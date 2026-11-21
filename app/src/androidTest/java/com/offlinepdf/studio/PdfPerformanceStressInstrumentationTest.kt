package com.offlinepdf.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.core.engine.PdfEngine
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.CompressionLevel
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.renderer.PdfRendererHelper
import com.offlinepdf.studio.core.util.FileStorageManager
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfPerformanceStressInstrumentationTest {

    private lateinit var context: Context
    private lateinit var engine: PdfEngine
    private lateinit var storageManager: FileStorageManager
    private val createdTestFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = FileStorageManager(context)
        engine = PdfBoxEngineImpl(context, storageManager)
    }

    @After
    fun tearDown() {
        createdTestFiles.forEach { file ->
            if (file.exists()) {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
        }
        createdTestFiles.clear()
        storageManager.clearTempFiles()
    }

    private fun track(file: File): File {
        createdTestFiles.add(file)
        return file
    }

    // =========================================================================
    // 1. WORKLOAD MATRIX STRESS TESTS (Small, Medium, Large, Very Large, Image, Text, Mixed, High-Entropy)
    // =========================================================================

    @Test
    fun testWorkload_Small_1Page_RenderingAndMetadata() = runBlocking {
        val testFile = track(File(context.cacheDir, "workload_small_1p.pdf"))
        PdfEngineTestFixtures.createMultiPagePdf(testFile, 1)

        val start = SystemClock.elapsedRealtime()
        val count = engine.getPageCount(Uri.fromFile(testFile))
        assertEquals(1, count)

        PdfRendererHelper.fromUri(context, Uri.fromFile(testFile)).use { helper ->
            assertEquals(1, helper.pageCount)
            val bmp = helper.renderPage(0, destWidth = 720)
            assertNotNull(bmp)
            bmp?.recycle()
        }
        val duration = SystemClock.elapsedRealtime() - start
        assertTrue("Small 1-page operation must finish under 3000ms (took ${duration}ms)", duration < 3000)
    }

    @Test
    fun testWorkload_Medium_10Pages_MergeAndSplit() = runBlocking {
        val fileA = track(File(context.cacheDir, "workload_med_a.pdf"))
        val fileB = track(File(context.cacheDir, "workload_med_b.pdf"))
        PdfEngineTestFixtures.createMultiPagePdf(fileA, 5)
        PdfEngineTestFixtures.createMultiPagePdf(fileB, 5)

        val start = SystemClock.elapsedRealtime()
        val mergeRes = engine.merge(listOf(Uri.fromFile(fileA), Uri.fromFile(fileB)))
        assertTrue("Merge must succeed", mergeRes is EngineResult.Success)
        val mergedFile = track((mergeRes as EngineResult.Success).data)
        assertTrue(mergedFile.exists() && mergedFile.length() > 0)
        assertEquals(10, engine.getPageCount(Uri.fromFile(mergedFile)))

        // Split into two 5-page chunks
        val splitRes = engine.split(Uri.fromFile(mergedFile), listOf(1..5, 6..10))
        assertTrue("Split must succeed", splitRes is EngineResult.Success)
        val splitFiles = (splitRes as EngineResult.Success).data
        assertEquals(2, splitFiles.size)
        splitFiles.forEach { f ->
            track(f)
            assertTrue(f.exists() && f.length() > 0)
            assertEquals(5, engine.getPageCount(Uri.fromFile(f)))
        }

        val duration = SystemClock.elapsedRealtime() - start
        assertTrue("Medium 10-page merge & split must complete under 10000ms (took ${duration}ms)", duration < 10000)
    }

    @Test
    fun testWorkload_Large_50Pages_ProcessingAndVerification() = runBlocking {
        val largeFile = track(File(context.cacheDir, "workload_large_50p.pdf"))
        PdfEngineTestFixtures.createMultiPagePdf(largeFile, 50)
        assertEquals(50, engine.getPageCount(Uri.fromFile(largeFile)))

        val start = SystemClock.elapsedRealtime()
        // Rotate all 50 pages
        val rotRes = engine.rotatePages(Uri.fromFile(largeFile), rotationDegrees = 90)
        assertTrue("50-page rotation must succeed", rotRes is EngineResult.Success)
        val rotatedFile = track((rotRes as EngineResult.Success).data)
        assertTrue(rotatedFile.exists() && rotatedFile.length() > 0)
        assertEquals(50, engine.getPageCount(Uri.fromFile(rotatedFile)))

        val duration = SystemClock.elapsedRealtime() - start
        assertTrue("50-page rotation must complete under 15000ms (took ${duration}ms)", duration < 15000)
    }

    @Test
    fun testWorkload_VeryLarge_100Pages_PageCountAndReorder() = runBlocking {
        val veryLargeFile = track(File(context.cacheDir, "workload_vlarge_100p.pdf"))
        PdfEngineTestFixtures.createMultiPagePdf(veryLargeFile, 100)
        assertEquals(100, engine.getPageCount(Uri.fromFile(veryLargeFile)))

        val start = SystemClock.elapsedRealtime()
        // Reverse first 20 pages
        val pageIndices = (20 downTo 1).map { it - 1 }
        val reorderRes = engine.reorderAndFilter(Uri.fromFile(veryLargeFile), pageIndices)
        assertTrue("Reorder must succeed", reorderRes is EngineResult.Success)
        val reorderedFile = track((reorderRes as EngineResult.Success).data)
        assertTrue(reorderedFile.exists() && reorderedFile.length() > 0)
        assertEquals(20, engine.getPageCount(Uri.fromFile(reorderedFile)))

        val duration = SystemClock.elapsedRealtime() - start
        assertTrue("100-page partial reorder must complete under 20000ms (took ${duration}ms)", duration < 20000)
    }

    @Test
    fun testWorkload_ImageHeavy_CompressionAndDownsampling() = runBlocking {
        val imgFile = track(File(context.cacheDir, "workload_image_heavy.pdf"))
        PdfEngineTestFixtures.createLargeImagePdf(imgFile, width = 1600, height = 1600, text = "HighRes Scanned")
        val origLength = imgFile.length()

        val start = SystemClock.elapsedRealtime()
        val compRes = engine.compress(Uri.fromFile(imgFile), CompressionLevel.HIGH)
        assertTrue("Compression must succeed", compRes is EngineResult.Success)
        val compFile = track((compRes as EngineResult.Success).data)
        assertTrue(compFile.exists() && compFile.length() > 0)
        assertTrue("Compressed image-heavy file must be smaller than original", compFile.length() < origLength)

        val duration = SystemClock.elapsedRealtime() - start
        assertTrue("High-res image compression must finish under 15000ms (took ${duration}ms)", duration < 15000)
    }

    @Test
    fun testWorkload_TextHeavy_DenseExtraction() = runBlocking {
        val textFile = track(File(context.cacheDir, "workload_dense_text.pdf"))
        PdfEngineTestFixtures.createDenseTextPdf(textFile, pageCount = 10, linesPerPage = 30)

        val start = SystemClock.elapsedRealtime()
        val extractRes = engine.extractText(Uri.fromFile(textFile))
        assertTrue("Text extraction must succeed", extractRes is EngineResult.Success)
        val extractedFile = track((extractRes as EngineResult.Success).data)
        assertTrue(extractedFile.exists() && extractedFile.length() > 0)
        val text = extractedFile.readText()
        assertTrue(text.contains("Dense Text Document - Page 1"))
        assertTrue(text.contains("Paragraph line 30"))

        val duration = SystemClock.elapsedRealtime() - start
        assertTrue("Dense text extraction must finish under 30000ms (took ${duration}ms)", duration < 30000)
    }

    @Test
    fun testWorkload_Mixed_TextAndVectorAndImage() = runBlocking {
        val mixedFile = track(File(context.cacheDir, "workload_mixed.pdf"))
        PdfEngineTestFixtures.createMixedPdf(mixedFile, pageCount = 5)

        val start = SystemClock.elapsedRealtime()
        val grayRes = engine.convertToGrayscale(Uri.fromFile(mixedFile))
        assertTrue("Grayscale conversion must succeed", grayRes is EngineResult.Success)
        val grayFile = track((grayRes as EngineResult.Success).data)
        assertTrue(grayFile.exists() && grayFile.length() > 0)
        assertEquals(5, engine.getPageCount(Uri.fromFile(grayFile)))

        val duration = SystemClock.elapsedRealtime() - start
        assertTrue("Mixed document grayscale conversion must finish under 8000ms (took ${duration}ms)", duration < 8000)
    }

    @Test
    fun testWorkload_HighEntropy_NoiseImageHandling() = runBlocking {
        val noiseFile = track(File(context.cacheDir, "workload_noise.pdf"))
        PdfEngineTestFixtures.createHighEntropyPdf(noiseFile, pageCount = 1, width = 300, height = 300)

        val start = SystemClock.elapsedRealtime()
        val imgRes = engine.extractImages(Uri.fromFile(noiseFile))
        assertTrue("Image extraction must succeed", imgRes is EngineResult.Success)
        val images = (imgRes as EngineResult.Success).data
        assertTrue("Must extract high-entropy image successfully", images.isNotEmpty())
        images.forEach { track(it) }

        val duration = SystemClock.elapsedRealtime() - start
        assertTrue("High-entropy image extraction must complete under 5000ms (took ${duration}ms)", duration < 5000)
    }

    // =========================================================================
    // 2. MAJOR OPERATIONS BENCHMARK & CORRECTNESS
    // =========================================================================

    @Test
    fun testMajorOperations_BenchmarkSuite() = runBlocking {
        val baseFile = track(File(context.cacheDir, "benchmark_base.pdf"))
        PdfEngineTestFixtures.createMultiPagePdf(baseFile, 3)
        val baseUri = Uri.fromFile(baseFile)

        // 1. Text-to-PDF
        val t2pStart = SystemClock.elapsedRealtime()
        val t2pRes = engine.textToPdf("Benchmark paragraph content\nSecond line for conversion test.")
        assertTrue("Text-to-PDF must succeed", t2pRes is EngineResult.Success)
        track((t2pRes as EngineResult.Success).data)
        val t2pTime = SystemClock.elapsedRealtime() - t2pStart
        assertTrue("Text-to-PDF latency under 3000ms (took ${t2pTime}ms)", t2pTime < 3000)

        // 2. Images-to-PDF
        val imgFile = track(File(context.cacheDir, "bench_img.png"))
        PdfEngineTestFixtures.createTestImageFile(imgFile, 150, 150, Color.RED)
        val i2pStart = SystemClock.elapsedRealtime()
        val i2pRes = engine.imagesToPdf(listOf(Uri.fromFile(imgFile)))
        assertTrue("Images-to-PDF must succeed", i2pRes is EngineResult.Success)
        track((i2pRes as EngineResult.Success).data)
        val i2pTime = SystemClock.elapsedRealtime() - i2pStart
        assertTrue("Images-to-PDF latency under 3000ms (took ${i2pTime}ms)", i2pTime < 3000)

        // 3. PDF-to-Images
        val p2iStart = SystemClock.elapsedRealtime()
        val p2iRes = engine.pdfToImages(baseUri)
        assertTrue("PDF-to-images must succeed", p2iRes is EngineResult.Success)
        val p2iList = (p2iRes as EngineResult.Success).data
        assertTrue(p2iList.isNotEmpty())
        p2iList.forEach { track(it) }
        val p2iTime = SystemClock.elapsedRealtime() - p2iStart
        assertTrue("PDF-to-images latency under 4000ms (took ${p2iTime}ms)", p2iTime < 4000)

        // 4. Invert colors
        val invStart = SystemClock.elapsedRealtime()
        val invRes = engine.invertColors(baseUri)
        assertTrue("Invert colors must succeed", invRes is EngineResult.Success)
        track((invRes as EngineResult.Success).data)
        val invTime = SystemClock.elapsedRealtime() - invStart
        assertTrue("Invert colors latency under 5000ms (took ${invTime}ms)", invTime < 5000)

        // 5. Encryption & Decryption
        val encStart = SystemClock.elapsedRealtime()
        val encRes = engine.protectPdf(baseUri, "TestPass123")
        assertTrue("Encryption must succeed", encRes is EngineResult.Success)
        val encFile = track((encRes as EngineResult.Success).data)
        val encTime = SystemClock.elapsedRealtime() - encStart
        assertTrue("Encryption latency under 3000ms (took ${encTime}ms)", encTime < 3000)

        val decStart = SystemClock.elapsedRealtime()
        val decRes = engine.unlockPdf(Uri.fromFile(encFile), "TestPass123")
        assertTrue("Decryption must succeed", decRes is EngineResult.Success)
        track((decRes as EngineResult.Success).data)
        val decTime = SystemClock.elapsedRealtime() - decStart
        assertTrue("Decryption latency under 3000ms (took ${decTime}ms)", decTime < 3000)

        // 6. Watermark
        val wmStart = SystemClock.elapsedRealtime()
        val wmRes = engine.addWatermark(baseUri, "BENCHMARK")
        assertTrue("Watermark must succeed", wmRes is EngineResult.Success)
        track((wmRes as EngineResult.Success).data)
        val wmTime = SystemClock.elapsedRealtime() - wmStart
        assertTrue("Watermark latency under 3000ms (took ${wmTime}ms)", wmTime < 3000)
    }

    // =========================================================================
    // 3. MEMORY, RESOURCE PROFILING & CONSECUTIVE OPERATIONS
    // =========================================================================

    @Test
    fun testResourceLifecycle_RepeatedOperations_NoMemoryOrFileLeak() = runBlocking {
        val testFile = track(File(context.cacheDir, "lifecycle_source.pdf"))
        PdfEngineTestFixtures.createMultiPagePdf(testFile, 5)
        val sourceUri = Uri.fromFile(testFile)

        val initialReservations = storageManager.getActiveReservationsCount()
        assertEquals("Initial active reservations must be 0", 0, initialReservations)

        // Perform 10 consecutive operations
        for (i in 1..10) {
            val rotRes = engine.rotatePages(sourceUri, rotationDegrees = 90)
            assertTrue("Consecutive rotation #$i must succeed", rotRes is EngineResult.Success)
            val rotFile = track((rotRes as EngineResult.Success).data)
            assertTrue(rotFile.exists() && rotFile.length() > 0)

            // Render thumbnail and page
            PdfRendererHelper.fromUri(context, Uri.fromFile(rotFile)).use { helper ->
                val thumb = helper.renderThumbnail(0, maxDimension = 150)
                assertNotNull(thumb)
                thumb?.recycle()
            }
        }

        // Verify active reservations returned to 0
        val finalReservations = storageManager.getActiveReservationsCount()
        assertEquals("Active reservations must return to 0 after repeated operations", 0, finalReservations)

        // Verify scratch directory has no leaked files
        val scratchDir = File(context.cacheDir, "pdf_scratch")
        if (scratchDir.exists()) {
            val scratchFiles = scratchDir.listFiles() ?: emptyArray()
            assertEquals("Scratch directory must be empty after operations complete", 0, scratchFiles.size)
        }
    }

    // =========================================================================
    // 4. LOW-RESOURCE, CONCURRENCY & CANCELLATION
    // =========================================================================

    @Test
    fun testConcurrency_MultipleOperations_NoCollisionOrStall() = runBlocking {
        val file1 = track(File(context.cacheDir, "conc_1.pdf"))
        val file2 = track(File(context.cacheDir, "conc_2.pdf"))
        PdfEngineTestFixtures.createMultiPagePdf(file1, 4)
        PdfEngineTestFixtures.createMultiPagePdf(file2, 4)

        val job1 = launch {
            val res1 = engine.rotatePages(Uri.fromFile(file1), rotationDegrees = 180)
            assertTrue(res1 is EngineResult.Success)
            track((res1 as EngineResult.Success).data)
        }

        val job2 = launch {
            val res2 = engine.rotatePages(Uri.fromFile(file2), rotationDegrees = 180)
            assertTrue(res2 is EngineResult.Success)
            track((res2 as EngineResult.Success).data)
        }

        job1.join()
        job2.join()

        assertEquals("No reservations should remain active", 0, storageManager.getActiveReservationsCount())
    }

    @Test
    fun testCancellation_MidOperation_CleansStagingAndReservation() = runBlocking {
        val largeFile = track(File(context.cacheDir, "cancel_test.pdf"))
        PdfEngineTestFixtures.createMultiPagePdf(largeFile, 30)

        var started = false
        val job = launch {
            try {
                engine.rotatePages(
                    Uri.fromFile(largeFile),
                    rotationDegrees = 90,
                    onProgress = { progress ->
                        started = true
                    }
                )
            } catch (_: Exception) {
                // Cancellation caught
            }
        }

        // Wait briefly until operation starts then cancel immediately
        while (!started) {
            kotlinx.coroutines.delay(10)
        }
        job.cancelAndJoin()

        // Assert no reservations leaked
        assertEquals("Cancelled operation must release all active reservations", 0, storageManager.getActiveReservationsCount())
    }

    // =========================================================================
    // 5. DISPLAY & RENDER METRICS COMPATIBILITY
    // =========================================================================

    @Test
    fun testRenderer_VariousDestWidthsAndMetrics() = runBlocking {
        val testFile = track(File(context.cacheDir, "render_metrics.pdf"))
        PdfEngineTestFixtures.createMultiPagePdf(testFile, 1)

        PdfRendererHelper.fromUri(context, Uri.fromFile(testFile)).use { helper ->
            val widths = listOf(320, 720, 1080, 1440)
            for (w in widths) {
                val bmp = helper.renderPage(0, destWidth = w)
                assertNotNull("Rendering at destWidth=$w must succeed", bmp)
                assertEquals(w, bmp!!.width)
                assertTrue("Height must be positive", bmp.height > 0)
                bmp.recycle()
            }
        }
    }
}
