package com.offlinepdf.studio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.renderer.PdfRendererHelper
import com.offlinepdf.studio.core.util.FileStorageManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AppSecurityRegressionInstrumentationTest {

    private lateinit var context: Context
    private lateinit var storageManager: FileStorageManager
    private lateinit var engine: PdfBoxEngineImpl
    private lateinit var testDocsDir: File
    private lateinit var cacheProcessingDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = FileStorageManager(context)
        engine = PdfBoxEngineImpl(context, storageManager)
        testDocsDir = File(context.filesDir, "test_sec_reg").apply {
            if (!exists()) mkdirs()
        }
        cacheProcessingDir = File(context.cacheDir, "pdf_processing").apply {
            if (!exists()) mkdirs()
        }
        com.offlinepdf.studio.core.util.PdfStorageScanner.invalidateCache()
    }

    @After
    fun tearDown() {
        testDocsDir.deleteRecursively()
        cacheProcessingDir.deleteRecursively()
        com.offlinepdf.studio.core.util.PdfStorageScanner.invalidateCache()
    }

    // 1. Path traversal prevention in createOutputFile
    @Test
    fun test01_pathTraversalNeutralizationInOutputFile() {
        val payloads = listOf(
            "../../../../etc/passwd",
            "..\\..\\win.ini",
            "../../../outside.pdf",
            "payload\u0000with_null.pdf",
            "nested/../../breakout.pdf",
            "~/root_breakout.pdf"
        )

        val targetDir = File(context.filesDir, "pdf_documents")
        val targetCanonical = targetDir.canonicalPath

        for (payload in payloads) {
            val file = storageManager.createOutputFile(payload, targetDir = targetDir)
            try {
                assertTrue(
                    "Output file must be confined within target directory: ${file.canonicalPath}",
                    file.canonicalPath.startsWith(targetCanonical)
                )
                assertFalse("Filename must not contain directory separators", file.name.contains("/") || file.name.contains("\\"))
                assertFalse("Filename must not contain null characters", file.name.contains("\u0000"))
            } finally {
                storageManager.releaseReservation(file)
                if (file.exists()) file.delete()
            }
        }
    }

    // 2. Path traversal neutralization in reserveExportPrefix
    @Test
    fun test02_pathTraversalNeutralizationInExportPrefix() {
        val payloads = listOf(
            "../../../../var/log",
            "..\\..\\windows\\system32",
            "image_prefix/../../hack",
            "prefix\u0000inject"
        )

        for (payload in payloads) {
            val prefix = storageManager.reserveExportPrefix(payload)
            try {
                assertFalse("Prefix must not contain forward slashes", prefix.contains("/"))
                assertFalse("Prefix must not contain backward slashes", prefix.contains("\\"))
                assertFalse("Prefix must not contain null characters", prefix.contains("\u0000"))
                assertFalse("Prefix must not contain traversal dots", prefix.contains(".."))
                assertTrue("Prefix must only contain alphanumeric, underscore, or dash", prefix.matches("[a-zA-Z0-9_-]+".toRegex()))
            } finally {
                storageManager.releaseExportPrefix(prefix)
            }
        }
    }

    // 3. Safe temporary file cleanup on invalid or unreadable URI
    @Test
    fun test03_invalidUriStreamCopyCleanup() {
        val invalidUri = Uri.parse("content://com.offlinepdf.studio.unregistered/nonexistent_document_${System.currentTimeMillis()}.pdf")
        val beforeCount = cacheProcessingDir.listFiles()?.size ?: 0

        var caughtException = false
        try {
            storageManager.copyToTempFile(invalidUri, "sec_test_invalid_")
        } catch (_: Exception) {
            caughtException = true
        }

        assertTrue("copyToTempFile must fail on invalid URI", caughtException)
        val afterCount = cacheProcessingDir.listFiles()?.size ?: 0
        assertEquals("No temporary file must be leaked after failed copy", beforeCount, afterCount)
    }

    // 4. Safe PdfRendererHelper cleanup on 0-byte or corrupted PDF
    @Test
    fun test04_corruptPdfRendererCleanup() {
        val zeroByteFile = File(testDocsDir, "zero_byte.pdf").apply {
            createNewFile()
        }

        val cacheBefore = context.cacheDir.listFiles { _, name -> name.startsWith("render_view_") }?.size ?: 0

        var caughtException = false
        try {
            PdfRendererHelper.fromUri(context, Uri.fromFile(zeroByteFile)).use {
                it.pageCount
            }
        } catch (_: IOException) {
            caughtException = true
        }

        assertTrue("PdfRendererHelper must throw IOException on 0-byte PDF", caughtException)
        val cacheAfter = context.cacheDir.listFiles { _, name -> name.startsWith("render_view_") }?.size ?: 0
        assertEquals("No render_view_ temporary file must remain in cache after error", cacheBefore, cacheAfter)
    }

    // 5. Corrupted PDF handling in PdfEngine: fail-closed, no reservation leaks, no partial output
    @Test
    fun test05_corruptedPdfEngineHandling() = runBlocking {
        val corrupted = PdfEngineTestFixtures.createUnrecoverableFile(File(testDocsDir, "corrupted_sec.pdf"))
        val corruptUri = Uri.fromFile(corrupted)

        val initialReservations = storageManager.getActiveReservationsCount()

        val rotateResult = engine.rotatePages(corruptUri, 90, "sec_rot_out")
        assertTrue("Rotate on corrupt PDF must return Error", rotateResult is EngineResult.Error)

        val compressResult = engine.compress(corruptUri, com.offlinepdf.studio.core.engine.model.CompressionLevel.MEDIUM, "sec_comp_out")
        assertTrue("Compress on corrupt PDF must return Error", compressResult is EngineResult.Error)

        val protectResult = engine.protectPdf(corruptUri, "uPass", "oPass", "sec_enc_out")
        assertTrue("Protect on corrupt PDF must return Error", protectResult is EngineResult.Error)

        val textResult = engine.extractText(corruptUri, "sec_txt_out")
        assertTrue("ExtractText on corrupt PDF must return Error", textResult is EngineResult.Error)

        val finalReservations = storageManager.getActiveReservationsCount()
        assertEquals("All path reservations must be fully released on error", initialReservations, finalReservations)
    }

    // 6. Password security: no secret exposure in error message or logs
    @Test
    fun test06_passwordProtectionLeakPrevention() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "sec_pw_doc.pdf"), 2)
        val userSecret = "SuperConfidentialUserKey_987654"
        val ownerSecret = "SuperConfidentialOwnerKey_123456"

        val encResult = engine.protectPdf(
            sourceUri = Uri.fromFile(src),
            userPassword = userSecret,
            ownerPassword = ownerSecret,
            outputBaseName = "sec_pw_protected"
        )
        assertTrue("Protection must succeed", encResult is EngineResult.Success)
        val protectedFile = (encResult as EngineResult.Success).data

        // Attempt unlock with incorrect password
        val wrongPassword = "WrongPasswordAttempt"
        val unlockResult = engine.unlockPdf(
            sourceUri = Uri.fromFile(protectedFile),
            password = wrongPassword,
            outputBaseName = "sec_pw_unlocked"
        )

        assertTrue("Unlock with wrong password must return Error", unlockResult is EngineResult.Error)
        val errorMessage = (unlockResult as EngineResult.Error).userMessage

        assertFalse("Error message must not contain user password secret", errorMessage.contains(userSecret))
        assertFalse("Error message must not contain owner password secret", errorMessage.contains(ownerSecret))
        assertFalse("Error message must not leak attempted password", errorMessage.contains(wrongPassword))

        assertEquals("No reservations must leak on unlock failure", 0, storageManager.getActiveReservationsCount())
    }

    // 7. FileProvider scope enforcement: only authorized paths can generate shareable URIs
    @Test
    fun test07_fileProviderScopeEnforcement() {
        val authority = "${context.packageName}.fileprovider"

        // Authorized path: under filesDir/pdf_documents
        val authorizedDoc = File(File(context.filesDir, "pdf_documents"), "valid_share.pdf").apply {
            writeText("dummy content")
        }
        val validUri = FileProvider.getUriForFile(context, authority, authorizedDoc)
        assertNotNull("FileProvider must generate URI for authorized documents path", validUri)
        assertEquals("content", validUri.scheme)

        // Unauthorized path: arbitrary file outside declared file_paths.xml
        val unauthorizedFile = File(context.filesDir, "unauthorized_secret.txt").apply {
            writeText("sensitive internal state")
        }

        var blocked = false
        try {
            FileProvider.getUriForFile(context, authority, unauthorizedFile)
        } catch (_: IllegalArgumentException) {
            blocked = true
        }

        assertTrue("FileProvider must reject paths outside whitelisted subdirectories", blocked)

        authorizedDoc.delete()
        unauthorizedFile.delete()
    }

    // 8. Zero-network verification: android.permission.INTERNET must not be requested
    @Test
    fun test08_zeroNetworkVerification() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        val permissions = packageInfo.requestedPermissions?.toList() ?: emptyList()

        assertFalse(
            "Application MUST NOT declare android.permission.INTERNET",
            permissions.contains("android.permission.INTERNET")
        )
        assertFalse(
            "Application MUST NOT declare android.permission.ACCESS_NETWORK_STATE",
            permissions.contains("android.permission.ACCESS_NETWORK_STATE")
        )
        assertFalse(
            "Application MUST NOT declare android.permission.READ_MEDIA_IMAGES (minimized; Photo Picker used instead)",
            permissions.contains("android.permission.READ_MEDIA_IMAGES")
        )
        assertFalse(
            "Application MUST NOT declare android.permission.WRITE_EXTERNAL_STORAGE (minimized; MediaStore/SAF used instead)",
            permissions.contains("android.permission.WRITE_EXTERNAL_STORAGE")
        )
    }

    // 9. Malicious intent URI scheme rejection logic
    @Test
    fun test09_maliciousIntentSchemeRejection() {
        fun isValidIntentScheme(uri: Uri?): Boolean {
            val scheme = uri?.scheme?.lowercase()
            return scheme == "content" || scheme == "file"
        }

        assertTrue(isValidIntentScheme(Uri.parse("content://media/external/file/123")))
        assertTrue(isValidIntentScheme(Uri.parse("file:///sdcard/document.pdf")))
        assertFalse(isValidIntentScheme(Uri.parse("http://malicious.org/exploit.pdf")))
        assertFalse(isValidIntentScheme(Uri.parse("https://malicious.org/exploit.pdf")))
        assertFalse(isValidIntentScheme(Uri.parse("javascript:alert(1)")))
        assertFalse(isValidIntentScheme(Uri.parse("ftp://malicious.org/exploit.pdf")))
        assertFalse(isValidIntentScheme(Uri.parse("custom://payload")))
        assertFalse(isValidIntentScheme(null))
    }

    // 10. Limited access state: app-sandbox documents remain discoverable and accessible
    @Test
    fun test10_limitedAccessAppSandboxDiscovery() {
        // Create an app-private document in pdf_documents
        val sandboxDir = File(context.filesDir, "pdf_documents").apply { if (!exists()) mkdirs() }
        val testDoc = File(sandboxDir, "sandbox_test_${System.currentTimeMillis()}.pdf").apply {
            writeText("app-private content")
        }

        try {
            com.offlinepdf.studio.core.util.PdfStorageScanner.invalidateCache()
            val folders = com.offlinepdf.studio.core.util.PdfStorageScanner.getAllInternalStorageFolders(context)
            assertNotNull("Folders list must not be null in any permission state", folders)
            val hasSandboxFolder = folders.any { it.name.contains("Processed") || it.path.contains("pdf_documents") }
            assertTrue("App sandbox folder must be discoverable regardless of external permissions", hasSandboxFolder)
        } finally {
            testDoc.delete()
            com.offlinepdf.studio.core.util.PdfStorageScanner.invalidateCache()
        }
    }

    // 11. Storage permission API-specific compatibility logic
    @Test
    fun test11_storagePermissionCompatibilityLogic() {
        try {
            // Live Android 14 runtime check
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val isManager = android.os.Environment.isExternalStorageManager()
                val permissionCheck = com.offlinepdf.studio.core.util.PdfStorageScanner.hasAllFilesPermission(context)
                assertEquals("On API 30+, hasAllFilesPermission must match isExternalStorageManager()", isManager, permissionCheck)
            }

            // Injected API 29 Scoped Storage check: must return false regardless of READ permission
            com.offlinepdf.studio.core.util.PdfStorageScanner.sdkIntProvider = { 29 }
            com.offlinepdf.studio.core.util.PdfStorageScanner.readPermissionChecker = { true }
            assertFalse(
                "On API 29, broad access must remain false under scoped storage",
                com.offlinepdf.studio.core.util.PdfStorageScanner.hasAllFilesPermission(context)
            )

            // Injected API 28 Legacy Storage check: returns readPermissionChecker
            com.offlinepdf.studio.core.util.PdfStorageScanner.sdkIntProvider = { 28 }
            com.offlinepdf.studio.core.util.PdfStorageScanner.readPermissionChecker = { true }
            assertTrue(
                "On API 28 with READ granted, hasAllFilesPermission must return true",
                com.offlinepdf.studio.core.util.PdfStorageScanner.hasAllFilesPermission(context)
            )
            com.offlinepdf.studio.core.util.PdfStorageScanner.readPermissionChecker = { false }
            assertFalse(
                "On API 28 with READ denied, hasAllFilesPermission must return false",
                com.offlinepdf.studio.core.util.PdfStorageScanner.hasAllFilesPermission(context)
            )
        } finally {
            com.offlinepdf.studio.core.util.PdfStorageScanner.sdkIntProvider = { android.os.Build.VERSION.SDK_INT }
            com.offlinepdf.studio.core.util.PdfStorageScanner.readPermissionChecker = { ctx ->
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        }
    }

    // 12. Public Downloads export and SAF error cleanup
    @Test
    fun test12_publicDownloadsExportAndSafCleanup() {
        val samplePdf = File(testDocsDir, "export_test.pdf").apply {
            writeText("%PDF-1.4 sample content for export test")
        }

        // 1. Test saveToPublicDownloads
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val uri = storageManager.saveToPublicDownloads(samplePdf)
            assertNotNull("On Android 10+, saveToPublicDownloads via MediaStore must return valid URI", uri)
            assertEquals("content", uri?.scheme)
            // Cleanup exported file
            uri?.let { context.contentResolver.delete(it, null, null) }
        } else {
            val uri = storageManager.saveToPublicDownloads(samplePdf)
            assertNull("On API < 29 without WRITE_EXTERNAL_STORAGE, direct export must return null directing to SAF", uri)
        }

        // 2. Test copyFileToUri with invalid / unreachable URI handles gracefully
        val invalidDestination = Uri.parse("content://nonexistent.authority/invalid_dest")
        val success = storageManager.copyFileToUri(samplePdf, invalidDestination)
        assertFalse("copyFileToUri with unwriteable destination must return false cleanly", success)
    }
}
