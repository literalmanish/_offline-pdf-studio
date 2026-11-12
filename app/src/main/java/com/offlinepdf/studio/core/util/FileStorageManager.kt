package com.offlinepdf.studio.core.util

import android.content.Context
import android.net.Uri
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
     * Purges temporary processing cache files older than 1 hour or manually called
     */
    fun clearTempFiles() {
        try {
            cacheProcessingDir.listFiles()?.forEach { file ->
                if (System.currentTimeMillis() - file.lastModified() > 3600_000) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }
}
