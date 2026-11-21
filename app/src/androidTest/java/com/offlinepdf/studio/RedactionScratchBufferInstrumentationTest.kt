package com.offlinepdf.studio

import android.content.Context
import android.content.ContextWrapper
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.util.FileStorageManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android Instrumentation Tests for Bounded Scratch Buffering & Redaction Publication Lifecycle (H-02).
 *
 * Verifies:
 * 1. Both documents use the configured scratch policy (4 MiB main memory, 64 MiB total scratch storage).
 * 2. A stream exceeding 4 MiB spills to disk.
 * 3. Scratch files appear in the configured private processing directory.
 * 4. Scratch files disappear after normal PDDocument.close().
 * 5. Scratch files disappear after an induced processing failure.
 * 6. Scratch initialization with an invalid directory fails closed.
 * 7. Exceeding the configured total scratch-storage limit produces a controlled IOException.
 * 8. A failed redaction does not leave a partial final PDF.
 * 9. Successful redaction publishes a structurally valid PDF.
 * 10. Cancellation immediately before publication cleans staging and leaves no output file.
 * 11. Cancellation immediately after move preserves committed output without deletion.
 * 12. Structural staging verification fails closed without publishing.
 * 13. A failed cleanup is reported without deleting an already-published output.
 */
@RunWith(AndroidJUnit4::class)
class RedactionScratchBufferInstrumentationTest {

    private class IsolatedTestContext(
        base: Context,
        private val rootDir: File
    ) : ContextWrapper(base) {
        private val isolatedFilesDir = File(rootDir, "files").apply { mkdirs() }
        private val isolatedCacheDir = File(rootDir, "cache").apply { mkdirs() }

        override fun getFilesDir(): File = isolatedFilesDir
        override fun getCacheDir(): File = isolatedCacheDir
    }

    private lateinit var isolatedRootDir: File
    private lateinit var testContext: Context
    private lateinit var testDocsDir: File
    private lateinit var testCacheDir: File
    private lateinit var storageManager: FileStorageManager
    private lateinit var engine: PdfBoxEngineImpl

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(appContext)
        isolatedRootDir = File(appContext.cacheDir, "test_scratch_lifecycle_${System.nanoTime()}").apply {
            mkdirs()
        }
        testContext = IsolatedTestContext(appContext, isolatedRootDir)
        testDocsDir = File(testContext.filesDir, "pdf_documents").apply { mkdirs() }
        testCacheDir = testContext.cacheDir
        storageManager = FileStorageManager(testContext)
        engine = PdfBoxEngineImpl(testContext, storageManager = storageManager)
    }

    @After
    fun tearDown() {
        if (::isolatedRootDir.isInitialized) {
            isolatedRootDir.deleteRecursively()
        }
    }

    private fun createSamplePdf(file: File): File {
        PDDocument().use { doc ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(595f, 842f))
            doc.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 750f)
                cs.showText("Offline PDF Studio Bounded Buffering Test Document")
                cs.endText()
            }
            doc.save(file)
        }
        return file
    }

    // 1. Both documents use the configured scratch policy
    @Test
    fun test01_bothDocumentsUseConfiguredScratchPolicy() {
        val memSetting = MemoryUsageSetting.setupMixed(
            PdfBoxEngineImpl.PDFBOX_MAIN_MEMORY_SCRATCH_BYTES,
            PdfBoxEngineImpl.PDFBOX_TOTAL_SCRATCH_STORAGE_BYTES
        ).setTempDir(testCacheDir)

        assertEquals("Main memory must be restricted to 4 MiB", 4L * 1024L * 1024L, memSetting.maxMainMemoryBytes)
        assertEquals("Total scratch storage must be restricted to 64 MiB", 64L * 1024L * 1024L, memSetting.maxStorageBytes)
        assertTrue("Main memory must be restricted", memSetting.isMainMemoryRestricted)
        assertTrue("Storage must be restricted", memSetting.isStorageRestricted)

        ScratchFile(memSetting).use { scratch ->
            val inMemMaxField = ScratchFile::class.java.getDeclaredField("inMemoryMaxPageCount").apply { isAccessible = true }
            val inMemMax = inMemMaxField.getInt(scratch)
            assertEquals("4 MiB main memory equates to 1024 pages of 4096 B", 1024, inMemMax)

            val maxPagesField = ScratchFile::class.java.getDeclaredField("maxPageCount").apply { isAccessible = true }
            val maxPages = maxPagesField.getInt(scratch)
            assertEquals("64 MiB total storage equates to 16384 pages of 4096 B", 16384, maxPages)

            val restrictedField = ScratchFile::class.java.getDeclaredField("maxMainMemoryIsRestricted").apply { isAccessible = true }
            assertTrue(restrictedField.getBoolean(scratch))

            val useScratchField = ScratchFile::class.java.getDeclaredField("useScratchFile").apply { isAccessible = true }
            assertTrue(useScratchField.getBoolean(scratch))
        }

        // Verify destination document constructed via createBoundedTargetDocument
        engine.createBoundedTargetDocument(memSetting).use { targetDoc ->
            assertNotNull("COSDocument must not be null", targetDoc.document)
            assertNotNull("Document catalog root must not be null", targetDoc.documentCatalog)
            assertNotNull("Pages dictionary must not be null", targetDoc.pages)
            assertEquals("Initial page count must be 0", 0, targetDoc.numberOfPages)

            val page = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(100f, 100f))
            targetDoc.addPage(page)
            assertEquals("Page count after addPage must be 1", 1, targetDoc.numberOfPages)
        }
    }

    // 2. A stream exceeding 4 MiB spills to disk
    @Test
    fun test02_streamExceeding4MiBSpillsToDisk() {
        val scratchDir = File(testCacheDir, "pdf_scratch").apply { mkdirs() }
        val memSetting = MemoryUsageSetting.setupMixed(4L * 1024L * 1024L, 64L * 1024L * 1024L)
            .setTempDir(scratchDir)

        ScratchFile(memSetting).use { scratch ->
            val buffer = scratch.createBuffer()
            val chunk = ByteArray(64 * 1024) { 0x41.toByte() } // 64 KB chunks
            // Write 5 MB (> 4 MiB main-memory limit)
            for (i in 0 until 80) {
                buffer.write(chunk)
            }

            val tmpFiles = scratchDir.listFiles { _, name -> name.startsWith("PDFBox") && name.endsWith(".tmp") }
            assertNotNull("Scratch files array must not be null", tmpFiles)
            assertTrue("Scratch file must be created on disk when exceeding 4 MiB", tmpFiles!!.isNotEmpty())
            val scratchDiskFile = tmpFiles.first()
            assertTrue("Scratch disk file must have non-zero length", scratchDiskFile.length() > 0L)
            // 5 MB - 4 MB = ~1 MB written to disk
            assertTrue(
                "Disk file length should be approximately ~1 MB (was ${scratchDiskFile.length()} bytes)",
                scratchDiskFile.length() >= 900_000L
            )
            buffer.close()
        }
    }

    // 3. Scratch files appear in the configured private processing directory
    @Test
    fun test03_scratchFilesAppearInConfiguredPrivateProcessingDirectory() {
        val scratchDir = File(testCacheDir, "pdf_scratch").apply { mkdirs() }
        val memSetting = MemoryUsageSetting.setupMixed(4L * 1024L * 1024L, 64L * 1024L * 1024L)
            .setTempDir(scratchDir)

        ScratchFile(memSetting).use { scratch ->
            val buffer = scratch.createBuffer()
            val chunk = ByteArray(64 * 1024) { 0x42.toByte() }
            for (i in 0 until 80) { // 5 MB
                buffer.write(chunk)
            }

            val tmpFiles = scratchDir.listFiles { _, name -> name.startsWith("PDFBox") && name.endsWith(".tmp") }
            assertNotNull(tmpFiles)
            assertEquals("Exactly one scratch file must appear in private scratchDir", 1, tmpFiles!!.size)
            assertEquals("Scratch file parent must be scratchDir", scratchDir.canonicalPath, tmpFiles.first().parentFile?.canonicalPath)
            buffer.close()
        }
    }

    // 4. Scratch files disappear after normal PDDocument.close()
    @Test
    fun test04_scratchFilesDisappearAfterNormalClose() {
        val scratchDir = File(testCacheDir, "pdf_scratch").apply { mkdirs() }
        val memSetting = MemoryUsageSetting.setupMixed(4L * 1024L * 1024L, 64L * 1024L * 1024L)
            .setTempDir(scratchDir)

        var diskFile: File? = null
        ScratchFile(memSetting).use { scratch ->
            val buffer = scratch.createBuffer()
            val chunk = ByteArray(64 * 1024) { 0x43.toByte() }
            for (i in 0 until 80) {
                buffer.write(chunk)
            }
            val tmpFiles = scratchDir.listFiles { _, name -> name.startsWith("PDFBox") && name.endsWith(".tmp") }
            assertNotNull(tmpFiles)
            assertTrue(tmpFiles!!.isNotEmpty())
            diskFile = tmpFiles.first()
            assertTrue("Disk file must exist before close", diskFile!!.exists())
            buffer.close()
        }

        assertNotNull("Disk file reference must have been captured", diskFile)
        assertFalse("Scratch file must be deleted after ScratchFile.close()", diskFile!!.exists())
        val remaining = scratchDir.listFiles { _, name -> name.startsWith("PDFBox") && name.endsWith(".tmp") }
        assertTrue("No scratch files must remain after close", remaining.isNullOrEmpty())
    }

    // 5. Scratch files disappear after an induced processing failure
    @Test
    fun test05_scratchFilesDisappearAfterInducedProcessingFailure() {
        val scratchDir = File(testCacheDir, "pdf_scratch").apply { mkdirs() }
        val memSetting = MemoryUsageSetting.setupMixed(4L * 1024L * 1024L, 64L * 1024L * 1024L)
            .setTempDir(scratchDir)

        var diskFile: File? = null
        try {
            ScratchFile(memSetting).use { scratch ->
                val buffer = scratch.createBuffer()
                val chunk = ByteArray(64 * 1024) { 0x44.toByte() }
                for (i in 0 until 80) {
                    buffer.write(chunk)
                }
                val tmpFiles = scratchDir.listFiles { _, name -> name.startsWith("PDFBox") && name.endsWith(".tmp") }
                diskFile = tmpFiles?.firstOrNull()
                assertTrue("Scratch file must exist on disk", diskFile?.exists() == true)

                // Induce processing failure
                throw RuntimeException("Induced processing failure mid-operation")
            }
        } catch (_: RuntimeException) {
            // Expected
        }

        assertNotNull(diskFile)
        assertFalse("Scratch file must be deleted even if an exception occurred in use block", diskFile!!.exists())
        val remaining = scratchDir.listFiles { _, name -> name.startsWith("PDFBox") && name.endsWith(".tmp") }
        assertTrue("Scratch directory must be clean after exception", remaining.isNullOrEmpty())
    }

    // 6. Scratch initialization with an invalid directory fails closed
    @Test
    fun test06_scratchInitializationWithInvalidDirectoryFailsClosed() = runBlocking {
        // Create an invalid scratch path: create a regular file named 'pdf_scratch' so mkdirs fails
        val invalidDir = File(testCacheDir, "pdf_scratch")
        invalidDir.createNewFile() // Regular file, cannot be a directory
        assertTrue("invalidDir exists as regular file", invalidDir.isFile)

        val fixture = createSamplePdf(File(testDocsDir, "fixture_fail_closed.pdf"))
        val result = engine.redactArea(
            sourceUri = Uri.fromFile(fixture),
            pageIndex = 0,
            xRatio = 0.1f,
            yRatio = 0.1f,
            widthRatio = 0.5f,
            heightRatio = 0.2f,
            outputBaseName = "test_invalid_scratch_dir"
        )

        assertTrue("Redaction must fail closed when scratch directory is invalid", result is EngineResult.Error)
        val ex = (result as EngineResult.Error).exception
        assertNotNull("Exception must be recorded", ex)
        assertTrue("Exception message or cause must indicate scratch directory failure",
            ex.message?.contains("scratch", ignoreCase = true) == true ||
            ex.cause?.message?.contains("scratch", ignoreCase = true) == true
        )
    }

    // 7. Exceeding the configured total scratch-storage limit produces a controlled IOException
    @Test
    fun test07_exceedingTotalScratchStorageLimitProducesControlledIOException() {
        val scratchDir = File(testCacheDir, "pdf_scratch").apply { mkdirs() }
        // Set main memory to 32 KB and total storage to 64 KB (16 pages of 4 KB)
        val memSetting = MemoryUsageSetting.setupMixed(32L * 1024L, 64L * 1024L)
            .setTempDir(scratchDir)

        try {
            ScratchFile(memSetting).use { scratch ->
                val buffer = scratch.createBuffer()
                val chunk = ByteArray(16 * 1024) { 0x55.toByte() }
                // Try writing 128 KB (exceeds 64 KB total storage limit)
                for (i in 0 until 8) {
                    buffer.write(chunk)
                }
                fail("Writing beyond maxStorageBytes must throw IOException")
            }
        } catch (e: IOException) {
            assertTrue(
                "IOException must report scratch file memory exceeded (was: ${e.message})",
                e.message?.contains("Maximum allowed scratch file memory exceeded", ignoreCase = true) == true
            )
        }
    }

    // 8. A failed redaction does not leave a partial final PDF
    @Test
    fun test08_failedRedactionDoesNotLeavePartialFinalPdf() = runBlocking {
        // Create an unparseable 0-byte file
        val corruptFile = File(testDocsDir, "corrupt_input.pdf").apply { createNewFile() }
        val outputBaseName = "test_no_partial_leak"

        val result = engine.redactArea(
            sourceUri = Uri.fromFile(corruptFile),
            pageIndex = 0,
            xRatio = 0.1f,
            yRatio = 0.1f,
            widthRatio = 0.5f,
            heightRatio = 0.2f,
            outputBaseName = outputBaseName
        )

        assertTrue("Result must be error for corrupt input", result is EngineResult.Error)

        val destinationFiles = testDocsDir.listFiles { _, name -> name.startsWith(outputBaseName) }
        assertTrue(
            "Destination directory must contain no partial or completed files for this output base name",
            destinationFiles.isNullOrEmpty()
        )
    }

    // 9. Successful redaction publishes a structurally valid PDF
    @Test
    fun test09_successfulRedactionPublishesStructurallyValidPdf() = runBlocking {
        val fixture = createSamplePdf(File(testDocsDir, "fixture_valid_redaction.pdf"))
        val outputBaseName = "test_valid_published"

        val result = engine.redactArea(
            sourceUri = Uri.fromFile(fixture),
            pageIndex = 0,
            xRatio = 0.1f,
            yRatio = 0.1f,
            widthRatio = 0.5f,
            heightRatio = 0.2f,
            outputBaseName = outputBaseName
        )

        assertTrue("Redaction must succeed", result is EngineResult.Success)
        val publishedFile = (result as EngineResult.Success).data
        assertTrue("Published file must exist on disk", publishedFile.exists())
        assertTrue("Published file length must be > 0", publishedFile.length() > 0L)

        // Verify PDF magic header
        FileInputStream(publishedFile).use { input ->
            val header = ByteArray(5)
            val bytesRead = input.read(header)
            assertEquals(5, bytesRead)
            assertEquals("%PDF-", String(header, Charsets.US_ASCII))
        }

        // Structural verification via PDFBox
        PDDocument.load(publishedFile).use { doc ->
            assertFalse("Document must not be encrypted", doc.isEncrypted)
            assertEquals("Document must have 1 page", 1, doc.numberOfPages)
        }

        // Visual rendering verification via Android PdfRenderer
        ParcelFileDescriptor.open(publishedFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                assertEquals(1, renderer.pageCount)
                val page = renderer.openPage(0)
                val bmp = android.graphics.Bitmap.createBitmap(page.width, page.height, android.graphics.Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bmp.recycle()
            }
        }
    }

    // 10. Cancellation immediately before publication cleans staging and leaves no output file
    @Test
    fun test10_cancellationImmediatelyBeforePublicationCleansStagingAndLeavesNoOutput() = runBlocking {
        val fixture = createSamplePdf(File(testDocsDir, "fixture_cancel_before_pub.pdf"))
        val outputBaseName = "test_cancel_before_pub"

        val engineWithPrePublishCancel = PdfBoxEngineImpl(
            context = testContext,
            storageManager = storageManager,
            prePublishAction = { stage ->
                // Cancel immediately before publication lock and move
                throw CancellationException("Induced cancellation immediately before publication commit")
            }
        )

        var caughtCancellation = false
        try {
            engineWithPrePublishCancel.redactArea(
                sourceUri = Uri.fromFile(fixture),
                pageIndex = 0,
                xRatio = 0.1f,
                yRatio = 0.1f,
                widthRatio = 0.5f,
                heightRatio = 0.2f,
                outputBaseName = outputBaseName
            )
        } catch (e: CancellationException) {
            caughtCancellation = true
        }

        assertTrue("CancellationException must be propagated before publication", caughtCancellation)

        // Verify no orphaned staging files remain
        val stagingFiles = testDocsDir.listFiles { _, name -> name.contains("stage_redact") }
        assertTrue("No staging files must remain after cancellation", stagingFiles.isNullOrEmpty())

        // Verify zero published files exist
        val publishedFiles = testDocsDir.listFiles { _, name -> name.startsWith(outputBaseName) }
        assertTrue("No published file must exist when cancelled before publication", publishedFiles.isNullOrEmpty())

        // Verify no scratch files remain in scratchDir
        val scratchDir = File(testCacheDir, "pdf_scratch")
        if (scratchDir.exists()) {
            val scratchFiles = scratchDir.listFiles { _, name -> name.startsWith("PDFBox") && name.endsWith(".tmp") }
            assertTrue("No scratch files must remain after cancellation", scratchFiles.isNullOrEmpty())
        }

        // Verify job counter was cleanly decremented
        assertEquals("Active redaction jobs must be 0 after cancellation", 0, PdfBoxEngineImpl.activeRedactionJobs.get())
    }

    // 11. Cancellation immediately after move preserves committed output
    @Test
    fun test11_cancellationImmediatelyAfterMovePreservesCommittedOutput() = runBlocking {
        val fixture = createSamplePdf(File(testDocsDir, "fixture_cancel_after_move.pdf"))
        val outputBaseName = "test_cancel_after_move"

        val engineWithPostMoveCancel = PdfBoxEngineImpl(
            context = testContext,
            storageManager = storageManager,
            afterLinkAction = { stage, dest ->
                // Throw cancellation after the move has already succeeded
                throw CancellationException("Induced cancellation immediately after move commit")
            }
        )

        val result = engineWithPostMoveCancel.redactArea(
            sourceUri = Uri.fromFile(fixture),
            pageIndex = 0,
            xRatio = 0.1f,
            yRatio = 0.1f,
            widthRatio = 0.5f,
            heightRatio = 0.2f,
            outputBaseName = outputBaseName
        )

        assertTrue("Redaction must succeed and return committed file even if cancelled after move", result is EngineResult.Success)
        val publishedFile = (result as EngineResult.Success).data
        assertTrue("Committed output file must exist", publishedFile.exists())
        assertTrue("Committed output file must have non-zero length", publishedFile.length() > 0L)

        // Verify published file is structurally valid
        PDDocument.load(publishedFile).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }

        // Verify no staging files remain
        val stagingFiles = testDocsDir.listFiles { _, name -> name.contains("stage_redact") }
        assertTrue("No staging files must remain after publication", stagingFiles.isNullOrEmpty())

        // Verify job counter was cleanly decremented
        assertEquals("Active redaction jobs must be 0 after post-move cancellation handling", 0, PdfBoxEngineImpl.activeRedactionJobs.get())
    }

    // 12. Structural staging verification fails closed without publishing
    @Test
    fun test12_structuralStagingVerificationFailsClosedWithoutPublishing() = runBlocking {
        val memSetting = MemoryUsageSetting.setupMixed(
            PdfBoxEngineImpl.PDFBOX_MAIN_MEMORY_SCRATCH_BYTES,
            PdfBoxEngineImpl.PDFBOX_TOTAL_SCRATCH_STORAGE_BYTES
        ).setTempDir(testCacheDir)

        // Test 1: Invalid header fails verification
        val invalidHeaderFile = File(testDocsDir, "invalid_header.tmp").apply {
            writeBytes("NOT_A_VALID_PDF_HEADER".toByteArray())
        }
        try {
            engine.verifyStagedPdf(invalidHeaderFile, 1, 0, memSetting)
            fail("verifyStagedPdf must throw IOException for invalid header")
        } catch (e: IOException) {
            assertTrue("Exception message must indicate header failure", e.message?.contains("invalid PDF header") == true)
        } finally {
            invalidHeaderFile.delete()
        }

        // Test 2: Page count mismatch fails verification
        val sampleFile = createSamplePdf(File(testDocsDir, "sample_one_page.pdf"))
        try {
            // Expected 5 pages, but sample has only 1 page
            engine.verifyStagedPdf(sampleFile, 5, 0, memSetting)
            fail("verifyStagedPdf must throw IOException for page count mismatch")
        } catch (e: IOException) {
            assertTrue("Exception message must indicate page count mismatch", e.message?.contains("page count mismatch") == true)
        } finally {
            sampleFile.delete()
        }

        // Test 3: Zero-length file fails verification
        val emptyFile = File(testDocsDir, "empty.tmp").apply { createNewFile() }
        try {
            engine.verifyStagedPdf(emptyFile, 1, 0, memSetting)
            fail("verifyStagedPdf must throw IOException for empty file")
        } catch (e: IOException) {
            assertTrue("Exception message must indicate missing/empty file", e.message?.contains("missing or empty") == true)
        } finally {
            emptyFile.delete()
        }
    }

    // 13. A failed cleanup is reported without deleting an already-published output
    @Test
    fun test13_failedCleanupReportedWithoutDeletingAlreadyPublishedOutput() = runBlocking {
        val fixture = createSamplePdf(File(testDocsDir, "fixture_failed_cleanup.pdf"))
        val outputBaseName = "test_failed_cleanup_safety"

        val loggedWarnings = mutableListOf<String>()
        val engineWithFailingDeleter = PdfBoxEngineImpl(
            context = testContext,
            storageManager = storageManager,
            publishOperation = { source, target ->
                // Simulate a copy publication where stage is retained
                Files.copy(source, target)
                target
            },
            fileDeleter = { file ->
                if (file.name.contains("stage_redact")) {
                    false // Simulate deletion failure for staging
                } else {
                    file.delete()
                }
            },
            cleanupWarningLogger = { msg ->
                loggedWarnings.add(msg)
            }
        )

        val result = engineWithFailingDeleter.redactArea(
            sourceUri = Uri.fromFile(fixture),
            pageIndex = 0,
            xRatio = 0.1f,
            yRatio = 0.1f,
            widthRatio = 0.5f,
            heightRatio = 0.2f,
            outputBaseName = outputBaseName
        )

        assertTrue("Redaction must still succeed even if non-critical staging cleanup fails", result is EngineResult.Success)
        val publishedFile = (result as EngineResult.Success).data
        assertTrue("Published output file must exist and NOT be deleted by failed cleanup handling", publishedFile.exists())
        assertTrue("Published output file must have non-zero size", publishedFile.length() > 0L)
        assertTrue("Warning must be recorded for failed staging deletion", loggedWarnings.any { it.contains("staging") || it.contains("stage_redact") })
    }

    // 14. Destination-document constructor test: creates with createBoundedTargetDocument, adds pages, saves, reloads, verifies structure
    @Test
    fun test14_constructorRoundTrip_createBoundedTargetDocument() = runBlocking {
        val memSetting = MemoryUsageSetting.setupMixed(
            PdfBoxEngineImpl.PDFBOX_MAIN_MEMORY_SCRATCH_BYTES,
            PdfBoxEngineImpl.PDFBOX_TOTAL_SCRATCH_STORAGE_BYTES
        ).setTempDir(testCacheDir)

        val targetFile = File(testDocsDir, "roundtrip_bounded_doc.pdf")
        val doc = engine.createBoundedTargetDocument(memSetting)
        try {
            val page1 = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(595f, 842f))
            doc.addPage(page1)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page1).use { cs ->
                cs.beginText()
                cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD, 12f)
                cs.newLineAtOffset(50f, 750f)
                cs.showText("Page 1 Bounded Document Content")
                cs.endText()
            }

            val page2 = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(595f, 842f))
            doc.addPage(page2)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page2).use { cs ->
                cs.beginText()
                cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 750f)
                cs.showText("Page 2 Bounded Document Content")
                cs.endText()
            }

            doc.save(targetFile)
        } finally {
            doc.close()
        }

        assertTrue("Saved document must exist", targetFile.exists())
        assertTrue("Saved document must have non-zero size", targetFile.length() > 0L)

        // Reload with bounded MemoryUsageSetting and verify structure
        PDDocument.load(targetFile, memSetting).use { reloaded ->
            assertNotNull("Document catalog must not be null", reloaded.documentCatalog)
            assertEquals("Document version must be 1.4", 1.4f, reloaded.version, 0.01f)
            assertNotNull("Document pages tree must not be null", reloaded.pages)
            assertEquals("Document must have exactly 2 pages", 2, reloaded.numberOfPages)
        }
    }

    // 15. Actual staged-page rendering verification succeeds for valid document
    @Test
    fun test15_verifyStagedPdf_validDocumentRendersCleanly() = runBlocking {
        val memSetting = MemoryUsageSetting.setupMixed(
            PdfBoxEngineImpl.PDFBOX_MAIN_MEMORY_SCRATCH_BYTES,
            PdfBoxEngineImpl.PDFBOX_TOTAL_SCRATCH_STORAGE_BYTES
        ).setTempDir(testCacheDir)

        val validFile = createSamplePdf(File(testDocsDir, "valid_stage_render_test.pdf"))
        try {
            // Must succeed cleanly without any exception, executing low-res render verification
            engine.verifyStagedPdf(validFile, 1, 0, memSetting)
        } finally {
            validFile.delete()
        }
    }

    // 16. Actual staged-page rendering verification fails closed for invalid/unrenderable document
    @Test
    fun test16_verifyStagedPdf_invalidUnrenderableDocumentFailsVerification() = runBlocking {
        val memSetting = MemoryUsageSetting.setupMixed(
            PdfBoxEngineImpl.PDFBOX_MAIN_MEMORY_SCRATCH_BYTES,
            PdfBoxEngineImpl.PDFBOX_TOTAL_SCRATCH_STORAGE_BYTES
        ).setTempDir(testCacheDir)

        val unrenderableFile = File(testDocsDir, "corrupted_unrenderable.pdf").apply {
            writeBytes("%PDF-1.4\n%Corrupted content that cannot be parsed as valid PDF\n%%EOF".toByteArray())
        }

        try {
            engine.verifyStagedPdf(unrenderableFile, 1, 0, memSetting)
            fail("verifyStagedPdf must throw IOException for unrenderable or corrupted document")
        } catch (e: IOException) {
            assertTrue("Expected IOException during verification", e.message != null)
        } finally {
            unrenderableFile.delete()
        }
    }

    // 17. Cancellation while waiting for publication lock
    @Test
    fun test17_cancellationWhileWaitingForPublicationLock() = runBlocking {
        val fixture = createSamplePdf(File(testDocsDir, "fixture_lock_wait_cancel.pdf"))
        val outputBaseName = "test_lock_wait_cancel"

        val lockAcquiredLatch = CountDownLatch(1)
        val testCanProceedLatch = CountDownLatch(1)
        val lockThread = Thread {
            synchronized(PdfBoxEngineImpl.publicationLock) {
                lockAcquiredLatch.countDown()
                testCanProceedLatch.await(5, TimeUnit.SECONDS)
            }
        }
        lockThread.start()

        // Wait until background thread holds publicationLock
        assertTrue("Background thread must acquire publicationLock", lockAcquiredLatch.await(3, TimeUnit.SECONDS))

        var jobCaughtCancellation = false
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                engine.redactArea(
                    sourceUri = Uri.fromFile(fixture),
                    pageIndex = 0,
                    xRatio = 0.1f,
                    yRatio = 0.1f,
                    widthRatio = 0.5f,
                    heightRatio = 0.2f,
                    outputBaseName = outputBaseName
                )
            } catch (e: CancellationException) {
                jobCaughtCancellation = true
            }
        }

        // Give the redaction job time to start and block waiting on publicationLock
        Thread.sleep(500)

        // Cancel the redaction job while it is waiting for publicationLock
        job.cancel()

        // Release the lock so the thread can exit and the cancelled job can detect cancellation upon entry
        testCanProceedLatch.countDown()
        lockThread.join(3000)
        job.join()

        assertTrue("CancellationException must be caught when cancelled while waiting for publication lock", jobCaughtCancellation)

        // Verify zero published files exist
        val publishedFiles = testDocsDir.listFiles { _, name -> name.startsWith(outputBaseName) }
        assertTrue("No published file must exist when cancelled while waiting for publication lock", publishedFiles.isNullOrEmpty())

        // Verify no orphaned staging files remain
        val stagingFiles = testDocsDir.listFiles { _, name -> name.contains("stage_redact") }
        assertTrue("No staging files must remain after cancellation", stagingFiles.isNullOrEmpty())

        // Verify job counter was cleanly decremented
        assertEquals("Active redaction jobs must be 0 after cancellation", 0, PdfBoxEngineImpl.activeRedactionJobs.get())
    }

    // 18. Post-publication destination verification failure preserves committed file and reports verification problem explicitly
    @Test
    fun test18_failureInPostPublicationVerifierPreservesCommittedOutput() = runBlocking {
        val fixture = createSamplePdf(File(testDocsDir, "fixture_post_verifier_fail.pdf"))
        val outputBaseName = "test_post_verifier_fail"
        val loggedWarnings = mutableListOf<String>()

        val engineWithFailingVerifier = PdfBoxEngineImpl(
            context = testContext,
            storageManager = storageManager,
            publicationVerifier = { _, _ -> false }, // Simulated verifier failure after publication commit
            cleanupWarningLogger = { msg -> loggedWarnings.add(msg) }
        )

        val result = engineWithFailingVerifier.redactArea(
            sourceUri = Uri.fromFile(fixture),
            pageIndex = 0,
            xRatio = 0.1f,
            yRatio = 0.1f,
            widthRatio = 0.5f,
            heightRatio = 0.2f,
            outputBaseName = outputBaseName
        )

        // Case C: Must NOT silently report ordinary success; must report verification failure explicitly
        assertTrue("Result must be EngineResult.Error on post-publication verification failure", result is EngineResult.Error)
        val error = result as EngineResult.Error
        assertTrue("Error must be classified as post-publication failure", error.isPostPublicationFailure)
        assertNotNull("Committed file must be referenced in error result", error.committedFile)
        val committedFile = error.committedFile!!
        assertTrue("Committed output file must exist on disk and NOT be deleted", committedFile.exists())
        assertTrue("Committed output file must have non-zero size", committedFile.length() > 0L)
        assertTrue("Exception must be PostPublicationVerificationException", error.exception is com.offlinepdf.studio.core.engine.model.PostPublicationVerificationException)
        assertTrue("Warning must be recorded for failed post-publication verification", loggedWarnings.any { it.contains("verification check failed") })

        // Verify staging file was cleaned up
        val stagingFiles = testDocsDir.listFiles { _, name -> name.contains("stage_redact") }
        assertTrue("Staging file must be cleaned up even when post-publication verification fails", stagingFiles.isNullOrEmpty())
    }

    // 19. Failure in nonessential post-publication hook preserves committed output and returns ordinary success
    @Test
    fun test19_failureInPostPublicationHookPreservesCommittedOutput() = runBlocking {
        val fixture = createSamplePdf(File(testDocsDir, "fixture_post_hook_fail.pdf"))
        val outputBaseName = "test_post_hook_fail"
        val loggedWarnings = mutableListOf<String>()

        val engineWithFailingHook = PdfBoxEngineImpl(
            context = testContext,
            storageManager = storageManager,
            afterLinkAction = { _, _ -> throw IOException("Simulated post-publication hook failure") },
            cleanupWarningLogger = { msg -> loggedWarnings.add(msg) }
        )

        val result = engineWithFailingHook.redactArea(
            sourceUri = Uri.fromFile(fixture),
            pageIndex = 0,
            xRatio = 0.1f,
            yRatio = 0.1f,
            widthRatio = 0.5f,
            heightRatio = 0.2f,
            outputBaseName = outputBaseName
        )

        // Case B: Nonessential hook failure does not affect document integrity; returns established successful result
        assertTrue("Redaction must succeed and return EngineResult.Success when nonessential hook fails", result is EngineResult.Success)
        val publishedFile = (result as EngineResult.Success).data
        assertTrue("Published file must exist on disk", publishedFile.exists())
        assertTrue("Published file must have non-zero size", publishedFile.length() > 0L)
        assertTrue("Warning must be recorded for post-publication hook failure", loggedWarnings.any { it.contains("Post-publication hook threw exception") })

        // Verify staging file was cleaned up
        val stagingFiles = testDocsDir.listFiles { _, name -> name.contains("stage_redact") }
        assertTrue("Staging file must be cleaned up after publication", stagingFiles.isNullOrEmpty())
    }

    // 20. Post-publication destination verification failure in textToPdf has identical semantics: preserves file, cleans stage, reports explicitly
    @Test
    fun test20_textToPdfPostPublicationVerificationFailureConsistency() = runBlocking {
        val loggedWarnings = mutableListOf<String>()
        val engineWithFailingVerifier = PdfBoxEngineImpl(
            context = testContext,
            storageManager = storageManager,
            publicationVerifier = { _, _ -> false },
            cleanupWarningLogger = { msg -> loggedWarnings.add(msg) }
        )

        val result = engineWithFailingVerifier.textToPdf("Consistent publication text", "Title", "test_text_verify_fail")

        assertTrue("textToPdf must return EngineResult.Error on post-publication verification failure", result is EngineResult.Error)
        val error = result as EngineResult.Error
        assertTrue("Error must be classified as post-publication failure", error.isPostPublicationFailure)
        assertNotNull("Committed file must be referenced in error result", error.committedFile)
        val committedFile = error.committedFile!!
        assertTrue("Committed output file must exist on disk and NOT be deleted", committedFile.exists())
        assertTrue("Committed output file must have non-zero size", committedFile.length() > 0L)
        assertTrue("Exception must be PostPublicationVerificationException", error.exception is com.offlinepdf.studio.core.engine.model.PostPublicationVerificationException)

        // Staging file must be cleaned up
        val stagingFiles = testDocsDir.listFiles { _, name -> name.startsWith(".stage_") }
        assertTrue("Staging files must be cleaned up", stagingFiles.isNullOrEmpty())
    }

    // 21. Failure in nonessential post-publication hook in textToPdf preserves committed output and returns ordinary success
    @Test
    fun test21_textToPdfPostPublicationHookFailureConsistency() = runBlocking {
        val loggedWarnings = mutableListOf<String>()
        val engineWithFailingHook = PdfBoxEngineImpl(
            context = testContext,
            storageManager = storageManager,
            afterLinkAction = { _, _ -> throw IOException("Simulated hook error in textToPdf") },
            cleanupWarningLogger = { msg -> loggedWarnings.add(msg) }
        )

        val result = engineWithFailingHook.textToPdf("Hook fail text", "Title", "test_text_hook_fail")

        assertTrue("textToPdf must return EngineResult.Success when nonessential hook fails", result is EngineResult.Success)
        val publishedFile = (result as EngineResult.Success).data
        assertTrue("Published file must exist on disk", publishedFile.exists())
        assertTrue("Published file must have non-zero size", publishedFile.length() > 0L)
        assertTrue("Warning must be recorded for post-publication hook failure", loggedWarnings.any { it.contains("Post-publication hook threw exception") })
    }

    // 22. Cancellation during post-publication verification propagates, preserves committed output and cleans staging
    @Test
    fun test22_cancellationDuringPostPublicationVerifierPreservesCommittedOutput() = runBlocking {
        val fixture = createSamplePdf(File(testDocsDir, "fixture_cancel_in_verifier.pdf"))
        val outputBaseName = "test_cancel_in_verifier"
        val loggedWarnings = mutableListOf<String>()

        val engineWithCancellingVerifier = PdfBoxEngineImpl(
            context = testContext,
            storageManager = storageManager,
            publicationVerifier = { _, _ ->
                // Simulate coroutine cancellation during verification
                throw CancellationException("Induced cancellation during post-publication verification")
            },
            cleanupWarningLogger = { msg -> loggedWarnings.add(msg) }
        )

        var caughtCancellation = false
        try {
            engineWithCancellingVerifier.redactArea(
                sourceUri = Uri.fromFile(fixture),
                pageIndex = 0,
                xRatio = 0.1f,
                yRatio = 0.1f,
                widthRatio = 0.5f,
                heightRatio = 0.2f,
                outputBaseName = outputBaseName
            )
            fail("CancellationException must propagate from redactArea()")
        } catch (e: CancellationException) {
            caughtCancellation = true
            assertEquals("Induced cancellation during post-publication verification", e.message)
        }

        assertTrue("CancellationException must propagate when cancelled during publicationVerifier", caughtCancellation)

        // Find the committed destination file created during publication
        val publishedCandidates = testDocsDir.listFiles { _, name ->
            name.startsWith(outputBaseName) && name.endsWith(".pdf")
        } ?: emptyArray()

        assertEquals("Exactly one committed destination PDF must exist", 1, publishedCandidates.size)
        val publishedFile = publishedCandidates[0]
        assertTrue("Committed output file must exist on disk and NOT be deleted", publishedFile.exists())
        assertTrue("Committed output file must have non-zero size", publishedFile.length() > 0L)
        assertTrue("Warning must record cancellation after publication commit", loggedWarnings.any { it.contains("Post-publication verification cancelled") })

        // Staging file must be cleaned up
        val stagingFiles = testDocsDir.listFiles { _, name -> name.contains("stage_redact") }
        assertTrue("Staging file must be cleaned up after cancellation", stagingFiles.isNullOrEmpty())

        // Active jobs must be cleanly decremented
        assertEquals("Active redaction jobs must be 0 after cancellation", 0, PdfBoxEngineImpl.activeRedactionJobs.get())
    }

    // 23. JVM constructor and copy binary compatibility of EngineResult.Error
    @Test
    fun test23_jvmConstructorCompatibility_EngineResultError() {
        val testEx = IOException("Test exception")
        val testMsg = "Test user message"

        // 1. Verify runtime bytecode reflects both 2-arg and 3-arg JVM constructors
        val errorClass = com.offlinepdf.studio.core.engine.model.EngineResult.Error::class.java
        val twoArgCtor = errorClass.getConstructor(Throwable::class.java, String::class.java)
        assertNotNull("JVM 2-argument constructor must exist in bytecode for binary compatibility", twoArgCtor)

        val threeArgCtor = errorClass.getConstructor(Throwable::class.java, String::class.java, File::class.java)
        assertNotNull("JVM 3-argument constructor must exist in bytecode", threeArgCtor)

        // 2. Verify generated copy() and copy$default() have the original 2-argument JVM signatures
        val copyMethod = errorClass.getMethod("copy", Throwable::class.java, String::class.java)
        assertNotNull("JVM copy(Throwable, String) must exist with original 2-arg signature", copyMethod)

        val copyDefaultMethod = errorClass.getMethod("copy\$default", errorClass, Throwable::class.java, String::class.java, Int::class.javaPrimitiveType, Any::class.java)
        assertNotNull("JVM copy\$default must exist with original parameters", copyDefaultMethod)

        // 3. Instantiate via 2-argument constructor and verify default values
        val errorFromTwoArg = twoArgCtor.newInstance(testEx, testMsg)
        assertEquals(testEx, errorFromTwoArg.exception)
        assertEquals(testMsg, errorFromTwoArg.userMessage)
        assertEquals(null, errorFromTwoArg.committedFile)
        assertFalse("isPostPublicationFailure must be false for 2-arg constructor", errorFromTwoArg.isPostPublicationFailure)

        // 4. Instantiate via 3-argument constructor and verify committed file tracking
        val dummyFile = File(testDocsDir, "dummy_committed.pdf")
        val errorFromThreeArg = threeArgCtor.newInstance(testEx, testMsg, dummyFile)
        assertEquals(testEx, errorFromThreeArg.exception)
        assertEquals(testMsg, errorFromThreeArg.userMessage)
        assertEquals(dummyFile, errorFromThreeArg.committedFile)
        assertTrue("isPostPublicationFailure must be true when committedFile is provided", errorFromThreeArg.isPostPublicationFailure)

        // 5. Verify copy() operates solely on primary properties and does not carry the body property committedFile
        val copied = errorFromThreeArg.copy(testEx, "New message")
        assertEquals("New message", copied.userMessage)
        assertEquals(null, copied.committedFile)
        assertFalse("Generated copy does not carry body property committedFile", copied.isPostPublicationFailure)
    }
}
