package com.offlinepdf.studio.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

data class StorageFolder(
    val id: String,
    val name: String,
    val path: String,
    val files: List<File>
)

object PdfStorageScanner {

    fun hasAllFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun requestAllFilesPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    /**
     * Finds all PDF files located in public Downloads directory and any subdirectories
     */
    fun getDownloadsPdfs(context: Context): List<File> {
        val results = mutableSetOf<File>()

        // 1. File system scan of Downloads folder
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadsDir.exists() && downloadsDir.canRead()) {
            scanDirectoryRecursively(downloadsDir, results, maxDepth = 4)
        }

        // 2. Query MediaStore for PDFs in Downloads
        queryMediaStoreForPdfs(context, results, filterPathContains = "Download")

        return results.filter { it.exists() && it.length() > 0 }.sortedByDescending { it.lastModified() }
    }

    /**
     * Scans internal storage and groups all discovered PDFs into folders
     */
    fun getAllInternalStorageFolders(context: Context): List<StorageFolder> {
        val allPdfs = mutableSetOf<File>()

        // 1. Processed files inside app sandbox
        val appDocs = File(context.filesDir, "pdf_documents")
        if (appDocs.exists()) {
            appDocs.listFiles { f -> f.extension.equals("pdf", true) }?.let { allPdfs.addAll(it) }
        }

        // 2. Scan standard public directories
        val standardDirs = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStorageDirectory()
        )

        standardDirs.forEach { dir ->
            if (dir.exists() && dir.canRead()) {
                scanDirectoryRecursively(dir, allPdfs, maxDepth = 3)
            }
        }

        // 3. Scan WhatsApp and Messenger documents if present
        val extRoot = Environment.getExternalStorageDirectory()
        listOf(
            File(extRoot, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents"),
            File(extRoot, "WhatsApp/Media/WhatsApp Documents"),
            File(extRoot, "Telegram/Telegram Documents")
        ).forEach { dir ->
            if (dir.exists() && dir.canRead()) {
                scanDirectoryRecursively(dir, allPdfs, maxDepth = 2)
            }
        }

        // 4. Query MediaStore to catch all system-indexed PDFs
        queryMediaStoreForPdfs(context, allPdfs)

        // Group by folder
        val validFiles = allPdfs.filter { it.exists() && it.length() > 0 }
        val groupedByFolder = validFiles.groupBy { it.parentFile ?: File("/") }

        val folderList = mutableListOf<StorageFolder>()

        groupedByFolder.forEach { (parentDir, files) ->
            val folderName = when {
                parentDir.absolutePath.contains("pdf_documents") -> "Processed (Studio)"
                parentDir.name.equals("Download", ignoreCase = true) || parentDir.name.equals("Downloads", ignoreCase = true) -> "Downloads"
                parentDir.name.equals("OfflinePDFStudio", ignoreCase = true) -> "Downloads / Studio"
                parentDir.name.equals("Documents", ignoreCase = true) -> "Documents"
                parentDir.name.contains("WhatsApp", ignoreCase = true) -> "WhatsApp Documents"
                parentDir.name.contains("Telegram", ignoreCase = true) -> "Telegram Documents"
                parentDir.name.isBlank() -> "Internal Storage Root"
                else -> parentDir.name
            }

            folderList.add(
                StorageFolder(
                    id = parentDir.absolutePath,
                    name = folderName,
                    path = parentDir.absolutePath,
                    files = files.sortedByDescending { it.lastModified() }
                )
            )
        }

        return folderList.sortedWith(
            compareByDescending<StorageFolder> { it.name == "Processed (Studio)" }
                .thenByDescending { it.name == "Downloads" }
                .thenByDescending { it.files.size }
        )
    }

    private fun scanDirectoryRecursively(dir: File, out: MutableSet<File>, maxDepth: Int, currentDepth: Int = 0) {
        if (currentDepth > maxDepth || !dir.exists() || !dir.canRead()) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && !child.name.startsWith(".")) {
                scanDirectoryRecursively(child, out, maxDepth, currentDepth + 1)
            } else if (child.isFile && child.extension.equals("pdf", true)) {
                out.add(child)
            }
        }
    }

    private fun queryMediaStoreForPdfs(context: Context, out: MutableSet<File>, filterPathContains: String? = null) {
        try {
            val collection = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.pdf'"
            val selectionArgs = arrayOf("application/pdf")

            context.contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val dataIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                while (cursor.moveToNext()) {
                    if (dataIndex != -1) {
                        val path = cursor.getString(dataIndex)
                        if (path != null) {
                            if (filterPathContains == null || path.contains(filterPathContains, ignoreCase = true)) {
                                val f = File(path)
                                if (f.exists() && f.isFile) {
                                    out.add(f)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
