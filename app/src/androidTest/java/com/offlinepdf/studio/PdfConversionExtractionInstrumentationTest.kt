package com.offlinepdf.studio

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.CompressionLevel
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.util.FileStorageManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfConversionExtractionInstrumentationTest {

    private lateinit var context: Context
    private lateinit var storageManager: FileStorageManager
    private lateinit var engine: PdfBoxEngineImpl
    private lateinit var testDocsDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = FileStorageManager(context)
        engine = PdfBoxEngineImpl(context, storageManager)
        testDocsDir = File(context.filesDir, "test_conv_extract").apply {
            if (!exists()) mkdirs()
        }
    }

    @After
    fun tearDown() {
        testDocsDir.deleteRecursively()
        File(context.cacheDir, "pdf_processing").deleteRecursively()
    }

    // 1. imagesToPdf
    @Test
    fun test01_imagesToPdf() = runBlocking {
        val img1 = PdfEngineTestFixtures.createTestImageFile(File(testDocsDir, "img1.png"), 150, 200, Color.RED)
        val img2 = PdfEngineTestFixtures.createTestImageFile(File(testDocsDir, "img2.png"), 200, 300, Color.GREEN)

        val result = engine.imagesToPdf(
            imageUris = listOf(Uri.fromFile(img1), Uri.fromFile(img2)),
            outputBaseName = "test_images_to_pdf"
        )

        assertTrue("Images to PDF must succeed", result is EngineResult.Success)
        val pdfFile = (result as EngineResult.Success).data
        assertTrue("Output PDF must exist", pdfFile.exists())

        PDDocument.load(pdfFile).use { doc ->
            assertEquals("Output PDF must have 2 pages", 2, doc.numberOfPages)
            val p1 = doc.getPage(0)
            assertEquals(150f, p1.mediaBox.width, 1f)
            assertEquals(200f, p1.mediaBox.height, 1f)
        }
    }

    // 2. pdfToImages - PNG and JPEG
    @Test
    fun test02_pdfToImages() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "p2img_src.pdf"), 2)

        val resultPng = engine.pdfToImages(
            sourceUri = Uri.fromFile(src),
            format = "PNG",
            outputBaseName = "test_p2img_png"
        )

        assertTrue("PDF to PNG must succeed", resultPng is EngineResult.Success)
        val pngFiles = (resultPng as EngineResult.Success).data
        assertEquals("Must produce 2 image files for 2 pages", 2, pngFiles.size)
        pngFiles.forEach { file ->
            assertTrue("Rendered image file must exist", file.exists())
            assertTrue("Rendered image file must not be empty", file.length() > 0)
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            assertNotNull("Must decode as valid bitmap", bitmap)
            bitmap.recycle()
        }
    }

    // 3. extractImages
    @Test
    fun test03_extractImages() = runBlocking {
        val src = PdfEngineTestFixtures.createPdfWithEmbeddedImage(File(testDocsDir, "extract_img_src.pdf"), 250, 250)

        val result = engine.extractImages(
            sourceUri = Uri.fromFile(src),
            outputBaseName = "test_extracted_img"
        )

        assertTrue("Extract images must succeed", result is EngineResult.Success)
        val extractedImages = (result as EngineResult.Success).data
        assertEquals("Must extract exactly 1 image", 1, extractedImages.size)
        val imgFile = extractedImages[0]
        assertTrue(imgFile.exists())
        val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
        assertNotNull(bmp)
        assertEquals(250, bmp.width)
        assertEquals(250, bmp.height)
        bmp.recycle()
    }

    // 4. extractText
    @Test
    fun test04_extractText() = runBlocking {
        val src = PdfEngineTestFixtures.createMultiPagePdf(File(testDocsDir, "extract_text_src.pdf"), 2)

        val result = engine.extractText(
            sourceUri = Uri.fromFile(src),
            outputBaseName = "test_extracted_text"
        )

        assertTrue("Extract text must succeed", result is EngineResult.Success)
        val txtFile = (result as EngineResult.Success).data
        assertTrue(txtFile.exists())
        val content = txtFile.readText()
        assertTrue("Extracted text must contain Page 1 Content", content.contains("Page 1 Content"))
        assertTrue("Extracted text must contain Page 2 Content", content.contains("Page 2 Content"))
    }

    // 5. convertToGrayscale - Deterministic pixel channel R=G=B assertion
    @Test
    fun test05_convertToGrayscale() = runBlocking {
        // Pure saturated RED block (R=255, G=0, B=0)
        val src = PdfEngineTestFixtures.createKnownColorPdf(File(testDocsDir, "pure_red.pdf"), 255, 0, 0)

        val result = engine.convertToGrayscale(
            sourceUri = Uri.fromFile(src),
            outputBaseName = "test_grayscale"
        )

        assertTrue("Grayscale conversion must succeed", result is EngineResult.Success)
        val grayFile = (result as EngineResult.Success).data
        assertTrue(grayFile.exists())

        PDDocument.load(grayFile).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }

        // Render output page to verify pixel channels satisfy R ≈ G ≈ B (strictly desaturated)
        val pfd = android.os.ParcelFileDescriptor.open(grayFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(pfd)
        val page = renderer.openPage(0)
        val bmp = android.graphics.Bitmap.createBitmap(page.width / 2, page.height / 2, android.graphics.Bitmap.Config.ARGB_8888)
        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        renderer.close()
        pfd.close()

        // Sample center of the image block (drawn at [100, 300, 200, 200] in PDF space)
        val sampleX = (bmp.width * (200f / 595f)).toInt()
        val sampleY = (bmp.height * (1f - (400f / 842f))).toInt()
        val pixel = bmp.getPixel(sampleX, sampleY)
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        bmp.recycle()

        // In true grayscale, all RGB channels must be equal within rendering/compression tolerance (<= 4)
        assertTrue("Grayscale channels must be equal (|R - G| <= 4): R=$r, G=$g", kotlin.math.abs(r - g) <= 4)
        assertTrue("Grayscale channels must be equal (|G - B| <= 4): G=$g, B=$b", kotlin.math.abs(g - b) <= 4)
        // Android ColorMatrix.setSaturation(0f) applies ITU-R BT.709 luminance:
        // L = 0.213 * R + 0.715 * G + 0.072 * B. For pure RED (255, 0, 0), theoretical L = 0.213 * 255 ≈ 54.3.
        // Allowing for rendering / JPEG quantization variance, verified bounds are 54 ± 6 (48..60).
        assertTrue("Grayscale luminance must match theoretical BT.709 value (54 ± 6): R=$r", r in 48..60)
    }

    // 6. invertColors - Deterministic channel inversion verification
    @Test
    fun test06_invertColors() = runBlocking {
        // Pure BLUE block (R=0, G=0, B=255)
        val src = PdfEngineTestFixtures.createKnownColorPdf(File(testDocsDir, "pure_blue.pdf"), 0, 0, 255)

        val result = engine.invertColors(
            sourceUri = Uri.fromFile(src),
            outputBaseName = "test_inverted"
        )

        assertTrue("Invert colors must succeed", result is EngineResult.Success)
        val invFile = (result as EngineResult.Success).data
        assertTrue(invFile.exists())

        PDDocument.load(invFile).use { doc ->
            assertEquals(1, doc.numberOfPages)
        }

        // Invert of Blue (0, 0, 255) is Yellow (255, 255, 0)
        val pfd = android.os.ParcelFileDescriptor.open(invFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(pfd)
        val page = renderer.openPage(0)
        val bmp = android.graphics.Bitmap.createBitmap(page.width / 2, page.height / 2, android.graphics.Bitmap.Config.ARGB_8888)
        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        renderer.close()
        pfd.close()

        val sampleX = (bmp.width * (200f / 595f)).toInt()
        val sampleY = (bmp.height * (1f - (400f / 842f))).toInt()
        val pixel = bmp.getPixel(sampleX, sampleY)
        val rOut = Color.red(pixel)
        val gOut = Color.green(pixel)
        val bOut = Color.blue(pixel)
        bmp.recycle()

        // Yellow output: high Red (>= 240), high Green (>= 240), low Blue (<= 15)
        assertTrue("Inverted Blue must have high Red component (>= 240): R=$rOut", rOut >= 240)
        assertTrue("Inverted Blue must have high Green component (>= 240): G=$gOut", gOut >= 240)
        assertTrue("Inverted Blue must have low Blue component (<= 15): B=$bOut", bOut <= 15)
    }

    // 7. compress - Inspect image dimensions before and after; verify downsampling and text preservation
    @Test
    fun test07_compress() = runBlocking {
        val textMarker = "Important Contract Terms Intact"
        val src = PdfEngineTestFixtures.createLargeImagePdf(
            File(testDocsDir, "comp_large.pdf"),
            width = 1600,
            height = 1600,
            text = textMarker
        )

        // Verify initial oversized dimensions
        PDDocument.load(src).use { docBefore ->
            val page = docBefore.getPage(0)
            val name = page.resources.xObjectNames.first()
            val imgBefore = page.resources.getXObject(name) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
            assertEquals("Initial width must be 1600", 1600, imgBefore.width)
            assertEquals("Initial height must be 1600", 1600, imgBefore.height)
        }

        // Compress with HIGH level (maxDimension = 800)
        val result = engine.compress(
            sourceUri = Uri.fromFile(src),
            level = CompressionLevel.HIGH,
            outputBaseName = "test_compressed_downsampled"
        )

        assertTrue("Compress must succeed", result is EngineResult.Success)
        val compFile = (result as EngineResult.Success).data
        assertTrue(compFile.exists())

        PDDocument.load(compFile).use { docAfter ->
            assertEquals(1, docAfter.numberOfPages)
            val page = docAfter.getPage(0)
            val name = page.resources.xObjectNames.first()
            val imgAfter = page.resources.getXObject(name) as com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject

            // Assert downsampling occurred to <= 800px
            assertTrue("Compressed image width must be <= 800: ${imgAfter.width}", imgAfter.width <= 800)
            assertTrue("Compressed image height must be <= 800: ${imgAfter.height}", imgAfter.height <= 800)

            // Assert non-image text content remains intact
            val stripped = com.tom_roush.pdfbox.text.PDFTextStripper().getText(docAfter)
            assertTrue("Page text content must remain intact after image compression", stripped.contains(textMarker))
        }
    }

    // 8. repairPdf - Exercises PDFBox xref recovery on damaged xref table
    @Test
    fun test08_repairPdf() = runBlocking {
        val corruptedSrc = PdfEngineTestFixtures.createCorruptedXrefPdf(File(testDocsDir, "damaged_xref.pdf"))

        val result = engine.repairPdf(
            sourceUri = Uri.fromFile(corruptedSrc),
            outputBaseName = "test_repaired_xref"
        )

        assertTrue("Repair must recover document with damaged xref", result is EngineResult.Success)
        val repFile = (result as EngineResult.Success).data
        assertTrue(repFile.exists())

        PDDocument.load(repFile).use { doc ->
            assertEquals("Recovered document must have 2 pages", 2, doc.numberOfPages)
            val text = com.tom_roush.pdfbox.text.PDFTextStripper().getText(doc)
            assertTrue("Recovered page 1 content must be readable", text.contains("Page 1 Content"))
            assertTrue("Recovered page 2 content must be readable", text.contains("Page 2 Content"))
        }
    }

    // 9. repairPdf - Unrecoverable corruption fails safely and cleans up
    @Test
    fun test09_repairPdf_unrecoverableCorruptionFailsSafely() = runBlocking {
        val nonPdf = PdfEngineTestFixtures.createUnrecoverableFile(File(testDocsDir, "garbage_file.pdf"))

        val result = engine.repairPdf(
            sourceUri = Uri.fromFile(nonPdf),
            outputBaseName = "test_repair_garbage"
        )

        assertTrue("Repair of unrecoverable non-PDF must return Error", result is EngineResult.Error)
        val err = result as EngineResult.Error
        assertTrue("Error user message must mention repair failure", err.userMessage.contains("Repair failed", ignoreCase = true))

        // Verify temp files cleaned up
        val procDir = File(context.cacheDir, "pdf_processing")
        val orphaned = procDir.listFiles { _, name -> name.startsWith("rep_") } ?: emptyArray()
        assertEquals("Temporary processing files must be cleaned up after failure", 0, orphaned.size)
    }
}
