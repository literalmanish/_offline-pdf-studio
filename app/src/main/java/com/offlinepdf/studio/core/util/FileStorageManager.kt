package com.offlinepdf.studio.core.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileStorageManager(
    private val context: Context,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() }
) {

    private val activeReservations = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val cacheProcessingDir: File
        get() = File(context.cacheDir, "pdf_processing").apply { if (!exists()) mkdirs() }

    private val outputDir: File
        get() = File(context.filesDir, "pdf_documents").apply { if (!exists()) mkdirs() }

    /**
     * Resolves display name and size from a content Uri
     */
    fun getUriMetadata(uri: Uri): Pair<String, Long> {
        var name = "document.pdf"
        var size = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        return Pair(name, size)
    }

    /**
     * Copies a Uri stream to a temporary processing file so libraries requiring File/seekable access can run safely
     */
    fun copyToTempFile(uri: Uri, prefix: String = "input_"): File {
        val tempFile = File.createTempFile(prefix, ".pdf", cacheProcessingDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Could not open stream for URI: $uri")
            input.use { inStream ->
                FileOutputStream(tempFile).use { output ->
                    inStream.copyTo(output, bufferSize = 8192)
                }
            }
            return tempFile
        } catch (t: Throwable) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw t
        }
    }

    /**
     * Creates a new output destination in the app's persistent documents directory (or specified targetDir).
     * Prevents TOCTOU races between concurrent operations by tracking active in-flight reservations
     * alongside filesystem existence checks.
     */
    fun createOutputFile(
        baseName: String,
        extension: String = "pdf",
        targetDir: File = outputDir
    ): File {
        if (!targetDir.exists()) targetDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestampProvider()))
        val ext = extension.removePrefix(".")
        val cleanName = baseName.removeSuffix(".$ext").removeSuffix(".pdf").replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        synchronized(activeReservations) {
            val baseFile = File(targetDir, "${cleanName}_$timestamp.$ext")
            if (!baseFile.exists() && activeReservations.add(baseFile.absolutePath)) {
                return baseFile
            }
            var counter = 1
            while (true) {
                val candidate = File(targetDir, "${cleanName}_${timestamp}_$counter.$ext")
                if (!candidate.exists() && activeReservations.add(candidate.absolutePath)) {
                    return candidate
                }
                counter++
            }
        }
    }

    /**
     * Checks if any files in targetDir match the given export prefix.
     */
    private fun hasMatchingFiles(targetDir: File, prefix: String): Boolean {
        if (!targetDir.exists()) return false
        val files = targetDir.listFiles { _, name ->
            name.startsWith("${prefix}_") || name.startsWith("${prefix}.")
        }
        return !files.isNullOrEmpty()
    }

    /**
     * Reserves a unique timestamped export prefix for multi-file exports (e.g. pdfToImages, extractImages).
     * Guarantees that concurrent and repeated exports receive distinct, non-overlapping prefixes.
     */
    fun reserveExportPrefix(
        baseName: String,
        targetDir: File = context.filesDir
    ): String {
        if (!targetDir.exists()) targetDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestampProvider()))
        val cleanName = baseName.removeSuffix(".pdf").replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        synchronized(activeReservations) {
            val basePrefix = "${cleanName}_$timestamp"
            val baseKey = File(targetDir, basePrefix).absolutePath
            if (!hasMatchingFiles(targetDir, basePrefix) && activeReservations.add(baseKey)) {
                return basePrefix
            }
            var counter = 1
            while (true) {
                val candidatePrefix = "${cleanName}_${timestamp}_$counter"
                val candidateKey = File(targetDir, candidatePrefix).absolutePath
                if (!hasMatchingFiles(targetDir, candidatePrefix) && activeReservations.add(candidateKey)) {
                    return candidatePrefix
                }
                counter++
            }
        }
    }

    /**
     * Releases an active path reservation after publication or cleanup
     */
    fun releaseReservation(file: File) {
        activeReservations.remove(file.absolutePath)
    }

    /**
     * Releases an active path reservation by string path
     */
    fun releaseReservation(path: String) {
        activeReservations.remove(path)
    }

    /**
     * Releases an export prefix reservation
     */
    fun releaseExportPrefix(prefix: String, targetDir: File = context.filesDir) {
        val key = File(targetDir, prefix).absolutePath
        activeReservations.remove(key)
    }

    /**
     * Returns the count of active in-flight reservations (used for test verification)
     */
    fun getActiveReservationsCount(): Int = synchronized(activeReservations) { activeReservations.size }

    /**
     * Obtains a secure content:// URI via FileProvider to allow viewing/sharing without storage permissions
     */
    fun getShareableUri(file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * Direct download to public Downloads directory without requiring storage permissions (API 29+)
     * On API < 29, returns null to direct user to Storage Access Framework flow.
     * Guarantees partial/incomplete downloads are cleaned upon failure.
     */
    fun saveToPublicDownloads(file: File, mimeType: String = "application/pdf"): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OfflinePDFStudio")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    try {
                        val outStream = resolver.openOutputStream(uri)
                            ?: throw IOException("Could not open output stream for MediaStore URI: $uri")
                        outStream.use { out ->
                            file.inputStream().use { input -> input.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (t: Throwable) {
                        try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                        throw t
                    }
                }
                uri
            } else {
                // On API < 29 without WRITE_EXTERNAL_STORAGE, direct public downloads creation is unpermitted
                // when targeting modern SDKs; user-directed Storage Access Framework (copyFileToUri) is used.
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Copies a processed File to an external destination chosen by the user via Storage Access Framework
     * Cleans incomplete destination if copying fails or is interrupted.
     */
    fun copyFileToUri(file: File, destinationUri: Uri): Boolean {
        return try {
            val out = context.contentResolver.openOutputStream(destinationUri)
                ?: return false
            try {
                out.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                true
            } catch (copyErr: Throwable) {
                try {
                    android.provider.DocumentsContract.deleteDocument(context.contentResolver, destinationUri)
                } catch (_: Throwable) {}
                throw copyErr
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Purges temporary processing cache files
     */
    fun clearTempFiles() {
        try {
            context.cacheDir.listFiles()?.forEach { file ->
                file.deleteRecursively()
            }
            cacheProcessingDir.mkdirs()
        } catch (_: Exception) {}
    }
}
