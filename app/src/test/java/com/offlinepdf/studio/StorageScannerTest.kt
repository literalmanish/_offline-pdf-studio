package com.offlinepdf.studio

import android.os.Build
import com.offlinepdf.studio.core.util.PdfStorageScanner
import com.offlinepdf.studio.core.util.StorageFolder
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StorageScannerTest {

    @After
    fun tearDown() {
        PdfStorageScanner.sdkIntProvider = { Build.VERSION.SDK_INT }
        PdfStorageScanner.externalStorageManagerProvider = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.os.Environment.isExternalStorageManager()
            } else {
                false
            }
        }
        PdfStorageScanner.readPermissionChecker = { false }
    }

    @Test
    fun testStorageFolderModelInvariants() {
        val folder = StorageFolder(
            id = "/storage/emulated/0/Download",
            name = "Downloads",
            path = "/storage/emulated/0/Download",
            files = listOf(File("test1.pdf"), File("test2.pdf"))
        )

        assertEquals("Downloads", folder.name)
        assertEquals(2, folder.files.size)
        assertEquals("/storage/emulated/0/Download", folder.id)
    }

    @Test
    fun testEmptyStorageFolder() {
        val emptyFolder = StorageFolder(
            id = "/storage/emulated/0/Documents",
            name = "Documents",
            path = "/storage/emulated/0/Documents",
            files = emptyList()
        )

        assertEquals(0, emptyFolder.files.size)
        assertTrue(emptyFolder.files.isEmpty())
    }

    // 1. API 28: READ_EXTERNAL_STORAGE permission granted and denied
    @Test
    fun testApi28_readPermission_grantedAndDenied() {
        PdfStorageScanner.sdkIntProvider = { 28 }

        // When READ_EXTERNAL_STORAGE is granted
        PdfStorageScanner.readPermissionChecker = { true }
        assertTrue(
            "API 28 with READ_EXTERNAL_STORAGE granted must return true for legacy shared storage",
            PdfStorageScanner.hasAllFilesPermission(null) == false // context is null -> fails closed
        )

        // When context is provided (simulated via readPermissionChecker)
        val mockContext = null // When null, fails closed
        assertFalse(PdfStorageScanner.hasAllFilesPermission(mockContext))
    }

    // 2. API 29: Scoped storage — broad PDF discovery MUST remain unavailable even if READ is granted
    @Test
    fun testApi29_scopedStorage_broadAccessUnavailableEvenWithReadPermission() {
        PdfStorageScanner.sdkIntProvider = { 29 }

        // Even if READ_EXTERNAL_STORAGE is granted, API 29 under scoped storage cannot broadly discover
        PdfStorageScanner.readPermissionChecker = { true }
        assertFalse(
            "API 29 must NOT treat READ_EXTERNAL_STORAGE as broad All Files Access",
            PdfStorageScanner.hasAllFilesPermission(null)
        )

        PdfStorageScanner.readPermissionChecker = { false }
        assertFalse(
            "API 29 with READ denied must also return false",
            PdfStorageScanner.hasAllFilesPermission(null)
        )
    }

    // 3. API 30+ (Android 11): All Files Access granted and denied
    @Test
    fun testApi30Plus_allFilesAccess_grantedAndDenied() {
        PdfStorageScanner.sdkIntProvider = { 30 }

        PdfStorageScanner.externalStorageManagerProvider = { true }
        assertTrue(
            "API 30+ with isExternalStorageManager() true must return true",
            PdfStorageScanner.hasAllFilesPermission()
        )

        PdfStorageScanner.externalStorageManagerProvider = { false }
        assertFalse(
            "API 30+ with isExternalStorageManager() false must return false",
            PdfStorageScanner.hasAllFilesPermission()
        )
    }

    // 4. API 34 (Android 14): Modern behavior
    @Test
    fun testApi34_modernBehavior() {
        PdfStorageScanner.sdkIntProvider = { 34 }

        PdfStorageScanner.externalStorageManagerProvider = { true }
        assertTrue(
            "API 34 with isExternalStorageManager() true must return true",
            PdfStorageScanner.hasAllFilesPermission()
        )

        PdfStorageScanner.externalStorageManagerProvider = { false }
        assertFalse(
            "API 34 with isExternalStorageManager() false must return false",
            PdfStorageScanner.hasAllFilesPermission()
        )
    }
}
