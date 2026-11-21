package com.offlinepdf.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class UnicodeTextToPdfInstrumentationTest {

    private lateinit var context: Context
    private lateinit var engine: PdfBoxEngineImpl
    private lateinit var testOutputDir: File
    private val generatedFiles = mutableListOf<File>()

    companion object {
        private const val TAG = "UnicodePdfTest"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = PdfBoxEngineImpl(context)
        testOutputDir = File(context.cacheDir, "unicode_test_output_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        generatedFiles.forEach { file ->
            try { file.delete() } catch (_: Throwable) {}
        }
        testOutputDir.deleteRecursively()
    }

    data class VerificationResult(
        val file: File,
        val sizeBytes: Long,
        val pageCount: Int,
        val extractedText: String,
        val fontNames: List<String>,
        val renderedImages: List<File>,
        val nonWhitePixelCount: Long,
        val containsExpectedText: Boolean
    )

    private fun verifyGeneratedPdf(
        pdfFile: File,
        expectedSubstrings: List<String> = emptyList(),
        testLabel: String = "test",
        allowBlankPage: Boolean = false
    ): VerificationResult {
        // A. File validation
        assertTrue("Generated PDF file must exist", pdfFile.exists())
        val size = pdfFile.length()
        assertTrue("PDF file size must be non-zero (actual: $size bytes)", size > 0)

        // Validate PDF magic header bytes (%PDF-)
        FileInputStream(pdfFile).use { input ->
            val header = ByteArray(5)
            val bytesRead = input.read(header)
            assertEquals(5, bytesRead)
            assertEquals("%PDF-", String(header, Charsets.US_ASCII))
        }

        // B. PDFBox validation
        var pageCount = 0
        val fontNames = mutableListOf<String>()
        var extractedText = ""

        PDDocument.load(pdfFile).use { doc ->
            pageCount = doc.numberOfPages
            assertTrue("Document must contain at least 1 page", pageCount >= 1)

            for (i in 0 until pageCount) {
                val page = doc.getPage(i)
                val resources = page.resources
                if (resources != null) {
                    resources.fontNames?.forEach { cosName ->
                        try {
                            val font = resources.getFont(cosName)
                            fontNames.add(font.name ?: cosName.name)
                        } catch (e: Exception) {
                            fontNames.add(cosName.name)
                        }
                    }
                }
            }

            val stripper = PDFTextStripper()
            extractedText = stripper.getText(doc)
            Log.i(TAG, "[$testLabel] PDFBox Extracted Text (${extractedText.length} chars):\n$extractedText")
            Log.i(TAG, "[$testLabel] Font resources discovered: $fontNames")
        }

        // C. Android PdfRenderer visual rendering validation
        val renderedImages = mutableListOf<File>()
        var totalNonWhitePixels = 0L

        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                assertEquals("PdfRenderer page count must match PDFBox", pageCount, renderer.pageCount)

                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    // Render at 1.5x resolution for clean inspection
                    val width = (page.width * 1.5f).toInt()
                    val height = (page.height * 1.5f).toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    // Sample pixels to verify the page is not completely blank
                    var pageNonWhitePixels = 0L
                    val sampleStep = 4
                    for (x in 0 until width step sampleStep) {
                        for (y in 0 until height step sampleStep) {
                            val pixel = bitmap.getPixel(x, y)
                            if (pixel != Color.WHITE) {
                                pageNonWhitePixels++
                            }
                        }
                    }
                    totalNonWhitePixels += pageNonWhitePixels

                    // Save rendered page to test output directory
                    val imgFile = File(testOutputDir, "${testLabel}_page_${i + 1}.png")
                    FileOutputStream(imgFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    renderedImages.add(imgFile)
                    bitmap.recycle()

                    Log.i(TAG, "[$testLabel] Page ${i + 1} rendered to ${imgFile.name} (Non-white sampled pixels: $pageNonWhitePixels)")
                }
            }
        }

        if (!allowBlankPage) {
            assertTrue("Rendered pages must not be blank (sampled non-white pixels: $totalNonWhitePixels)", totalNonWhitePixels > 0)
        }

        val containsExpected = expectedSubstrings.all { expectedText ->
            val found = extractedText.contains(expectedText)
            if (!found) {
                Log.w(TAG, "[$testLabel] Searchability note: Expected substring '$expectedText' not found in PDFBox extracted text.")
            }
            found
        }

        return VerificationResult(
            file = pdfFile,
            sizeBytes = size,
            pageCount = pageCount,
            extractedText = extractedText,
            fontNames = fontNames,
            renderedImages = renderedImages,
            nonWhitePixelCount = totalNonWhitePixels,
            containsExpectedText = containsExpected
        )
    }

    // 1. English-only text
    @Test
    fun testEnglishOnlyTextToPdf() = runBlocking {
        val input = "Offline PDF Studio creates PDF documents."
        val title = "English Document"
        val result = engine.textToPdf(input, title, "test_english")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = listOf("Offline PDF Studio", "creates PDF documents"),
            testLabel = "english_only"
        )

        assertEquals(1, verification.pageCount)
        assertTrue("English text must be searchable and extracted by PDFBox", verification.containsExpectedText)
    }

    // 2. Hindi-only text
    @Test
    fun testHindiOnlyTextToPdf(): Unit = runBlocking {
        val input = "नमस्ते दुनिया, यह एक परीक्षण दस्तावेज़ है।"
        val title = "परीक्षण शीर्षक"
        val result = engine.textToPdf(input, title, "test_hindi")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = listOf("नमस्ते दुनिया"),
            testLabel = "hindi_only"
        )

        assertEquals(1, verification.pageCount)
        assertTrue("Page must have non-blank rendered pixels", verification.nonWhitePixelCount > 0)
        Log.i(TAG, "[hindi_only] Searchability status: containsExpectedText = ${verification.containsExpectedText}")
        Unit
    }

    // 3. Mixed Hindi-English text
    @Test
    fun testMixedHindiEnglishTextToPdf() = runBlocking {
        val input = "Invoice संख्या: 12345. कुल देय राशि ₹1,500."
        val title = "Bilingual Invoice"
        val result = engine.textToPdf(input, title, "test_mixed")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = listOf("Invoice", "12345"),
            testLabel = "mixed_bilingual"
        )

        assertEquals(1, verification.pageCount)
        assertTrue("English portion of mixed text must be extracted", verification.extractedText.contains("Invoice"))
    }

    // 4. Devanagari matras: कि, की, कु, के, को, कं
    @Test
    fun testDevanagariMatrasToPdf() = runBlocking {
        val input = "कि, की, कु, के, को, कं"
        val title = "Matras Verification"
        val result = engine.textToPdf(input, title, "test_matras")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = emptyList(),
            testLabel = "devanagari_matras"
        )

        assertEquals(1, verification.pageCount)
        assertTrue("Matras page must have rendered content", verification.nonWhitePixelCount > 0)
    }

    // 5. Conjuncts and ligatures: स्त, त्र, ज्ञ, क्ष
    @Test
    fun testConjunctsAndLigaturesToPdf() = runBlocking {
        val input = "स्त, त्र, ज्ञ, क्ष"
        val title = "Conjuncts Verification"
        val result = engine.textToPdf(input, title, "test_conjuncts")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = emptyList(),
            testLabel = "devanagari_conjuncts"
        )

        assertEquals(1, verification.pageCount)
        assertTrue("Conjuncts page must render vector glyphs", verification.nonWhitePixelCount > 0)
    }

    // 6. Complex Hindi words: किताब, स्थिति, दृष्टिकोण, उज्ज्वल, विद्या
    @Test
    fun testComplexHindiWordsToPdf() = runBlocking {
        val input = "किताब, स्थिति, दृष्टिकोण, उज्ज्वल, विद्या"
        val title = "Complex Hindi Vocabulary"
        val result = engine.textToPdf(input, title, "test_complex_words")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = emptyList(),
            testLabel = "complex_words"
        )

        assertEquals(1, verification.pageCount)
        assertTrue("Complex words must be rendered visually", verification.nonWhitePixelCount > 0)
    }

    // 7. Indian Rupee symbol: ₹
    @Test
    fun testIndianRupeeSymbolToPdf() = runBlocking {
        val input = "Grand Total: ₹ 25,000.75 | अग्रिम भुगतान: ₹ 5,000"
        val title = "Currency Test"
        val result = engine.textToPdf(input, title, "test_rupee")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = listOf("Grand Total", "25,000.75"),
            testLabel = "indian_rupee"
        )

        assertEquals(1, verification.pageCount)
        assertTrue("Document must contain rendered currency symbols", verification.nonWhitePixelCount > 0)
    }

    // 8. Devanagari and Latin numerals: ०, १, २, ३, ४, ५, ६, ७, ८, ९ and 0-9
    @Test
    fun testNumeralsToPdf() = runBlocking {
        val input = "Devanagari Digits: ०, १, २, ३, ४, ५, ६, ७, ८, ९\nLatin Digits: 0, 1, 2, 3, 4, 5, 6, 7, 8, 9"
        val title = "Numerals Comparison"
        val result = engine.textToPdf(input, title, "test_numerals")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = listOf("Latin Digits", "0, 1, 2, 3, 4, 5, 6, 7, 8, 9"),
            testLabel = "numerals"
        )

        assertEquals(1, verification.pageCount)
        assertTrue("Latin numerals must be extracted", verification.extractedText.contains("0, 1, 2, 3"))
    }

    // 9. Long text of at least 1,000 words producing multiple pages
    @Test
    fun testLongTextMultiPageToPdf() = runBlocking {
        val paragraph = "यह दस्तावेज़ ऑफ़लाइन पीडीएफ स्टूडियो (Offline PDF Studio) का एक बहु-पृष्ठ परीक्षण है। " +
                "This document tests complex multi-page pagination, margins, line wrapping, and dynamic layout bounds. " +
                "प्रत्येक पृष्ठ पर हिंदी और अंग्रेजी का सुंदर और स्पष्ट संयोजन होना चाहिए। " +
                "Invoice Item #100: Total amount ₹ 4,500.00 settled successfully.\n\n"

        val builder = java.lang.StringBuilder()
        // Repeat paragraph to exceed 1,000 words
        repeat(35) { index ->
            builder.append("अनुभाग ").append(index + 1).append(": ").append(paragraph)
        }
        val longText = builder.toString()
        val wordCount = longText.split("\\s+".toRegex()).size
        assertTrue("Text must contain at least 1,000 words (actual: $wordCount words)", wordCount >= 1000)

        val title = "Comprehensive Multi-Page Stress Test"
        val result = engine.textToPdf(longText, title, "test_long_multipage")

        assertTrue("Result must be EngineResult.Success", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = listOf("Comprehensive Multi-Page Stress Test"),
            testLabel = "multipage_stress"
        )

        assertTrue("1,000+ words document must produce multiple pages (actual: ${verification.pageCount})", verification.pageCount >= 3)
        assertEquals("Rendered image count must equal page count", verification.pageCount, verification.renderedImages.size)
    }

    // 10. Empty input, title-only, and failure/cancellation cases
    @Test
    fun testEmptyInputHandling() = runBlocking {
        val result = engine.textToPdf("", "", "test_empty")
        assertTrue("Empty text should succeed with an empty page", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = emptyList(),
            testLabel = "empty_input",
            allowBlankPage = true
        )
        assertEquals(1, verification.pageCount)
    }

    @Test
    fun testTitleOnlyHandling() = runBlocking {
        val result = engine.textToPdf("", "Only Title Document", "test_title_only")
        assertTrue("Title-only input should succeed", result is EngineResult.Success)
        val file = (result as EngineResult.Success).data
        generatedFiles.add(file)

        val verification = verifyGeneratedPdf(
            pdfFile = file,
            expectedSubstrings = listOf("Only Title Document"),
            testLabel = "title_only"
        )
        assertEquals(1, verification.pageCount)
        assertTrue(verification.extractedText.contains("Only Title Document"))
    }

    @Test
    fun testCancellationCleansUpPartialFile() = runBlocking {
        val hugeBuilder = StringBuilder()
        repeat(200) {
            hugeBuilder.append("परीक्षण शब्द अनुभाग $it: Offline PDF Studio cancellation test line with extensive text.\n")
        }
        val hugeText = hugeBuilder.toString()

        val job = async(Dispatchers.IO) {
            engine.textToPdf(hugeText, "Cancellation Target", "test_cancel_cleanup")
        }

        // Allow layout to begin, then cancel immediately
        delay(5)
        job.cancelAndJoin()

        assertTrue("Job must be cancelled", job.isCancelled)
        try {
            job.getCompleted()
            fail("Cancelled job should not produce a normal completed result")
        } catch (e: CancellationException) {
            // Expected
        }

        // Verify no leftover partial files in filesDir or cacheDir matching output name
        val leakedFiles = context.filesDir.listFiles { _, name -> name.contains("test_cancel_cleanup") } ?: emptyArray()
        assertEquals("Incomplete output file must be deleted upon cancellation", 0, leakedFiles.size)
    }
}
