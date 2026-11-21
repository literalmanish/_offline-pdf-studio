package com.offlinepdf.studio

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.util.FileStorageManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Publication Lifecycle & Resource Management Instrumentation Test Suite
 *
 * Verifies atomic hard-link publication, collision avoidance, retry cleanup,
 * and destination ownership invariants for [PdfBoxEngineImpl.textToPdf]
 * per the finalized Publication Lifecycle Test Plan (TC-01 through TC-07).
 *
 * Storage Safety:
 * All tests operate in a strictly isolated temporary directory via [IsolatedTestContext].
 * Real application documents and cache directories are never touched, scanned, or deleted.
 */
@RunWith(AndroidJUnit4::class)
class PublicationLifecycleInstrumentationTest {

    /**
     * Isolated ContextWrapper redirecting filesDir and cacheDir to an ephemeral test root.
     * Prevents any test run from polluting or deleting real user documents.
     */
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

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        // Create an isolated storage root strictly unique to this test instance
        isolatedRootDir = File(appContext.cacheDir, "test_isolated_lifecycle_${System.nanoTime()}").apply {
            mkdirs()
        }
        testContext = IsolatedTestContext(appContext, isolatedRootDir)
        testDocsDir = File(testContext.filesDir, "pdf_documents").apply { mkdirs() }
        testCacheDir = testContext.cacheDir
    }

    @After
    fun tearDown() {
        // Recursively clean ONLY the isolated test directory root; real app storage is untouched
        if (::isolatedRootDir.isInitialized) {
            isolatedRootDir.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun assertValidPdf(file: File, expectedPageCount: Int = 1) {
        assertTrue("PDF file must exist on disk: ${file.absolutePath}", file.exists())
        val length = file.length()
        assertTrue("PDF file length must be > 0 (was $length bytes)", length > 0L)

        // Verify PDF magic header bytes (%PDF-)
        FileInputStream(file).use { input ->
            val header = ByteArray(5)
            val bytesRead = input.read(header)
            assertEquals(5, bytesRead)
            assertEquals("%PDF-", String(header, Charsets.US_ASCII))
        }

        // Structural verification via PDFBox
        PDDocument.load(file).use { doc ->
            assertFalse("Document must not be encrypted", doc.isEncrypted)
            assertEquals("Page count must match expected", expectedPageCount, doc.numberOfPages)
        }
    }

    private fun countStagingFiles(): Int {
        return testDocsDir.listFiles { file -> file.name.startsWith(".stage_") }?.size ?: 0
    }

    private fun countCacheTempFiles(): Int {
        return testCacheDir.listFiles { file -> file.name.startsWith("txt2pdf_") }?.size ?: 0
    }

    // -----------------------------------------------------------------------------------------
    // TC-01: Successful Generation & PDF Structural Validity
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC01_SuccessfulGenerationAndValidity() = runBlocking {
        val engine = PdfBoxEngineImpl(testContext)
        val result = engine.textToPdf("Offline PDF Studio happy-path document.", "TC-01 Title", "test_tc01")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data

        assertValidPdf(file, expectedPageCount = 1)
        assertEquals("Zero orphan staging files must remain", 0, countStagingFiles())
        assertEquals("Zero cache temp files must remain", 0, countCacheTempFiles())
    }

    // -----------------------------------------------------------------------------------------
    // TC-02: Existing Destination Collision Protection with Injected Timestamp
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC02_ExistingDestinationCollision() = runBlocking {
        val fixedTimestamp = 1700000000000L
        val storageManager = FileStorageManager(testContext, timestampProvider = { fixedTimestamp })

        val baseDestination = storageManager.createOutputFile("test_tc02")
        baseDestination.parentFile?.mkdirs()

        // Create canary file with known non-PDF data
        val canaryData = ByteArray(512) { (it % 256).toByte() }
        baseDestination.writeBytes(canaryData)
        val originalSha = sha256(baseDestination)

        val engine = PdfBoxEngineImpl(testContext, storageManager = storageManager)
        val result = engine.textToPdf("Collision resolution content", "TC-02 Title", "test_tc02")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val generatedFile = (result as EngineResult.Success).data

        // 1. Canary file must remain byte-for-byte unchanged
        assertEquals("Canary file length must not change", 512L, baseDestination.length())
        assertEquals("Canary SHA-256 must match original exactly (no overwrite)", originalSha, sha256(baseDestination))

        // 2. Generated file must receive _1.pdf suffix
        assertTrue("Generated filename must end with _1.pdf (actual: ${generatedFile.name})", generatedFile.name.endsWith("_1.pdf"))
        assertValidPdf(generatedFile, expectedPageCount = 1)
        assertEquals("Zero staging files should remain", 0, countStagingFiles())
    }

    // -----------------------------------------------------------------------------------------
    // TC-03: Concurrent Generation Without Overwrite (Deterministic Start Barrier)
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC03_ConcurrentGeneration() = runBlocking {
        val fixedTimestamp = 1700000001000L
        val storageManager = FileStorageManager(testContext, timestampProvider = { fixedTimestamp })
        val engine = PdfBoxEngineImpl(testContext, storageManager = storageManager)

        val concurrencyCount = 4
        val startBarrier = CompletableDeferred<Unit>()
        val readyCounter = AtomicInteger(0)

        // Launch all 4 generation operations, synchronizing them at the start barrier
        val jobs = (1..concurrencyCount).map { id ->
            async(Dispatchers.IO) {
                readyCounter.incrementAndGet()
                startBarrier.await() // Await release barrier so all 4 begin together
                engine.textToPdf("Concurrent payload $id", "Concurrent $id", "test_tc03_concurrent")
            }
        }

        // Wait until all 4 coroutines are actively awaiting at the barrier
        while (readyCounter.get() < concurrencyCount) {
            delay(5)
        }

        // Release all four generation operations simultaneously
        startBarrier.complete(Unit)

        val results = jobs.awaitAll()
        val destinations = mutableListOf<File>()

        for (res in results) {
            assertTrue("Every concurrent task must succeed", res is EngineResult.Success)
            val dest = (res as EngineResult.Success).data
            destinations.add(dest)
            assertValidPdf(dest, expectedPageCount = 1)
        }

        // Verify distinct filenames were generated without clobbering
        val uniquePaths = destinations.map { it.absolutePath }.toSet()
        assertEquals("All concurrent destinations must have distinct file paths", concurrencyCount, uniquePaths.size)
        assertEquals("Zero orphan staging files must remain", 0, countStagingFiles())
    }

    // -----------------------------------------------------------------------------------------
    // TC-04: Deterministic Staging Deletion Failure & Retry Counting with Warning Verification
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC04_StagingDeletionFailureRetry() = runBlocking {
        var stageDeleteAttempts = 0
        val capturedWarnings = mutableListOf<String>()

        val spyDeleter: (File) -> Boolean = { file ->
            if (file.name.startsWith(".stage_")) {
                stageDeleteAttempts++
                false // Deterministically fail unlinking staging file
            } else {
                file.delete()
            }
        }

        val testPublisher: (Path, Path) -> Path = { source, target ->
            Files.copy(source, target)
            target
        }

        val engine = PdfBoxEngineImpl(
            context = testContext,
            fileDeleter = spyDeleter,
            publishOperation = testPublisher,
            cleanupWarningLogger = { msg -> capturedWarnings.add(msg) }
        )
        val result = engine.textToPdf("Staging deletion failure payload", "TC-04 Title", "test_tc04")

        assertTrue("Result must be EngineResult.Success even if staging deletion failed", result is EngineResult.Success)
        val outputFile = (result as EngineResult.Success).data

        assertValidPdf(outputFile, expectedPageCount = 1)

        // Step 8 attempts deletion once; failure retains stagingFile for retry in finally
        assertEquals("Must attempt staging file deletion exactly twice (step 8 + finally retry)", 2, stageDeleteAttempts)

        // Staging file remains on disk because deletion failed; must not be falsely reported as clean
        assertEquals("Staging file must remain on disk when deletion fails", 1, countStagingFiles())

        // Assert unconditionally that the captured messages contain the expected cleanup warning
        assertTrue(
            "Expected staging cleanup warning in captured warnings, but captured:\n$capturedWarnings",
            capturedWarnings.any { it.contains("Cleanup warning: Staging file could not be deleted") }
        )
    }

    // -----------------------------------------------------------------------------------------
    // TC-05: Controlled Publication Verification Failure (Never Delete Destination by Pathname)
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC05_PublicationVerificationFailure() = runBlocking {
        val deletedFiles = mutableListOf<File>()
        val spyDeleter: (File) -> Boolean = { file ->
            deletedFiles.add(file)
            file.delete()
        }

        // Inject verifier returning false while destination exists at full length
        val failingVerifier: (File, Long) -> Boolean = { _, _ -> false }

        val testPublisher: (Path, Path) -> Path = { source, target ->
            Files.copy(source, target)
            target
        }

        val engine = PdfBoxEngineImpl(
            context = testContext,
            fileDeleter = spyDeleter,
            publicationVerifier = failingVerifier,
            publishOperation = testPublisher
        )

        val result = engine.textToPdf("Verification failure text", "TC-05 Title", "test_tc05")

        assertTrue("Result must be EngineResult.Error on verification failure", result is EngineResult.Error)
        val error = (result as EngineResult.Error).exception
        assertTrue("Error must cite verification failure", error.message?.contains("Published destination PDF verification failed") == true)

        // Find the destination file created during publication in the isolated documents directory
        val publishedCandidates = testDocsDir.listFiles { file ->
            file.name.contains("test_tc05") && file.name.endsWith(".pdf")
        } ?: emptyArray()

        assertEquals("Exactly one published candidate must exist", 1, publishedCandidates.size)
        val publishedFile = publishedCandidates[0]

        // Verify the published file still physically exists on disk
        assertTrue("Published destination must NOT be deleted by pathname in finally", publishedFile.exists())
        assertTrue("Published destination must be non-empty", publishedFile.length() > 0L)

        // Verify the deleter spy confirms destination was NEVER passed for deletion
        assertFalse("Destination pathname must NEVER be passed to fileDeleter during failure cleanup",
            deletedFiles.any { it.absolutePath == publishedFile.absolutePath }
        )

        // Verify staging file and cache temporary file were passed to deleter
        assertTrue("Staging file must be passed to deleter", deletedFiles.any { it.name.startsWith(".stage_") })
        assertTrue("Cache temp file must be passed to deleter", deletedFiles.any { it.name.startsWith("txt2pdf_") })

        // Assert that staging and cache files actually no longer exist on disk
        assertEquals("Zero orphan staging files must remain on disk", 0, countStagingFiles())
        assertEquals("Zero cache temp files must remain on disk", 0, countCacheTempFiles())
        deletedFiles.forEach { file ->
            assertFalse("Cleaned temporary file must no longer exist on disk: ${file.name}", file.exists())
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-06: Deterministic Cancellation After Publication
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC06_CancellationAfterPublication() = runBlocking {
        val linkEstablished = CompletableDeferred<Unit>()
        val testAfterLinkAction: suspend (File, File) -> Unit = { _, _ ->
            linkEstablished.complete(Unit)
            delay(10000) // Suspend to allow deterministic cancellation
        }

        val deletedFiles = mutableListOf<File>()
        val spyDeleter: (File) -> Boolean = { file ->
            deletedFiles.add(file)
            file.delete()
        }

        val testPublisher: (Path, Path) -> Path = { source, target ->
            Files.copy(source, target)
            target
        }

        val engine = PdfBoxEngineImpl(
            context = testContext,
            fileDeleter = spyDeleter,
            afterLinkAction = testAfterLinkAction,
            publishOperation = testPublisher
        )

        val deferred = async(Dispatchers.IO) {
            engine.textToPdf("Cancellation test line", "TC-06 Title", "test_tc06")
        }

        // Wait until publication (Files.createLink) has completed
        linkEstablished.await()

        // Cancel the coroutine deterministically
        deferred.cancel()

        // Explicitly capture and assert CancellationException propagation from textToPdf()
        var caughtCancellation: CancellationException? = null
        try {
            deferred.await()
            fail("Expected CancellationException to be thrown from textToPdf()")
        } catch (e: CancellationException) {
            caughtCancellation = e
        }
        assertNotNull("CancellationException must be propagated from textToPdf()", caughtCancellation)

        // Locate published destination in isolated test directory
        val publishedCandidates = testDocsDir.listFiles { file ->
            file.name.contains("test_tc06") && file.name.endsWith(".pdf")
        } ?: emptyArray()

        assertEquals("Destination was linked before cancellation", 1, publishedCandidates.size)
        val publishedFile = publishedCandidates[0]

        // 1. Destination must NOT be deleted by pathname in finally
        assertTrue("Destination must still exist on disk after cancellation", publishedFile.exists())
        assertFalse("Destination must NEVER be passed to fileDeleter upon cancellation",
            deletedFiles.any { it.absolutePath == publishedFile.absolutePath }
        )

        // 2. Temporary cache file and staging file must be passed to deleter in finally
        assertTrue("Staging file must be passed to deleter on cancellation", deletedFiles.any { it.name.startsWith(".stage_") })
        assertTrue("Cache temp file must be passed to deleter on cancellation", deletedFiles.any { it.name.startsWith("txt2pdf_") })

        // Assert that staging and cache files actually no longer exist on disk
        assertEquals("Zero orphan staging files must remain on disk after cancellation", 0, countStagingFiles())
        assertEquals("Zero cache temp files must remain on disk after cancellation", 0, countCacheTempFiles())
        deletedFiles.forEach { file ->
            assertFalse("Cleaned temporary file must no longer exist on disk: ${file.name}", file.exists())
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-07: Unsupported Publication Fails Safely with Cause Preservation
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC07_UnsupportedPublication() = runBlocking {
        val unsupportedPublish: (Path, Path) -> Path = { _, _ ->
            throw UnsupportedOperationException("Atomic move unsupported on this filesystem")
        }

        val deletedFiles = mutableListOf<File>()
        val spyDeleter: (File) -> Boolean = { file ->
            deletedFiles.add(file)
            file.delete()
        }

        val engine = PdfBoxEngineImpl(
            context = testContext,
            fileDeleter = spyDeleter,
            publishOperation = unsupportedPublish
        )

        val result = engine.textToPdf("Unsupported publish text", "TC-07 Title", "test_tc07")

        assertTrue("Result must be EngineResult.Error", result is EngineResult.Error)
        val throwable = (result as EngineResult.Error).exception
        assertTrue("Throwable must be IOException", throwable is IOException)
        assertTrue("Throwable cause must be UnsupportedOperationException", throwable.cause is UnsupportedOperationException)
        assertEquals("Publication unsupported on this filesystem: Atomic move unsupported on this filesystem", throwable.message)

        // Confirm zero destination files were created in the isolated documents directory
        val createdDestinations = testDocsDir.listFiles { file ->
            file.name.contains("test_tc07")
        } ?: emptyArray()
        assertEquals("Zero destination files should be created or exposed", 0, createdDestinations.size)

        // Staging and cache files must be cleaned up
        assertEquals("Zero staging files should remain", 0, countStagingFiles())
        assertEquals("Zero cache temp files should remain", 0, countCacheTempFiles())
    }

    // -----------------------------------------------------------------------------------------
    // TC-08: Deterministic Destination Collision Concurrency in the Same Timestamp Window
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC08_ConcurrentOperationsSameBaseNameInSameTimestampWindow() = runBlocking {
        val fixedTimestamp = 1700000000000L
        val storageManager = FileStorageManager(testContext, timestampProvider = { fixedTimestamp })
        val engine = PdfBoxEngineImpl(context = testContext, storageManager = storageManager)

        val concurrentCount = 8
        val barrier = CompletableDeferred<Unit>()

        val jobs = (1..concurrentCount).map { index ->
            async(Dispatchers.IO) {
                // Synchronize start so all workers enter createOutputFile and publication concurrently
                barrier.await()
                engine.textToPdf(
                    text = "Concurrent document body payload for worker #$index",
                    title = "Concurrent Title #$index",
                    outputBaseName = "concurrent_colliding_doc"
                )
            }
        }

        // Release all concurrent operations simultaneously
        barrier.complete(Unit)

        val results = jobs.awaitAll()

        // 1. Verify all operations completed with EngineResult.Success
        val outputFiles = mutableListOf<File>()
        results.forEachIndexed { i, result ->
            assertTrue("Operation #$i must succeed: $result", result is EngineResult.Success)
            outputFiles.add((result as EngineResult.Success).data)
        }

        // 2. All successful operations must receive distinct output paths
        val uniquePaths = outputFiles.map { it.absolutePath }.toSet()
        assertEquals(
            "All $concurrentCount concurrent operations must receive distinct output paths",
            concurrentCount,
            uniquePaths.size
        )

        // 3. Existing files are never overwritten; each must exist and have valid non-zero length
        outputFiles.forEach { file ->
            assertTrue("File must exist on disk: ${file.name}", file.exists())
            assertTrue("File length must be > 0 bytes: ${file.name}", file.length() > 0L)
        }

        // 4. Published PDF files remain valid (magic header and loadable page tree)
        outputFiles.forEach { file ->
            assertValidPdf(file, expectedPageCount = 1)
        }

        // 5. Staging and cache files are cleaned
        assertEquals("Zero orphan staging files must remain on disk", 0, countStagingFiles())
        assertEquals("Zero cache temp files must remain on disk", 0, countCacheTempFiles())

        // 6. Active path reservations must be fully released
        assertEquals("All active reservations must be cleaned up", 0, storageManager.getActiveReservationsCount())
    }

    // -----------------------------------------------------------------------------------------
    // TC-09: Repeated Direct Image Exports & Destination Collision Protection
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC09_RepeatedDirectImageExports_CollisionProtection() = runBlocking {
        val fixedTimestamp = 1700000000000L
        val storageManager = FileStorageManager(testContext, timestampProvider = { fixedTimestamp })
        val engine = PdfBoxEngineImpl(context = testContext, storageManager = storageManager)

        val srcPdf = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "tc09_src.pdf"), 2)

        // First export run
        val res1 = engine.pdfToImages(
            sourceUri = Uri.fromFile(srcPdf),
            format = "PNG",
            outputBaseName = "repeated_img_export"
        )
        assertTrue("First export must succeed", res1 is EngineResult.Success)
        val files1 = (res1 as EngineResult.Success).data
        assertEquals("First export must have 2 page images", 2, files1.size)
        files1.forEach { assertTrue(it.exists() && it.length() > 0) }
        val sha1_page1 = sha256(files1[0])

        // Verify active reservations returned to 0 after first export
        assertEquals("Reservations must return to 0 after first export", 0, storageManager.getActiveReservationsCount())

        // Second export run with IDENTICAL base name and identical timestamp window
        val res2 = engine.pdfToImages(
            sourceUri = Uri.fromFile(srcPdf),
            format = "PNG",
            outputBaseName = "repeated_img_export"
        )
        assertTrue("Second export must succeed", res2 is EngineResult.Success)
        val files2 = (res2 as EngineResult.Success).data
        assertEquals("Second export must have 2 page images", 2, files2.size)
        files2.forEach { assertTrue(it.exists() && it.length() > 0) }

        // Confirm previously completed output files remain unchanged (not overwritten)
        assertEquals("Page 1 of first export must retain identical SHA-256", sha1_page1, sha256(files1[0]))

        // All 4 files must have distinct paths
        val allPaths = (files1 + files2).map { it.absolutePath }.toSet()
        assertEquals("All 4 image files across both runs must have distinct paths", 4, allPaths.size)

        // Verify active reservations returned to 0 after second export
        assertEquals("Reservations must return to 0 after second export", 0, storageManager.getActiveReservationsCount())
    }

    // -----------------------------------------------------------------------------------------
    // TC-10: Concurrent extractImages and extractText Using Identical Base Names
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC10_ConcurrentExtractImagesAndText_IdenticalBaseName() = runBlocking {
        val fixedTimestamp = 1700000000000L
        val storageManager = FileStorageManager(testContext, timestampProvider = { fixedTimestamp })
        val engine = PdfBoxEngineImpl(context = testContext, storageManager = storageManager)

        val srcPdf = PdfEngineTestFixtures.createPdfWithEmbeddedImage(File(testDocsDir, "tc10_src.pdf"), 200, 200)

        val barrier = CompletableDeferred<Unit>()

        val imgJob = async(Dispatchers.IO) {
            barrier.await()
            engine.extractImages(Uri.fromFile(srcPdf), "shared_export_name")
        }

        val txtJob = async(Dispatchers.IO) {
            barrier.await()
            engine.extractText(Uri.fromFile(srcPdf), "shared_export_name")
        }

        barrier.complete(Unit)

        val imgRes = imgJob.await()
        val txtRes = txtJob.await()

        assertTrue("Extract images must succeed", imgRes is EngineResult.Success)
        assertTrue("Extract text must succeed", txtRes is EngineResult.Success)

        val imgFiles = (imgRes as EngineResult.Success).data
        val txtFile = (txtRes as EngineResult.Success).data

        assertTrue("Image file must exist", imgFiles.isNotEmpty() && imgFiles[0].exists())
        assertTrue("Text file must exist", txtFile.exists())

        // Verify content integrity
        assertTrue("Text file must have .txt extension", txtFile.name.endsWith(".txt"))
        assertTrue("Image file must have .png extension", imgFiles[0].name.endsWith(".png"))

        val bmp = BitmapFactory.decodeFile(imgFiles[0].absolutePath)
        assertNotNull("Extracted image must be a valid bitmap", bmp)
        bmp?.recycle()

        // Active reservations must return to 0
        assertEquals("Reservations must return to 0", 0, storageManager.getActiveReservationsCount())
    }

    // -----------------------------------------------------------------------------------------
    // TC-11: Failure During Direct Output Writing Cleans Up Partial Files & Releases Reservation
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC11_FailureDuringDirectOutput_CleansUpAndReleasesReservation() = runBlocking {
        val storageManager = FileStorageManager(testContext)
        val engine = PdfBoxEngineImpl(context = testContext, storageManager = storageManager)

        // Pass invalid non-PDF file as source
        val corruptSource = File(testDocsDir, "corrupt_input.pdf").apply { writeText("NOT A VALID PDF") }

        val result = engine.rotatePages(
            sourceUri = Uri.fromFile(corruptSource),
            rotationDegrees = 90,
            outputBaseName = "failed_direct_op"
        )

        assertTrue("Result must be EngineResult.Error", result is EngineResult.Error)

        // Verify zero partial destination files remain in outputDir
        val destinations = testDocsDir.listFiles { file -> file.name.contains("failed_direct_op") } ?: emptyArray()
        assertEquals("Zero partial output files must remain on disk", 0, destinations.size)

        // Active reservations must return to 0
        assertEquals("Active reservation must be released on failure", 0, storageManager.getActiveReservationsCount())
    }

    // -----------------------------------------------------------------------------------------
    // TC-12: Cancellation During Direct Export Cleans Up Partial Files & Releases Reservation
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC12_CancellationDuringDirectExport_CleansUpAndReleasesReservation() = runBlocking {
        val storageManager = FileStorageManager(testContext)
        val engine = PdfBoxEngineImpl(context = testContext, storageManager = storageManager)

        val srcPdf = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "tc12_src.pdf"), 4)

        var cancelled = false
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            engine.pdfToImages(
                sourceUri = Uri.fromFile(srcPdf),
                format = "PNG",
                outputBaseName = "cancelled_export"
            ) { progress ->
                // Cancel after first page has been rendered
                if (progress.current >= 1) {
                    throw CancellationException("Simulated user cancellation mid-export")
                }
            }
        }

        try {
            job.await()
            fail("Operation should have thrown CancellationException")
        } catch (e: CancellationException) {
            cancelled = true
        }

        assertTrue("CancellationException must propagate", cancelled)

        // Verify zero partial export files left behind in context.filesDir
        val orphaned = testContext.filesDir.listFiles { file -> file.name.contains("cancelled_export") } ?: emptyArray()
        assertEquals("Zero orphaned partial image files must remain on disk", 0, orphaned.size)

        // Active reservations must return to 0
        assertEquals("Active reservation must be released upon cancellation", 0, storageManager.getActiveReservationsCount())
    }

    // -----------------------------------------------------------------------------------------
    // TC-13: Concurrent Direct PDF Operations with Same Base Name in Same Timestamp Window
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC13_ConcurrentDirectPdfOperations_SameBaseNameInSameTimestampWindow() = runBlocking {
        val fixedTimestamp = 1700000000000L
        val storageManager = FileStorageManager(testContext, timestampProvider = { fixedTimestamp })
        val engine = PdfBoxEngineImpl(context = testContext, storageManager = storageManager)

        val srcPdf = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "tc13_src.pdf"), 2)

        val concurrentCount = 6
        val barrier = CompletableDeferred<Unit>()

        val jobs = (1..concurrentCount).map { index ->
            async(Dispatchers.IO) {
                barrier.await()
                engine.rotatePages(
                    sourceUri = Uri.fromFile(srcPdf),
                    rotationDegrees = 90,
                    outputBaseName = "concurrent_direct_rotate"
                )
            }
        }

        barrier.complete(Unit)

        val results = jobs.awaitAll()

        val outputFiles = mutableListOf<File>()
        results.forEachIndexed { i, result ->
            assertTrue("Direct operation #$i must succeed: $result", result is EngineResult.Success)
            outputFiles.add((result as EngineResult.Success).data)
        }

        // All 6 must receive distinct output paths
        val uniquePaths = outputFiles.map { it.absolutePath }.toSet()
        assertEquals(
            "All $concurrentCount concurrent direct operations must receive distinct output paths",
            concurrentCount,
            uniquePaths.size
        )

        outputFiles.forEach { file ->
            assertTrue("File must exist on disk: ${file.name}", file.exists())
            assertTrue("File length must be > 0 bytes: ${file.name}", file.length() > 0L)
            assertValidPdf(file, expectedPageCount = 2)
        }

        // Active path reservations must return to zero
        assertEquals("All active reservations must be cleaned up", 0, storageManager.getActiveReservationsCount())
    }

    // -----------------------------------------------------------------------------------------
    // TC-14: Publication No-Overwrite Guarantee & FileAlreadyExistsException Retry
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC14_PublicationNoOverwriteGuarantee_MarkerPreservationAndRetry() = runBlocking {
        val fixedTimestamp = 1700000000000L
        val storageManager = FileStorageManager(testContext, timestampProvider = { fixedTimestamp })
        val engine = PdfBoxEngineImpl(context = testContext, storageManager = storageManager)

        // 1. Pre-create the intended destination with identifiable marker bytes
        val intendedDestination = File(testDocsDir, "test_tc14_candidate.pdf")
        val markerBytes = "PRE-EXISTING-CANARY-MARKER-BYTES-123456789".toByteArray(Charsets.UTF_8)
        intendedDestination.writeBytes(markerBytes)
        val originalMarkerSha = sha256(intendedDestination)

        // Pre-stage a valid PDF
        val stagedFile = File(testDocsDir, ".stage_tc14_${System.nanoTime()}.pdf")
        PdfEngineTestFixtures.createMultiPagePdf(stagedFile, 1)
        val stageLength = stagedFile.length()
        assertTrue("Stage file must exist before publication", stagedFile.exists())
        assertTrue("Stage file must have non-zero length", stageLength > 0L)

        // 2. Trigger staged PDF publication with the same candidate name
        val publishedFile = engine.publishStagedPdf(
            staged = stagedFile,
            baseDestination = intendedDestination,
            expectedLength = stageLength
        )

        // 3. Confirm publication retries using a collision suffix
        assertNotNull("Published file must not be null", publishedFile)
        assertTrue("Published file must exist on disk", publishedFile.exists())
        assertFalse(
            "Published file path must be distinct from pre-existing destination",
            publishedFile.absolutePath == intendedDestination.absolutePath
        )
        assertTrue(
            "Published filename must end with collision suffix _1.pdf (actual: ${publishedFile.name})",
            publishedFile.name.endsWith("_1.pdf")
        )

        // 4. Confirm the original destination marker bytes remain unchanged
        assertTrue("Original destination must still exist", intendedDestination.exists())
        assertEquals(
            "Original destination byte length must remain exactly unchanged",
            markerBytes.size.toLong(),
            intendedDestination.length()
        )
        assertEquals(
            "Original destination SHA-256 must remain identical (no overwrite)",
            originalMarkerSha,
            sha256(intendedDestination)
        )
        assertTrue(
            "Original destination marker content must match byte-for-byte",
            intendedDestination.readBytes().contentEquals(markerBytes)
        )

        // 5. Confirm the new destination is a valid PDF
        assertValidPdf(publishedFile, expectedPageCount = 1)

        // 6. Confirm staging cleanup
        assertFalse("Staging file must be cleaned up / moved", stagedFile.exists())
        assertEquals("Zero orphan staging files must remain on disk", 0, countStagingFiles())

        // 7. Confirm reservation count returns to zero
        assertEquals(
            "Reservation count must return to zero after publication",
            0,
            storageManager.getActiveReservationsCount()
        )
    }
}
