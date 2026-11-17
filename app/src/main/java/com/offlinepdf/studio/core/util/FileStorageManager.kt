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
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileStorageManager(private val context: Context) {

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
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output, bufferSize = 8192)
            }
        } ?: throw IllegalArgumentException("Could not open stream for URI: $uri")
        return tempFile
    }

    /**
     * Creates a new output PDF destination in the app's persistent documents directory
     */
    fun createOutputFile(baseName: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val cleanName = baseName.removeSuffix(".pdf").replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        return File(outputDir, "${cleanName}_$timestamp.pdf")
    }

    /**
     * Obtains a secure content:// URI via FileProvider to allow viewing/sharing without storage permissions
     */
    fun getShareableUri(file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    /**
     * Direct download to public Downloads directory without requiring storage permissions
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
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val subDir = File(downloadDir, "OfflinePDFStudio").apply { if (!exists()) mkdirs() }
                val destFile = File(subDir, file.name)
                file.copyTo(destFile, overwrite = true)
                Uri.fromFile(destFile)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Copies a processed File to an external destination chosen by the user via Storage Access Framework
     */
    fun copyFileToUri(file: File, destinationUri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            true
        } catch (e: Exception) {
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
