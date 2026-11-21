package com.offlinepdf.studio

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.engine.model.PageNumberPosition
import com.offlinepdf.studio.core.engine.model.StandardStamp
import com.offlinepdf.studio.core.engine.model.WatermarkPosition
import com.offlinepdf.studio.core.util.FileStorageManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfContentDecorationInstrumentationTest {

    private lateinit var context: Context
    private lateinit var storageManager: FileStorageManager
    private lateinit var engine: PdfBoxEngineImpl
    private lateinit var testDocsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = FileStorageManager(context)
        engine = PdfBoxEngineImpl(context, storageManager)
        testDocsDir = File(context.filesDir, "test_decoration").apply {
            if (!exists()) mkdirs()
        }
    }

    @After
    fun tearDown() {
        testDocsDir.deleteRecursively()
        File(context.cacheDir, "pdf_processing").deleteRecursively()
    }

    // 1. addWatermark - Center Diagonal: Full Matrix, ExtGState Alpha, and Render Verification
    @Test
    fun test01_addWatermark_centerDiagonal() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "wm_src.pdf"), 2)
        val watermarkText = "COMPANY_CONFIDENTIAL"
        val expectedOpacity = 0.3f

        val result = engine.addWatermark(
            sourceUri = Uri.fromFile(src),
            text = watermarkText,
            position = WatermarkPosition.CENTER_DIAGONAL,
            opacity = expectedOpacity,
            outputBaseName = "test_watermarked"
        )

        assertTrue("Add watermark must succeed", result is EngineResult.Success)
        val wmFile = (result as EngineResult.Success).data
        assertTrue("Watermarked file must exist", wmFile.exists())

        PDDocument.load(wmFile).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val page0 = doc.getPage(0)

            // A. Content Stream: verify actual text token and 45-degree rotation matrix (cos 45° ≈ 0.7071)
            val page0Stream = page0.contents.use { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertTrue("Page content stream must contain watermark string", page0Stream.contains(watermarkText))
            assertTrue("Content stream must include 45-degree rotation matrix (0.7071)", page0Stream.contains("0.7071"))

            // B. Graphics State: verify extended graphics state with 0.3 alpha constant
            val resources = page0.resources
            assertNotNull("Page resources must not be null", resources)
            var foundAlphaExtGState = false
            for (name in resources.extGStateNames) {
                val gs = resources.getExtGState(name)
                if (gs != null && gs.nonStrokingAlphaConstant != null) {
                    if (kotlin.math.abs(gs.nonStrokingAlphaConstant - expectedOpacity) < 0.05f) {
                        foundAlphaExtGState = true
                        break
                    }
                }
            }
            assertTrue("Page resources must contain ExtGState with opacity 0.3f", foundAlphaExtGState)
        }

        // C. Render Verification: render page with PdfRenderer and confirm visible watermark pixels
        val pfd = android.os.ParcelFileDescriptor.open(wmFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(pfd)
        val page = renderer.openPage(0)
        val bmp = android.graphics.Bitmap.createBitmap(page.width / 2, page.height / 2, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        renderer.close()
        pfd.close()

        // Check for non-white pixels in the center region where 45-deg watermark is drawn
        var nonWhitePixels = 0
        val midX = bmp.width / 2
        val midY = bmp.height / 2
        for (dx in -50..50) {
            for (dy in -50..50) {
                val pixel = bmp.getPixel((midX + dx).coerceIn(0, bmp.width - 1), (midY + dy).coerceIn(0, bmp.height - 1))
                if (pixel != android.graphics.Color.WHITE) {
                    nonWhitePixels++
                }
            }
        }
        bmp.recycle()
        assertTrue("Watermark must render visible non-white pixels in the center area", nonWhitePixels > 10)
    }

    // 2. addWatermark - Top and Bottom Positions
    @Test
    fun test02_addWatermark_horizontalPositions() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "wm_pos_src.pdf"), 1)
        val watermarkText = "DRAFT_SAMPLE"

        val result = engine.addWatermark(
            sourceUri = Uri.fromFile(src),
            text = watermarkText,
            position = WatermarkPosition.TOP,
            opacity = 0.5f,
            outputBaseName = "test_watermarked_top"
        )

        assertTrue(result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        PDDocument.load(file).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue(text.contains(watermarkText))
        }
    }

    // 3. addPageNumbers
    @Test
    fun test03_addPageNumbers() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "num_src.pdf"), 3)

        val result = engine.addPageNumbers(
            sourceUri = Uri.fromFile(src),
            position = PageNumberPosition.BOTTOM_CENTER,
            formatPattern = "Page {n} of {total}",
            startPage = 1,
            outputBaseName = "test_numbered"
        )

        assertTrue("Add page numbers must succeed", result is EngineResult.Success)
        val numberedFile = (result as EngineResult.Success).data

        PDDocument.load(numberedFile).use { doc ->
            assertEquals(3, doc.numberOfPages)
            val stripper = PDFTextStripper()
            stripper.startPage = 1
            stripper.endPage = 1
            val p1Text = stripper.getText(doc)
            assertTrue("Page 1 must contain formatted page number", p1Text.contains("Page 1 of 3"))

            stripper.startPage = 3
            stripper.endPage = 3
            val p3Text = stripper.getText(doc)
            assertTrue("Page 3 must contain formatted page number", p3Text.contains("Page 3 of 3"))
        }
    }

    // 4. addHeaderFooter
    @Test
    fun test04_addHeaderFooter() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "hf_src.pdf"), 2)
        val header = "DEPARTMENT_CLASSIFIED_HEADER"
        val footer = "STRICTLY_INTERNAL_FOOTER"

        val result = engine.addHeaderFooter(
            sourceUri = Uri.fromFile(src),
            headerText = header,
            footerText = footer,
            outputBaseName = "test_header_footer"
        )

        assertTrue("Add header and footer must succeed", result is EngineResult.Success)
        val hfFile = (result as EngineResult.Success).data

        PDDocument.load(hfFile).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue("Text must contain header", text.contains(header))
            assertTrue("Text must contain footer", text.contains(footer))
        }
    }

    // 5. addStamp - Standard and Custom
    @Test
    fun test05_addStamp() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "stamp_src.pdf"), 1)
        val customStamp = "OFFICIALLY_VERIFIED"

        val result = engine.addStamp(
            sourceUri = Uri.fromFile(src),
            stamp = StandardStamp.APPROVED,
            customText = customStamp,
            outputBaseName = "test_stamped"
        )

        assertTrue("Add stamp must succeed", result is EngineResult.Success)
        val stampedFile = (result as EngineResult.Success).data

        PDDocument.load(stampedFile).use { doc ->
            val text = PDFTextStripper().getText(doc)
            assertTrue("Text must contain stamp text", text.contains(customStamp))
        }
    }

    // 6. signPdf - Embedding Signature Image
    @Test
    fun test06_signPdf() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "sign_src.pdf"), 1)
        val signatureBmp = PdfEngineTestFixtures.createSignatureBitmap(200, 100)

        val result = engine.signPdf(
            sourceUri = Uri.fromFile(src),
            signatureBitmap = signatureBmp,
            pageIndex = 0,
            xRatio = 0.5f,
            yRatio = 0.2f,
            outputBaseName = "test_signed"
        )

        signatureBmp.recycle()

        assertTrue("Sign PDF must succeed", result is EngineResult.Success)
        val signedFile = (result as EngineResult.Success).data

        PDDocument.load(signedFile).use { doc ->
            val page = doc.getPage(0)
            val resources = page.resources
            assertNotNull("Resources must not be null", resources)
            var foundImage = false
            for (name in resources.xObjectNames) {
                if (resources.getXObject(name) is PDImageXObject) {
                    foundImage = true
                    break
                }
            }
            assertTrue("Page 0 resources must contain embedded signature image XObject", foundImage)
        }

        // Render verification: signature mark is visually rendered
        val pfd = android.os.ParcelFileDescriptor.open(signedFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(pfd)
        val rPage = renderer.openPage(0)
        val bmp = android.graphics.Bitmap.createBitmap(rPage.width, rPage.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        rPage.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        rPage.close()
        renderer.close()
        pfd.close()

        // Check pixels in signature bounding box (xRatio = 0.5f, yRatio = 0.2f)
        var nonWhiteSigPixels = 0
        val targetW = 150f
        val targetH = (100f / 200f) * targetW
        val pageWidth = 595.27563f
        val pageHeight = 841.88977f
        val posX = (pageWidth * 0.5f).coerceIn(20f, pageWidth - targetW - 20f)
        val posY = (pageHeight * 0.2f).coerceIn(20f, pageHeight - targetH - 20f)

        // Map PDF coordinates (bottom-left) to PdfRenderer display coordinates (top-left)
        val minX = (bmp.width * (posX / pageWidth)).toInt()
        val maxX = (bmp.width * ((posX + targetW) / pageWidth)).toInt()
        val minY = (bmp.height * (1f - (posY + targetH) / pageHeight)).toInt()
        val maxY = (bmp.height * (1f - posY / pageHeight)).toInt()

        for (x in minX..maxX) {
            for (y in minY..maxY) {
                val px = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
                if (px != android.graphics.Color.WHITE) {
                    nonWhiteSigPixels++
                }
            }
        }
        bmp.recycle()
        assertTrue("Signature mark must render visible pixels on output page", nonWhiteSigPixels > 10)
    }

    // 8. Source file preservation and collision handling
    @Test
    fun test08_decoration_sourcePreservationAndCollisionHandling() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "source_to_preserve.pdf"), 2)
        val origLength = src.length()
        val origBytes = src.readBytes()

        // First decoration run
        val res1 = engine.addWatermark(Uri.fromFile(src), "WATERMARK_1", outputBaseName = "collision_test")
        assertTrue(res1 is EngineResult.Success)
        val file1 = (res1 as EngineResult.Success).data

        // Second decoration run with identical outputBaseName: must not overwrite or crash
        val res2 = engine.addWatermark(Uri.fromFile(src), "WATERMARK_2", outputBaseName = "collision_test")
        assertTrue(res2 is EngineResult.Success)
        val file2 = (res2 as EngineResult.Success).data

        // Verify collision handling created distinct files
        assertNotEquals("Colliding base names must produce distinct files", file1.absolutePath, file2.absolutePath)
        assertTrue(file1.exists() && file1.length() > 0)
        assertTrue(file2.exists() && file2.length() > 0)

        // Verify source file was strictly preserved unchanged
        assertEquals("Source file length must remain identical", origLength, src.length())
        assertArrayEquals("Source file bytes must be unchanged", origBytes, src.readBytes())
    }

    // 7. batesNumbering
    @Test
    fun test07_batesNumbering() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "bates_src.pdf"), 3)

        val result = engine.batesNumbering(
            sourceUri = Uri.fromFile(src),
            prefix = "CASE-",
            startNumber = 42,
            digits = 4,
            position = PageNumberPosition.BOTTOM_RIGHT,
            outputBaseName = "test_bates"
        )

        assertTrue("Bates numbering must succeed", result is EngineResult.Success)
        val batesFile = (result as EngineResult.Success).data

        PDDocument.load(batesFile).use { doc ->
            val stripper = PDFTextStripper()
            stripper.startPage = 1
            stripper.endPage = 1
            val p1 = stripper.getText(doc)
            assertTrue("Page 1 must contain CASE-0042", p1.contains("CASE-0042"))

            stripper.startPage = 3
            stripper.endPage = 3
            val p3 = stripper.getText(doc)
            assertTrue("Page 3 must contain CASE-0044", p3.contains("CASE-0044"))
        }
    }
}
