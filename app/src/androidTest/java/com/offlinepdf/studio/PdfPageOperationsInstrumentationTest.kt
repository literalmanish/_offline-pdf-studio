package com.offlinepdf.studio

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.util.FileStorageManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfPageOperationsInstrumentationTest {

    private lateinit var context: Context
    private lateinit var storageManager: FileStorageManager
    private lateinit var engine: PdfBoxEngineImpl
    private lateinit var testDocsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = FileStorageManager(context)
        engine = PdfBoxEngineImpl(context, storageManager)
        testDocsDir = File(context.filesDir, "test_page_ops").apply {
            if (!exists()) mkdirs()
        }
    }

    @After
    fun tearDown() {
        testDocsDir.deleteRecursively()
        File(context.cacheDir, "pdf_processing").deleteRecursively()
    }

    // 1. getPageCount
    @Test
    fun test01_getPageCount_singleAndMultiPage() = runBlocking {
        val singlePage = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "single.pdf"), 1)
        val multiPage = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "multi.pdf"), 4)

        val countSingle = engine.getPageCount(Uri.fromFile(singlePage))
        val countMulti = engine.getPageCount(Uri.fromFile(multiPage))

        assertEquals("Single page count must be 1", 1, countSingle)
        assertEquals("Multi-page count must be 4", 4, countMulti)
    }

    // 2. merge - Success
    @Test
    fun test02_merge_success() = runBlocking {
        val doc1 = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "merge1.pdf"), 2)
        val doc2 = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "merge2.pdf"), 3)

        val result = engine.merge(
            sourceUris = listOf(Uri.fromFile(doc1), Uri.fromFile(doc2)),
            outputBaseName = "test_merged"
        )

        assertTrue("Merge must succeed", result is EngineResult.Success)
        val mergedFile = (result as EngineResult.Success).data
        assertTrue("Merged file must exist", mergedFile.exists())
        assertTrue("Merged file must be non-empty", mergedFile.length() > 0)

        PDDocument.load(mergedFile).use { doc ->
            assertEquals("Merged PDF must contain sum of pages (2 + 3 = 5)", 5, doc.numberOfPages)
        }
    }

    // 3. merge - Validation failure for < 2 files
    @Test
    fun test03_merge_invalidInput() = runBlocking {
        val doc1 = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "merge_single.pdf"), 1)

        val result = engine.merge(
            sourceUris = listOf(Uri.fromFile(doc1)),
            outputBaseName = "test_merged_single"
        )

        assertTrue("Merge with 1 file must fail", result is EngineResult.Error)
        val error = result as EngineResult.Error
        assertTrue("Error message must mention at least 2 files", error.userMessage.contains("2"))
    }

    // 4. merge - Cancellation
    @Test
    fun test04_merge_cancellation() = runBlocking {
        val doc1 = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "merge_c1.pdf"), 5)
        val doc2 = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "merge_c2.pdf"), 5)

        val job = launch {
            engine.merge(
                sourceUris = listOf(Uri.fromFile(doc1), Uri.fromFile(doc2)),
                outputBaseName = "test_merged_cancel",
                onProgress = {
                    throw CancellationException("Induced merge cancellation")
                }
            )
        }
        job.cancelAndJoin()
        assertTrue("Job must be cancelled", job.isCancelled)
    }

    // 5. split - Success
    @Test
    fun test05_split_success() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "split_source.pdf"), 5)

        val result = engine.split(
            sourceUri = Uri.fromFile(doc),
            ranges = listOf(1..2, 3..5),
            outputBaseName = "test_split"
        )

        assertTrue("Split must succeed", result is EngineResult.Success)
        val parts = (result as EngineResult.Success).data
        assertEquals("Split must produce 2 parts", 2, parts.size)

        PDDocument.load(parts[0]).use { p1 ->
            assertEquals("Part 1 must have 2 pages", 2, p1.numberOfPages)
        }
        PDDocument.load(parts[1]).use { p2 ->
            assertEquals("Part 2 must have 3 pages", 3, p2.numberOfPages)
        }
    }

    // 6. reorderAndFilter - Success with rotation
    @Test
    fun test06_reorderAndFilter_withRotation() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "reorder_source.pdf"), 3)

        // Select pages 2 and 0 (0-indexed), rotating page 2 by 90 degrees
        val result = engine.reorderAndFilter(
            sourceUri = Uri.fromFile(doc),
            pageIndices = listOf(2, 0),
            rotations = mapOf(2 to 90),
            outputBaseName = "test_reordered"
        )

        assertTrue("Reorder must succeed", result is EngineResult.Success)
        val reorderedFile = (result as EngineResult.Success).data

        PDDocument.load(reorderedFile).use { docReordered ->
            assertEquals("Reordered PDF must have 2 pages", 2, docReordered.numberOfPages)
            assertEquals("First page (orig idx 2) must be rotated 90 degrees", 90, docReordered.getPage(0).rotation)
            assertEquals("Second page (orig idx 0) rotation must be 0", 0, docReordered.getPage(1).rotation)

            val p0Stream = docReordered.getPage(0).contents.use { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertTrue("First page content must be orig page 3 content", p0Stream.contains("Page 3 Content"))

            val p1Stream = docReordered.getPage(1).contents.use { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertTrue("Second page content must be orig page 1 content", p1Stream.contains("Page 1 Content"))
        }

        // Render verification: confirm content remains visible after rotation
        val pfd = android.os.ParcelFileDescriptor.open(reorderedFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(pfd)
        val rPage0 = renderer.openPage(0)
        assertTrue("Rotated 90-degree page should have landscape aspect ratio (width > height)", rPage0.width > rPage0.height)
        val bmp0 = android.graphics.Bitmap.createBitmap(rPage0.width / 4, rPage0.height / 4, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas0 = android.graphics.Canvas(bmp0)
        canvas0.drawColor(android.graphics.Color.WHITE)
        rPage0.render(bmp0, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        rPage0.close()

        var visiblePixels = 0
        for (x in 0 until bmp0.width step 4) {
            for (y in 0 until bmp0.height step 4) {
                if (bmp0.getPixel(x, y) != android.graphics.Color.WHITE) visiblePixels++
            }
        }
        bmp0.recycle()
        renderer.close()
        pfd.close()
        assertTrue("Rotated page must have visible rendered content", visiblePixels > 0)
    }

    // 7. rotatePages
    @Test
    fun test07_rotatePages() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "rotate_source.pdf"), 2)

        val result = engine.rotatePages(
            sourceUri = Uri.fromFile(doc),
            rotationDegrees = 180,
            outputBaseName = "test_rotated"
        )

        assertTrue("Rotate must succeed", result is EngineResult.Success)
        val rotatedFile = (result as EngineResult.Success).data

        PDDocument.load(rotatedFile).use { rDoc ->
            assertEquals(180, rDoc.getPage(0).rotation)
            assertEquals(180, rDoc.getPage(1).rotation)
        }
    }

    // 8. reversePages
    @Test
    fun test08_reversePages() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "reverse_source.pdf"), 3)

        val result = engine.reversePages(
            sourceUri = Uri.fromFile(doc),
            outputBaseName = "test_reversed"
        )

        assertTrue("Reverse must succeed", result is EngineResult.Success)
        val reversedFile = (result as EngineResult.Success).data

        PDDocument.load(reversedFile).use { rDoc ->
            assertEquals(3, rDoc.numberOfPages)
            val stripper = PDFTextStripper()
            stripper.startPage = 1
            stripper.endPage = 1
            val page1Text = stripper.getText(rDoc)
            assertTrue("Reversed first page must be original page 3", page1Text.contains("Page 3 Content"))
        }
    }

    // 9. duplicatePages
    @Test
    fun test09_duplicatePages() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "dup_source.pdf"), 2)

        val result = engine.duplicatePages(
            sourceUri = Uri.fromFile(doc),
            pageIndices = listOf(0),
            outputBaseName = "test_duplicated"
        )

        assertTrue("Duplicate must succeed", result is EngineResult.Success)
        val dupFile = (result as EngineResult.Success).data

        PDDocument.load(dupFile).use { dDoc ->
            assertEquals("Page 0 duplicated on 2-page doc results in 3 pages", 3, dDoc.numberOfPages)
        }
    }

    // 10. insertBlankPages
    @Test
    fun test10_insertBlankPages() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "blank_source.pdf"), 2)

        val result = engine.insertBlankPages(
            sourceUri = Uri.fromFile(doc),
            positions = listOf(1), // 1-based index: insert blank before page 1
            outputBaseName = "test_blank_inserted"
        )

        assertTrue("Insert blank must succeed", result is EngineResult.Success)
        val blankFile = (result as EngineResult.Success).data

        PDDocument.load(blankFile).use { bDoc ->
            assertEquals("Doc must have 3 pages after inserting 1 blank page", 3, bDoc.numberOfPages)
        }
    }

    // 11. cropPages
    @Test
    fun test11_cropPages() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "crop_source.pdf"), 1)

        val result = engine.cropPages(
            sourceUri = Uri.fromFile(doc),
            marginPoints = 50f,
            outputBaseName = "test_cropped"
        )

        assertTrue("Crop must succeed", result is EngineResult.Success)
        val croppedFile = (result as EngineResult.Success).data

        PDDocument.load(croppedFile).use { cDoc ->
            val page = cDoc.getPage(0)
            val cropBox = page.cropBox
            assertNotNull("CropBox must not be null", cropBox)
            assertEquals("CropBox lowerLeftX must be 50", 50f, cropBox.lowerLeftX, 1f)
            assertEquals("CropBox lowerLeftY must be 50", 50f, cropBox.lowerLeftY, 1f)
        }
    }

    // 12. nUp
    @Test
    fun test12_nUp() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "nup_source.pdf"), 3)

        val result = engine.nUp(
            sourceUri = Uri.fromFile(doc),
            pagesPerSheet = 2,
            outputBaseName = "test_nup"
        )

        assertTrue("N-Up must succeed", result is EngineResult.Success)
        val nupFile = (result as EngineResult.Success).data

        PDDocument.load(nupFile).use { nDoc ->
            // 3 source pages in 2-up -> 2 sheets
            assertEquals("3 pages in 2-up layout must create 2 sheets", 2, nDoc.numberOfPages)
        }
    }

    // 13. addBindingMargin
    @Test
    fun test13_addBindingMargin() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "binding_source.pdf"), 1)

        val result = engine.addBindingMargin(
            sourceUri = Uri.fromFile(doc),
            leftMarginPoints = 36f,
            rightMarginPoints = 18f,
            outputBaseName = "test_binding_margin"
        )

        assertTrue("Add binding margin must succeed", result is EngineResult.Success)
        val bindingFile = (result as EngineResult.Success).data

        PDDocument.load(bindingFile).use { bDoc ->
            val page = bDoc.getPage(0)
            val expectedWidth = 595.27563f + 36f + 18f
            assertEquals("Page width must include added margins", expectedWidth, page.mediaBox.width, 2f)
        }
    }

    // 14. split - Out of bounds ranges handled gracefully
    @Test
    fun test14_split_outOfBoundsRanges() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "split_oob.pdf"), 2)

        // Range 10..20 is completely outside the 2-page document
        val result = engine.split(
            sourceUri = Uri.fromFile(doc),
            ranges = listOf(10..20),
            outputBaseName = "test_split_oob"
        )

        assertTrue("Split with out of bounds range must complete", result is EngineResult.Success)
        val files = (result as EngineResult.Success).data
        assertTrue("No output files created for empty/out-of-bounds ranges", files.isEmpty())
    }

    // 15. Source preservation and temporary directory cleanup
    @Test
    fun test15_pageOps_sourcePreservationAndCleanup() = runBlocking {
        val doc = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "source_clean_check.pdf"), 2)
        val origLen = doc.length()
        val origBytes = doc.readBytes()

        val result = engine.rotatePages(
            sourceUri = Uri.fromFile(doc),
            rotationDegrees = 90,
            outputBaseName = "test_clean_rotated"
        )
        assertTrue(result is EngineResult.Success)

        // Verify source file remains completely intact
        assertEquals("Source file length must remain intact", origLen, doc.length())
        assertArrayEquals("Source bytes must be identical", origBytes, doc.readBytes())

        // Verify temp processing directory has no orphaned files
        val procDir = File(context.cacheDir, "pdf_processing")
        val orphaned = procDir.listFiles { _, name -> name.startsWith("rot_") } ?: emptyArray()
        assertEquals("Temporary processing files must be cleaned up", 0, orphaned.size)
    }
}
