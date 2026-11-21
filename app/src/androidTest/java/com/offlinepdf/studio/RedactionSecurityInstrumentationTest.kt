package com.offlinepdf.studio

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinepdf.studio.core.engine.internal.PdfBoxEngineImpl
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Redaction Security & PDF Sanitization Automated Regression Test Suite
 *
 * Verifies or disproves:
 * - TC-RED-01: Searchable secret text is absent from the redacted output page.
 * - TC-RED-02: Target page does not retain its original text content streams.
 * - TC-RED-03: Original image XObjects from the target page are not retained.
 * - TC-RED-04: Correct redaction placement for rotations 0, 90, 180 and 270 degrees.
 * - TC-RED-05: Correct redaction placement for non-zero CropBox and MediaBox origins.
 * - TC-RED-06: Inspect output document metadata retention vs removal (/Info and XMP).
 * - TC-RED-07: Unredacted portions and non-target pages remain readable.
 * - TC-RED-08: Verify annotations, form fields, bookmarks and attachments disposition.
 *
 * Storage Safety:
 * Operates in a strictly isolated temporary directory via [IsolatedTestContext].
 * Never accesses or deletes real user files.
 */
@RunWith(AndroidJUnit4::class)
class RedactionSecurityInstrumentationTest {

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
    private lateinit var engine: PdfBoxEngineImpl

    companion object {
        private const val TAG = "RedactionSecurityTest"
    }

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(appContext)
        isolatedRootDir = File(appContext.cacheDir, "test_isolated_redaction_${System.nanoTime()}").apply {
            mkdirs()
        }
        testContext = IsolatedTestContext(appContext, isolatedRootDir)
        testDocsDir = File(testContext.filesDir, "pdf_documents").apply { mkdirs() }
        engine = PdfBoxEngineImpl(testContext)
    }

    @After
    fun tearDown() {
        if (::isolatedRootDir.isInitialized) {
            isolatedRootDir.deleteRecursively()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Pixel & Stream Verification Helpers
    // -----------------------------------------------------------------------------------------

    data class PixelInspectionResult(
        val blackPixelCount: Int,
        val totalSampledPixels: Int,
        val blackPercentage: Float
    )

    private fun renderPageToBitmap(pdfFile: File, pageIndex: Int = 0, scale: Float = 2.0f): Bitmap {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val page = renderer.openPage(pageIndex)
                val w = (page.width * scale).toInt().coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                return bmp
            }
        }
    }

    private fun inspectRegionPixels(
        bitmap: Bitmap,
        rectLeft: Int,
        rectTop: Int,
        rectRight: Int,
        rectBottom: Int,
        sampleStep: Int = 2
    ): PixelInspectionResult {
        var blackCount = 0
        var totalCount = 0
        val safeLeft = rectLeft.coerceIn(0, bitmap.width - 1)
        val safeRight = rectRight.coerceIn(safeLeft + 1, bitmap.width)
        val safeTop = rectTop.coerceIn(0, bitmap.height - 1)
        val safeBottom = rectBottom.coerceIn(safeTop + 1, bitmap.height)

        for (x in safeLeft until safeRight step sampleStep) {
            for (y in safeTop until safeBottom step sampleStep) {
                totalCount++
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // Consider pixel dark/black if all channels are < 35 (accounting for minor JPEG compression drift)
                if (r < 35 && g < 35 && b < 35) {
                    blackCount++
                }
            }
        }
        val pct = if (totalCount > 0) (blackCount.toFloat() / totalCount.toFloat()) * 100f else 0f
        return PixelInspectionResult(blackCount, totalCount, pct)
    }

    private fun getPageContentStreamText(page: PDPage): String {
        val sb = StringBuilder()
        val contents = page.contentStreams
        while (contents.hasNext()) {
            val stream = contents.next()
            stream.createInputStream()?.use { input ->
                sb.append(input.bufferedReader(Charsets.ISO_8859_1).readText())
            }
        }
        return sb.toString()
    }

    private fun getPageImageXObjectNames(page: PDPage): List<String> {
        val names = mutableListOf<String>()
        val resources = page.resources ?: return names
        val xobjects = resources.xObjectNames ?: return names
        for (cosName in xobjects) {
            val xobj = resources.getXObject(cosName)
            if (xobj is PDImageXObject) {
                names.add(cosName.name)
            }
        }
        return names
    }

    private fun saveRenderedScreenshot(bmp: Bitmap, name: String): File {
        val imgFile = File(testDocsDir, "$name.png")
        FileOutputStream(imgFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return imgFile
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-01: Searchable secret text is absent from the redacted output page
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_01_SearchableSecretTextPurged() = runBlocking {
        val secretToken = "SECRET_PATIENT_SSN_9876_ALPHA"
        val fixtureFile = File(testDocsDir, "fixture_tc_red_01.pdf")
        RedactionTestFixtures.createUnrotatedA4Fixture(fixtureFile, secretToken, "PUBLIC_HEADER_SECTION")

        val result = engine.redactArea(
            sourceUri = Uri.fromFile(fixtureFile),
            pageIndex = 0,
            xRatio = 0.2f,
            yRatio = 0.88f,
            widthRatio = 0.6f,
            heightRatio = 0.05f,
            outputBaseName = "test_red01"
        )
        assertTrue("Redaction must succeed", result is EngineResult.Success)
        val outputFile = (result as EngineResult.Success).data

        PDDocument.load(outputFile).use { doc ->
            val stripper = PDFTextStripper()
            val extracted = stripper.getText(doc)
            Log.i(TAG, "[TC-RED-01] Extracted text length: ${extracted.length}")
            assertFalse(
                "Extracted text must NOT contain sensitive secret token",
                extracted.contains(secretToken)
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-02: Target page does not retain its original text content streams
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_02_TargetPageDiscardsOriginalContentStreams() = runBlocking {
        val secretToken = "SECRET_STREAM_TOKEN_4433"
        val fixtureFile = File(testDocsDir, "fixture_tc_red_02.pdf")
        RedactionTestFixtures.createUnrotatedA4Fixture(fixtureFile, secretToken)

        val result = engine.redactArea(
            sourceUri = Uri.fromFile(fixtureFile),
            pageIndex = 0,
            xRatio = 0.2f,
            yRatio = 0.88f,
            widthRatio = 0.6f,
            heightRatio = 0.05f,
            outputBaseName = "test_red02"
        )
        assertTrue("Redaction must succeed", result is EngineResult.Success)
        val outputFile = (result as EngineResult.Success).data

        PDDocument.load(outputFile).use { doc ->
            val page = doc.getPage(0)
            val streamContent = getPageContentStreamText(page)
            Log.i(TAG, "[TC-RED-02] Target page raw content stream:\n$streamContent")

            // Verify absence of PDF text drawing operators
            assertFalse("Content stream must NOT contain 'BT' (Begin Text) operator", streamContent.contains("BT"))
            assertFalse("Content stream must NOT contain 'ET' (End Text) operator", streamContent.contains("ET"))
            assertFalse("Content stream must NOT contain 'Tj' (Show Text) operator", streamContent.contains("Tj"))
            assertFalse("Content stream must NOT contain 'TJ' (Show Text array) operator", streamContent.contains("TJ"))

            // Verify font resources on target page are null or empty
            val fontCount = page.resources?.fontNames?.toList()?.size ?: 0
            assertEquals("Target page must retain 0 font resources", 0, fontCount)
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-03: Original image XObjects from the target page are not retained
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_03_OriginalImageXObjectsPurged() = runBlocking {
        val secretToken = "SECRET_IMAGE_TOKEN_1122"
        val fixtureFile = File(testDocsDir, "fixture_tc_red_03.pdf")
        RedactionTestFixtures.createImageFixture(fixtureFile, secretToken)

        val result = engine.redactArea(
            sourceUri = Uri.fromFile(fixtureFile),
            pageIndex = 0,
            xRatio = 0.2f,
            yRatio = 0.88f,
            widthRatio = 0.6f,
            heightRatio = 0.05f,
            outputBaseName = "test_red03"
        )
        assertTrue("Redaction must succeed", result is EngineResult.Success)
        val outputFile = (result as EngineResult.Success).data

        PDDocument.load(outputFile).use { doc ->
            val page = doc.getPage(0)
            val imageNames = getPageImageXObjectNames(page)
            Log.i(TAG, "[TC-RED-03] Output page image XObjects: $imageNames")

            // Exactly 1 image must exist on the page: the newly generated rasterized page image
            assertEquals("Target page must contain exactly 1 image XObject (the rasterized page)", 1, imageNames.size)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Visual Redaction Integrity Verification Helpers
    // -----------------------------------------------------------------------------------------

    data class VisualComparisonResult(
        val insetBlackPct: Float,
        val secretRegionBlackPct: Float,
        val adjacentBlackPct: Float,
        val distantBlackPct: Float,
        val unredactedMae: Float,
        val unredactedMatchPct: Float,
        val origDimensions: Pair<Int, Int>,
        val outDimensions: Pair<Int, Int>,
        val isAspectMatch: Boolean
    )

    private fun compareSourceAndOutputVisuals(
        origBmp: Bitmap,
        outBmp: Bitmap,
        redactXRatio: Float = 0.20f,
        redactYRatio: Float = 0.88f,
        redactWRatio: Float = 0.60f,
        redactHRatio: Float = 0.05f
    ): VisualComparisonResult {
        assertEquals("Bitmaps must have identical width", origBmp.width, outBmp.width)
        assertEquals("Bitmaps must have identical height", origBmp.height, outBmp.height)

        val w = outBmp.width
        val h = outBmp.height

        // 1. Intended redaction bounding box
        val rLeft = (w * redactXRatio).toInt()
        val rRight = (w * (redactXRatio + redactWRatio)).toInt()
        val rTop = (h * (1.0f - redactYRatio - redactHRatio)).toInt()
        val rBottom = (h * (1.0f - redactYRatio)).toInt()

        // Inset region (insetting by 6 pixels to exclude JPEG boundary ringing)
        val insetLeft = (rLeft + 6).coerceAtMost(rRight)
        val insetRight = (rRight - 6).coerceAtLeast(insetLeft)
        val insetTop = (rTop + 6).coerceAtMost(rBottom)
        val insetBottom = (rBottom - 6).coerceAtLeast(insetTop)
        val insetInspection = inspectRegionPixels(outBmp, insetLeft, insetTop, insetRight, insetBottom)

        // Secret marker region (0.25..0.75, 0.08..0.11)
        val sLeft = (w * 0.25f).toInt()
        val sRight = (w * 0.75f).toInt()
        val sTop = (h * 0.08f).toInt()
        val sBottom = (h * 0.11f).toInt()
        val secretInspection = inspectRegionPixels(outBmp, sLeft, sTop, sRight, sBottom)

        // Adjacent marker region (0.25..0.75, 0.14..0.17)
        val aLeft = (w * 0.25f).toInt()
        val aRight = (w * 0.75f).toInt()
        val aTop = (h * 0.14f).toInt()
        val aBottom = (h * 0.17f).toInt()
        val adjInspection = inspectRegionPixels(outBmp, aLeft, aTop, aRight, aBottom)

        // Distant marker region (0.25..0.75, 0.80..0.84)
        val dLeft = (w * 0.25f).toInt()
        val dRight = (w * 0.75f).toInt()
        val dTop = (h * 0.80f).toInt()
        val dBottom = (h * 0.84f).toInt()
        val distInspection = inspectRegionPixels(outBmp, dLeft, dTop, dRight, dBottom)

        // Global comparison outside redaction bounding box (with safety margin of 6px)
        val excludeLeft = (rLeft - 6).coerceAtLeast(0)
        val excludeRight = (rRight + 6).coerceAtMost(w)
        val excludeTop = (rTop - 6).coerceAtLeast(0)
        val excludeBottom = (rBottom + 6).coerceAtMost(h)

        var totalDiff = 0.0
        var totalSampled = 0L
        var matchingPixels = 0L
        val sampleStep = 3 // Sample every 3rd pixel for speed and precision

        for (x in 0 until w step sampleStep) {
            for (y in 0 until h step sampleStep) {
                // Skip the redaction exclusion zone
                if (x in excludeLeft until excludeRight && y in excludeTop until excludeBottom) {
                    continue
                }
                totalSampled++
                val pOrig = origBmp.getPixel(x, y)
                val pOut = outBmp.getPixel(x, y)

                val dR = Math.abs(Color.red(pOut) - Color.red(pOrig))
                val dG = Math.abs(Color.green(pOut) - Color.green(pOrig))
                val dB = Math.abs(Color.blue(pOut) - Color.blue(pOrig))

                val pixelMae = (dR + dG + dB) / 3.0
                totalDiff += pixelMae

                // JPEG 92% tolerance: match within 30 per channel
                if (dR <= 30 && dG <= 30 && dB <= 30) {
                    matchingPixels++
                }
            }
        }

        val mae = if (totalSampled > 0) (totalDiff / totalSampled).toFloat() else 0f
        val matchPct = if (totalSampled > 0) (matchingPixels.toFloat() / totalSampled.toFloat()) * 100f else 100f

        val origAspect = origBmp.width.toFloat() / origBmp.height.toFloat()
        val outAspect = outBmp.width.toFloat() / outBmp.height.toFloat()
        val isAspectMatch = Math.abs(origAspect - outAspect) < 0.01f

        return VisualComparisonResult(
            insetBlackPct = insetInspection.blackPercentage,
            secretRegionBlackPct = secretInspection.blackPercentage,
            adjacentBlackPct = adjInspection.blackPercentage,
            distantBlackPct = distInspection.blackPercentage,
            unredactedMae = mae,
            unredactedMatchPct = matchPct,
            origDimensions = Pair(origBmp.width, origBmp.height),
            outDimensions = Pair(outBmp.width, outBmp.height),
            isAspectMatch = isAspectMatch
        )
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-04: Correct redaction placement for rotations 0, 90, 180 and 270 degrees
    // -----------------------------------------------------------------------------------------
    data class RotationResult(
        val rotation: Int,
        val observedRotation: Int,
        val isVisualOrientationPreserved: Boolean,
        val insetBlackPercentage: Float,
        val secretBlackPercentage: Float,
        val adjacentBlackPercentage: Float,
        val distantBlackPercentage: Float,
        val unredactedMae: Float,
        val unredactedMatchPct: Float,
        val isSecretPurged: Boolean,
        val screenshotPath: String,
        val isSuccess: Boolean,
        val errorMessage: String? = null
    )

    @Test
    fun testTC_RED_04_RotatedPagesRedactionPlacement() = runBlocking {
        val rotations = listOf(0, 90, 180, 270)
        val results = mutableListOf<RotationResult>()

        for (rotation in rotations) {
            val secretToken = "SECRET_ROT_${rotation}_TOKEN"
            val adjacentMarker = "PUBLIC_ADJ_ROT_${rotation}"
            val distantMarker = "PUBLIC_DIST_ROT_${rotation}"
            val fixtureFile = File(testDocsDir, "fixture_rot_${rotation}.pdf")
            RedactionTestFixtures.createRotatedFixture(
                file = fixtureFile,
                rotationDegrees = rotation,
                secretToken = secretToken,
                adjacentLandmark = adjacentMarker,
                distantLandmark = distantMarker
            )

            // 1. Render source PDF fixture before redaction
            val origBmp = renderPageToBitmap(fixtureFile, pageIndex = 0, scale = 2.0f)
            val origScreenshot = saveRenderedScreenshot(origBmp, "source_rot_${rotation}")

            // 2. Execute redaction
            val redactionRes = engine.redactArea(
                sourceUri = Uri.fromFile(fixtureFile),
                pageIndex = 0,
                xRatio = 0.2f,
                yRatio = 0.88f,
                widthRatio = 0.6f,
                heightRatio = 0.05f,
                outputBaseName = "test_red_rot_$rotation"
            )
            assertTrue("Redaction must complete without exception", redactionRes is EngineResult.Success)
            val outputFile = (redactionRes as EngineResult.Success).data

            var observedRotation = -1
            PDDocument.load(outputFile).use { doc ->
                observedRotation = doc.getPage(0).rotation
            }

            // 3. Render redacted output PDF at identical scale
            val outBmp = renderPageToBitmap(outputFile, pageIndex = 0, scale = 2.0f)
            val outScreenshot = saveRenderedScreenshot(outBmp, "redacted_rot_${rotation}")

            // 4. Perform visual comparison between source and output
            val comp = compareSourceAndOutputVisuals(origBmp, outBmp)
            origBmp.recycle()
            outBmp.recycle()

            // 5. Verify absence of secret text in reconstructed PDF text streams
            var textExtracted = ""
            PDDocument.load(outputFile).use { doc ->
                textExtracted = PDFTextStripper().getText(doc)
            }
            val secretPurged = !textExtracted.contains(secretToken)

            // Strict visual assertions:
            // - Inset black coverage >= 98%
            // - Secret marker region coverage >= 99%
            // - Adjacent marker black <= 5% (preserved)
            // - Distant marker black <= 5% (preserved)
            // - Unredacted MAE <= 12.0 (JPEG 92% tolerance)
            // - Unredacted match ratio >= 95%
            // - Aspect ratio and dimensions match
            val isInsetBlack = comp.insetBlackPct >= 98.0f
            val isSecretCovered = comp.secretRegionBlackPct >= 99.0f
            val isAdjacentPreserved = comp.adjacentBlackPct <= 5.0f
            val isDistantPreserved = comp.distantBlackPct <= 5.0f
            val isFidelityPreserved = comp.unredactedMae <= 12.0f && comp.unredactedMatchPct >= 95.0f
            val isVisualOrientationPreserved = comp.isAspectMatch

            val success = isInsetBlack && isSecretCovered && isAdjacentPreserved && isDistantPreserved && isFidelityPreserved && isVisualOrientationPreserved && secretPurged

            val errorMsg = when {
                !isVisualOrientationPreserved -> "Visual aspect ratio mismatch: orig=${comp.origDimensions}, out=${comp.outDimensions}"
                !isInsetBlack -> "Inset region not solid black: observed ${comp.insetBlackPct}% (expected >=98%)"
                !isSecretCovered -> "Secret marker not fully covered: observed ${comp.secretRegionBlackPct}% (expected >=99%)"
                !isAdjacentPreserved -> "Adjacent public marker corrupted: observed ${comp.adjacentBlackPct}% black (expected <=5%)"
                !isDistantPreserved -> "Distant public marker corrupted: observed ${comp.distantBlackPct}% black (expected <=5%)"
                !isFidelityPreserved -> "Unredacted fidelity failure: MAE=${comp.unredactedMae} (expected <=12), Match=${comp.unredactedMatchPct}% (expected >=95%)"
                !secretPurged -> "Secret text was not purged from document text stream"
                else -> null
            }

            results.add(
                RotationResult(
                    rotation = rotation,
                    observedRotation = observedRotation,
                    isVisualOrientationPreserved = isVisualOrientationPreserved,
                    insetBlackPercentage = comp.insetBlackPct,
                    secretBlackPercentage = comp.secretRegionBlackPct,
                    adjacentBlackPercentage = comp.adjacentBlackPct,
                    distantBlackPercentage = comp.distantBlackPct,
                    unredactedMae = comp.unredactedMae,
                    unredactedMatchPct = comp.unredactedMatchPct,
                    isSecretPurged = secretPurged,
                    screenshotPath = outScreenshot.name,
                    isSuccess = success,
                    errorMessage = errorMsg
                )
            )

            Log.i(TAG, "[TC-RED-04] Rotation $rotation deg: InsetBlack=${comp.insetBlackPct}%, SecretBlack=${comp.secretRegionBlackPct}%, AdjBlack=${comp.adjacentBlackPct}%, DistBlack=${comp.distantBlackPct}%, MAE=${comp.unredactedMae}, Match=${comp.unredactedMatchPct}%, Status=${if (success) "PASS" else "FAIL: $errorMsg"}")
        }

        // Detailed rotation comparison matrix
        Log.i(TAG, "=== TC-RED-04 ROTATION COMPARISON MATRIX ===")
        results.forEach { r ->
            Log.i(TAG, "  Rot ${r.rotation} deg: InsetBlack=${r.insetBlackPercentage}%, SecretBlack=${r.secretBlackPercentage}%, AdjBlack=${r.adjacentBlackPercentage}%, DistBlack=${r.distantBlackPercentage}%, MAE=${r.unredactedMae}, Match=${r.unredactedMatchPct}%, Status=${if (r.isSuccess) "PASS" else "FAIL (${r.errorMessage})"}")
        }

        val failed = results.filter { !it.isSuccess }
        if (failed.isNotEmpty()) {
            val failureSummary = failed.joinToString(" | ") {
                "Rot ${it.rotation} deg: ${it.errorMessage}"
            }
            fail("TC-RED-04 Verification Failed: $failureSummary")
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-05: Correct redaction placement for non-zero CropBox and MediaBox origins
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_05_NonZeroOriginCropBoxMediaBoxPlacement() = runBlocking {
        data class BoxTestCase(
            val name: String,
            val fixtureCreator: (File, String, String, String) -> Unit
        )

        val testCases = listOf(
            BoxTestCase("NonZeroCropBox") { file, token, adj, dist ->
                RedactionTestFixtures.createNonZeroCropBoxFixture(
                    file = file,
                    secretToken = token,
                    lowerLeftX = 50f,
                    lowerLeftY = 100f,
                    width = 500f,
                    height = 600f,
                    adjacentLandmark = adj,
                    distantLandmark = dist
                )
            },
            BoxTestCase("NonZeroMediaBox") { file, token, adj, dist ->
                RedactionTestFixtures.createNonZeroMediaBoxFixture(
                    file = file,
                    secretToken = token,
                    lowerLeftX = 100f,
                    lowerLeftY = 100f,
                    width = 500f,
                    height = 700f,
                    adjacentLandmark = adj,
                    distantLandmark = dist
                )
            },
            BoxTestCase("BothOriginsNonZero") { file, token, adj, dist ->
                RedactionTestFixtures.createBothOriginsNonZeroFixture(
                    file = file,
                    secretToken = token,
                    mediaX = 50f,
                    mediaY = 50f,
                    mediaW = 650f,
                    mediaH = 850f,
                    cropX = 100f,
                    cropY = 150f,
                    cropW = 500f,
                    cropH = 600f,
                    adjacentLandmark = adj,
                    distantLandmark = dist
                )
            }
        )

        for (tc in testCases) {
            val secretToken = "SECRET_BOX_${tc.name}_TOKEN"
            val adjacentMarker = "PUBLIC_ADJ_${tc.name}"
            val distantMarker = "PUBLIC_DIST_${tc.name}"
            val fixtureFile = File(testDocsDir, "fixture_${tc.name}.pdf")
            tc.fixtureCreator(fixtureFile, secretToken, adjacentMarker, distantMarker)

            // 1. Render source PDF fixture before redaction
            val origBmp = renderPageToBitmap(fixtureFile, pageIndex = 0, scale = 2.0f)
            val origScreenshot = saveRenderedScreenshot(origBmp, "source_${tc.name}")

            // 2. Execute redaction
            val res = engine.redactArea(
                sourceUri = Uri.fromFile(fixtureFile),
                pageIndex = 0,
                xRatio = 0.2f,
                yRatio = 0.88f,
                widthRatio = 0.6f,
                heightRatio = 0.05f,
                outputBaseName = "test_red_${tc.name}"
            )
            assertTrue("${tc.name} redaction must succeed", res is EngineResult.Success)
            val outFile = (res as EngineResult.Success).data

            // 3. Render redacted output PDF at identical scale
            val outBmp = renderPageToBitmap(outFile, pageIndex = 0, scale = 2.0f)
            val outScreenshot = saveRenderedScreenshot(outBmp, "redacted_${tc.name}")

            // 4. Perform visual comparison between source and output
            val comp = compareSourceAndOutputVisuals(origBmp, outBmp)
            origBmp.recycle()
            outBmp.recycle()

            // 5. Verify absence of secret text in text stream
            var textExtracted = ""
            PDDocument.load(outFile).use { doc ->
                textExtracted = PDFTextStripper().getText(doc)
            }
            val secretPurged = !textExtracted.contains(secretToken)

            Log.i(TAG, "[TC-RED-05] ${tc.name}: InsetBlack=${comp.insetBlackPct}%, SecretBlack=${comp.secretRegionBlackPct}%, AdjBlack=${comp.adjacentBlackPct}%, DistBlack=${comp.distantBlackPct}%, MAE=${comp.unredactedMae}, Match=${comp.unredactedMatchPct}%")

            // Strict visual assertions:
            assertTrue("${tc.name}: Inset blackout must be solid black (actual: ${comp.insetBlackPct}%)", comp.insetBlackPct >= 98.0f)
            assertTrue("${tc.name}: Secret marker must be completely covered by black (actual: ${comp.secretRegionBlackPct}%)", comp.secretRegionBlackPct >= 99.0f)
            assertTrue("${tc.name}: Adjacent public marker must not be blacked out (actual: ${comp.adjacentBlackPct}%)", comp.adjacentBlackPct <= 5.0f)
            assertTrue("${tc.name}: Distant public marker must not be blacked out (actual: ${comp.distantBlackPct}%)", comp.distantBlackPct <= 5.0f)
            assertTrue("${tc.name}: Unredacted MAE must be <= 12.0 (actual: ${comp.unredactedMae})", comp.unredactedMae <= 12.0f)
            assertTrue("${tc.name}: Unredacted pixel match ratio must be >= 95.0% (actual: ${comp.unredactedMatchPct}%)", comp.unredactedMatchPct >= 95.0f)
            assertTrue("${tc.name}: Secret text must be purged from document", secretPurged)
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-06: Inspect output document metadata retention vs removal (/Info and XMP)
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_06_MetadataRetentionVsRemovalAudit() = runBlocking {
        val secretToken = "SECRET_METADATA_DOC_0099"
        val author = "Dr. Alice Smith"
        val title = "Classified Patient Dossier"
        val subject = "TOP_SECRET_MEDICAL_SUBJECT"
        val keywords = "Confidential, Patient, SSN, 000-12-3456"

        val fixtureFile = File(testDocsDir, "fixture_tc_red_06.pdf")
        RedactionTestFixtures.createMetadataFixture(
            file = fixtureFile,
            secretToken = secretToken,
            author = author,
            title = title,
            subject = subject,
            keywords = keywords
        )

        val result = engine.redactArea(
            sourceUri = Uri.fromFile(fixtureFile),
            pageIndex = 0,
            xRatio = 0.2f,
            yRatio = 0.88f,
            widthRatio = 0.6f,
            heightRatio = 0.05f,
            outputBaseName = "test_red06"
        )
        assertTrue(result is EngineResult.Success)
        val outputFile = (result as EngineResult.Success).data

        PDDocument.load(outputFile).use { doc ->
            val info = doc.documentInformation
            val metadata = doc.documentCatalog.metadata

            Log.i(TAG, "=== TC-RED-06 METADATA DISPOSITION AUDIT ===")
            Log.i(TAG, "  Original Author: '$author' -> Output Author: '${info?.author}'")
            Log.i(TAG, "  Original Title: '$title' -> Output Title: '${info?.title}'")
            Log.i(TAG, "  Original Subject: '$subject' -> Output Subject: '${info?.subject}'")
            Log.i(TAG, "  Original Keywords: '$keywords' -> Output Keywords: '${info?.keywords}'")
            Log.i(TAG, "  Original XMP Stream Present: true -> Output XMP Present: ${metadata != null}")

            // Document reconstruction via empty PDDocument() completely purges document metadata
            assertNull("Document reconstruction discards /Info Author", info?.author)
            assertNull("Document reconstruction discards /Info Title", info?.title)
            assertNull("Document reconstruction discards /Info Subject", info?.subject)
            assertNull("Document reconstruction discards /Info Keywords", info?.keywords)
            assertNull("Document reconstruction discards /Metadata XMP stream", metadata)
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-07: Confirm that unredacted portions and non-target pages remain readable
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_07_UnredactedPortionsAndNonTargetPagesRemainIntact() = runBlocking {
        val page0Secret = "SECRET_HEADER_TARGET_PAGE"
        val page0Public = "PUBLIC_HOSPITAL_NOTICE_FOOTER"
        val page1VectorText = "PRISTINE_VECTOR_PAGE_2_CONTENT"

        val fixtureFile = File(testDocsDir, "fixture_tc_red_07.pdf")
        RedactionTestFixtures.createStructuredMultiPageFixture(
            file = fixtureFile,
            page0Secret = page0Secret,
            page0Public = page0Public,
            page1Text = page1VectorText
        )

        val result = engine.redactArea(
            sourceUri = Uri.fromFile(fixtureFile),
            pageIndex = 0,
            xRatio = 0.2f,
            yRatio = 0.88f,
            widthRatio = 0.6f,
            heightRatio = 0.05f,
            outputBaseName = "test_red07"
        )
        assertTrue(result is EngineResult.Success)
        val outputFile = (result as EngineResult.Success).data

        PDDocument.load(outputFile).use { doc ->
            assertEquals("Total pages must remain 2", 2, doc.numberOfPages)

            // Verify Page 2 (non-target page): vector text preserved
            val stripper = PDFTextStripper().apply {
                startPage = 2
                endPage = 2
            }
            val p2Text = stripper.getText(doc)
            assertTrue("Page 2 text must be preserved", p2Text.contains(page1VectorText))

            // Verify Page 2 content stream retains vector drawing & text operators
            val p2Stream = getPageContentStreamText(doc.getPage(1))
            assertTrue("Page 2 must retain BT operator", p2Stream.contains("BT"))
            assertTrue("Page 2 must retain Tj operator", p2Stream.contains("Tj"))
            assertTrue("Page 2 must retain m (moveTo) vector operator", p2Stream.contains("m"))
            assertTrue("Page 2 must retain l (lineTo) vector operator", p2Stream.contains("l"))

            // Verify Page 1 (target page): sensitive text purged
            stripper.startPage = 1
            stripper.endPage = 1
            val p1Text = stripper.getText(doc)
            assertFalse("Page 1 secret text must be purged", p1Text.contains(page0Secret))
        }

        // Verify Page 1 unredacted footer remains visually intact (not blacked out)
        val bmp = renderPageToBitmap(outputFile, pageIndex = 0, scale = 2.0f)
        val footerInspection = inspectRegionPixels(
            bmp,
            rectLeft = (bmp.width * 0.15f).toInt(),
            rectTop = (bmp.height * 0.75f).toInt(),
            rectRight = (bmp.width * 0.85f).toInt(),
            rectBottom = (bmp.height * 0.90f).toInt()
        )
        bmp.recycle()

        Log.i(TAG, "[TC-RED-07] Unredacted footer black pixel percentage: ${footerInspection.blackPercentage}%")
        assertTrue("Unredacted footer must not be blacked out", footerInspection.blackPercentage < 30.0f)
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-08: Verify annotations, form fields, bookmarks and attachments disposition
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_08_DocumentStructurePreservationAudit() = runBlocking {
        val page0Secret = "SECRET_DOC_STRUCTURE_TEST"
        val fixtureFile = File(testDocsDir, "fixture_tc_red_08.pdf")
        RedactionTestFixtures.createStructuredMultiPageFixture(
            file = fixtureFile,
            page0Secret = page0Secret,
            page0Public = "PUBLIC_FOOTER",
            page1Text = "PAGE_2_DATA"
        )

        val result = engine.redactArea(
            sourceUri = Uri.fromFile(fixtureFile),
            pageIndex = 0,
            xRatio = 0.2f,
            yRatio = 0.88f,
            widthRatio = 0.6f,
            heightRatio = 0.05f,
            outputBaseName = "test_red08"
        )
        assertTrue(result is EngineResult.Success)
        val outputFile = (result as EngineResult.Success).data

        PDDocument.load(outputFile).use { doc ->
            val p0Annots = doc.getPage(0).annotations ?: emptyList()
            val p1Annots = doc.getPage(1).annotations ?: emptyList()
            val acroForm = doc.documentCatalog.acroForm
            val outlines = doc.documentCatalog.documentOutline
            val embeddedFiles = doc.documentCatalog.names?.embeddedFiles

            Log.i(TAG, "=== TC-RED-08 STRUCTURAL INTEGRITY AUDIT ===")
            Log.i(TAG, "  - Page 0 Annotations: ${p0Annots.size} (Expected: 0 / Purged for Sanitization)")
            Log.i(TAG, "  - Page 1 Annotations: ${p1Annots.size} (Imported: ${p1Annots.isNotEmpty()})")
            Log.i(TAG, "  - Document AcroForm: ${if (acroForm != null) "Retained" else "DROPPED (Collateral Damage)"}")
            Log.i(TAG, "  - Document Outlines (Bookmarks): ${if (outlines != null) "Retained" else "DROPPED (Collateral Damage)"}")
            Log.i(TAG, "  - Embedded Files: ${if (embeddedFiles != null) "Retained" else "DROPPED (Collateral Damage)"}")

            // 1. Target page sanitization invariant: all page 0 annotations must be completely destroyed
            assertEquals("Page 0 annotations must be purged", 0, p0Annots.size)

            // 2. Structural loss documentation:
            // Since production code builds targetDoc = PDDocument() from scratch and only loops importPage,
            // document-level catalog structures (AcroForm, Outlines, EmbeddedFiles) are dropped.
            assertNull("AcroForm dropped by reconstruction", acroForm)
            assertNull("Outlines dropped by reconstruction", outlines)
            assertNull("Embedded files dropped by reconstruction", embeddedFiles)
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-09: Verify Lossless Encoding & Ringing-Free Borders (H-02)
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_09_LosslessPixelPreservationAndNoRinging() = runBlocking {
        val secretText = "LOSSLESS_SECRET_DATA_H02"
        val publicText = "LOSSLESS_PUBLIC_FOOTER_H02"
        val fixtureFile = File(testDocsDir, "fixture_tc_red_09.pdf")
        RedactionTestFixtures.createLosslessFidelityFixture(
            file = fixtureFile,
            secretText = secretText,
            publicText = publicText
        )

        // Redact the top secret banner: x: 100..480, y: 720..760 on A4 (595.28 x 841.89 pt)
        // xRatio ~ 0.17, yRatio ~ 0.85, widthRatio ~ 0.65, heightRatio ~ 0.06
        val result = engine.redactArea(
            sourceUri = Uri.fromFile(fixtureFile),
            pageIndex = 0,
            xRatio = 0.17f,
            yRatio = 0.85f,
            widthRatio = 0.65f,
            heightRatio = 0.06f,
            outputBaseName = "test_red09"
        )
        assertTrue("Redaction must succeed", result is EngineResult.Success)
        val outputFile = (result as EngineResult.Success).data

        // 1. Structural inspection of the generated image XObject
        PDDocument.load(outputFile).use { doc ->
            assertEquals("Must have 1 page", 1, doc.numberOfPages)
            val page = doc.getPage(0)
            val resources = page.resources
            val xobjectNames = resources.xObjectNames.toList()
            assertTrue("Page must contain at least 1 XObject", xobjectNames.isNotEmpty())

            var foundImage: PDImageXObject? = null
            for (name in xobjectNames) {
                val xobj = resources.getXObject(name)
                if (xobj is PDImageXObject) {
                    foundImage = xobj
                    break
                }
            }
            assertNotNull("Page must contain a PDImageXObject", foundImage)
            val img = foundImage!!

            Log.i(TAG, "=== TC-RED-09 IMAGE XOBJECT INSPECTION ===")
            Log.i(TAG, "  - Image Suffix: ${img.suffix}")
            Log.i(TAG, "  - ColorSpace: ${img.colorSpace?.name}")
            Log.i(TAG, "  - BitsPerComponent: ${img.bitsPerComponent}")
            Log.i(TAG, "  - Dimensions: ${img.width} x ${img.height}")
            Log.i(TAG, "  - SoftMask (SMask): ${img.softMask}")
            Log.i(TAG, "  - Filter: ${img.cosObject.getItem(COSName.FILTER)}")

            // Must be FlateDecode lossless compression (NOT DCTDecode lossy JPEG)
            val filterItem = img.cosObject.getItem(COSName.FILTER)
            assertTrue(
                "Filter must be FlateDecode, was: $filterItem",
                filterItem == COSName.FLATE_DECODE || img.suffix.equals("png", ignoreCase = true)
            )
            // ColorSpace must be DeviceRGB with 8 bits per component
            assertEquals("Bits per component must be 8", 8, img.bitsPerComponent)
            assertEquals("Color space must be DeviceRGB", COSName.DEVICERGB.name, img.colorSpace?.name)
            // Opaque page rendering must not contain an unnecessary soft mask (SMask)
            assertNull("Opaque page must not create an SMask", img.softMask)
        }

        // 2. Visual pixel fidelity & ringing check:
        // Render redacted output and inspect adjacent pure green patch (x=120..220, y=660..710)
        val bmp = renderPageToBitmap(outputFile, pageIndex = 0, scale = 1.0f)
        try {
            // Check redaction region: must be solid black (sample inset region within redaction box)
            val rLeft = (bmp.width * 0.17f).toInt()
            val rRight = (bmp.width * (0.17f + 0.65f)).toInt()
            val rTop = (bmp.height * (1.0f - 0.85f - 0.06f)).toInt()
            val rBottom = (bmp.height * (1.0f - 0.85f)).toInt()

            val redactionInspection = inspectRegionPixels(
                bmp,
                rectLeft = rLeft + 4,
                rectTop = rTop + 4,
                rectRight = rRight - 4,
                rectBottom = rBottom - 4
            )
            Log.i(TAG, "[TC-RED-09] Redaction black pixel percentage: ${redactionInspection.blackPercentage}%")
            assertTrue("Redaction area must be solid black", redactionInspection.blackPercentage >= 99.0f)

            // Check adjacent unredacted green patch: PDF y=660..710 => normalized yRatio ~ 0.78..0.84
            // Top on screen: 1.0 - 0.84 = 0.16; Bottom: 1.0 - 0.78 = 0.22
            val greenLeft = (bmp.width * (130f / 595.28f)).toInt()
            val greenRight = (bmp.width * (210f / 595.28f)).toInt()
            val greenTop = (bmp.height * ((841.89f - 700f) / 841.89f)).toInt()
            val greenBottom = (bmp.height * ((841.89f - 670f) / 841.89f)).toInt()

            var totalGreenDeviance = 0.0
            var greenSampleCount = 0
            for (x in greenLeft until greenRight step 2) {
                for (y in greenTop until greenBottom step 2) {
                    val pixel = bmp.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    // Pure green is (0, 255, 0). Measure deviation: |r - 0| + |g - 255| + |b - 0|
                    val dev = (Math.abs(r - 0) + Math.abs(g - 255) + Math.abs(b - 0)) / 3.0
                    totalGreenDeviance += dev
                    greenSampleCount++
                }
            }
            val meanDeviance = if (greenSampleCount > 0) totalGreenDeviance / greenSampleCount else 0.0
            Log.i(TAG, "[TC-RED-09] Unredacted green patch mean absolute color deviation: $meanDeviance")
            // In JPEG (DCT), edge ringing causes deviance > 10.0. In Lossless FlateDecode, deviance is virtually 0.
            assertTrue(
                "Lossless encoding must preserve unredacted patch without DCT ringing (mean deviation < 5.0, was $meanDeviance)",
                meanDeviance < 5.0
            )
        } finally {
            bmp.recycle()
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-10: Verify Resolution Scaling & Memory Budget Safety (H-02)
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_10_ResolutionAndMemoryBudgetSafety() = runBlocking {
        // 1. Standard A4 resolution test: verify effective DPI >= 180 (scale >= 2.5x)
        val a4File = File(testDocsDir, "fixture_tc_red_10_a4.pdf")
        RedactionTestFixtures.createUnrotatedA4Fixture(
            file = a4File,
            secretToken = "SECRET_A4_RESOLUTION_TEST"
        )
        val a4Result = engine.redactArea(
            sourceUri = Uri.fromFile(a4File),
            pageIndex = 0,
            xRatio = 0.2f,
            yRatio = 0.85f,
            widthRatio = 0.6f,
            heightRatio = 0.06f,
            outputBaseName = "test_red10_a4"
        )
        assertTrue("A4 redaction must succeed", a4Result is EngineResult.Success)
        val a4OutputFile = (a4Result as EngineResult.Success).data

        PDDocument.load(a4OutputFile).use { doc ->
            val page = doc.getPage(0)
            val xName = page.resources.xObjectNames.first()
            val img = page.resources.getXObject(xName) as PDImageXObject
            val effectiveDpiX = (img.width.toFloat() / 595.28f) * 72f
            val effectiveDpiY = (img.height.toFloat() / 841.89f) * 72f

            Log.i(TAG, "=== TC-RED-10 A4 RESOLUTION AUDIT ===")
            Log.i(TAG, "  - Image Dimensions: ${img.width} x ${img.height}")
            Log.i(TAG, "  - Effective DPI: X=${effectiveDpiX}, Y=${effectiveDpiY}")

            assertTrue("A4 Effective DPI must be >= 180 (was $effectiveDpiX)", effectiveDpiX >= 180f)
            assertTrue("A4 Effective DPI must be >= 180 (was $effectiveDpiY)", effectiveDpiY >= 180f)
            // Verify pixel count does not exceed budget
            val totalPixels = img.width.toLong() * img.height.toLong()
            assertTrue("A4 total pixels must be <= 6.29 MP", totalPixels <= 6_291_456L)
        }

        // 2. Large-format sheet memory safety test (ISO A1 Architectural sheet: 1684 x 2384 pt)
        val a1File = File(testDocsDir, "fixture_tc_red_10_a1.pdf")
        RedactionTestFixtures.createLargeFormatSheetFixture(
            file = a1File,
            widthPt = 1684f,
            heightPt = 2384f,
            secretText = "SECRET_A1_BLUEPRINT_SPEC"
        )
        val a1Result = engine.redactArea(
            sourceUri = Uri.fromFile(a1File),
            pageIndex = 0,
            xRatio = 0.1f,
            yRatio = 0.85f,
            widthRatio = 0.8f,
            heightRatio = 0.1f,
            outputBaseName = "test_red10_a1"
        )
        assertTrue("A1 large-format redaction must succeed without OOM", a1Result is EngineResult.Success)
        val a1OutputFile = (a1Result as EngineResult.Success).data

        PDDocument.load(a1OutputFile).use { doc ->
            val page = doc.getPage(0)
            val xName = page.resources.xObjectNames.first()
            val img = page.resources.getXObject(xName) as PDImageXObject

            val maxDimension = maxOf(img.width, img.height)
            val totalPixels = img.width.toLong() * img.height.toLong()
            val continuousBitmapBytes = totalPixels * 4L

            Log.i(TAG, "=== TC-RED-10 A1 LARGE-FORMAT SAFETY AUDIT ===")
            Log.i(TAG, "  - Output Image Dimensions: ${img.width} x ${img.height}")
            Log.i(TAG, "  - Max Dimension: $maxDimension (Cap: 2880)")
            Log.i(TAG, "  - Total Pixels: $totalPixels (~${totalPixels / 1_000_000f} MP)")
            Log.i(TAG, "  - Continuous Bitmap Memory: $continuousBitmapBytes bytes (${continuousBitmapBytes / (1024 * 1024)} MB, Limit: <= 25 MB)")

            assertTrue("Max dimension must be capped <= 2880px, was: $maxDimension", maxDimension <= 2880)
            assertTrue("Total pixels must be capped <= 6,291,456, was: $totalPixels", totalPixels <= 6_291_456L)
            assertTrue("Continuous bitmap memory must be <= 25 MB, was: ${continuousBitmapBytes / (1024 * 1024)} MB", continuousBitmapBytes <= 26_214_400L)
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-11: Concurrent Redaction Serialization & Process-Wide Mutex Safety
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_11_ConcurrentRedactionMemorySerialization() = runBlocking {
        val f1 = File(testDocsDir, "fixture_concurrent_1.pdf")
        val f2 = File(testDocsDir, "fixture_concurrent_2.pdf")
        RedactionTestFixtures.createUnrotatedA4Fixture(f1, "SECRET_CONCURRENT_1")
        RedactionTestFixtures.createUnrotatedA4Fixture(f2, "SECRET_CONCURRENT_2")

        var maxConcurrentActive = 0
        val isMonitoring = java.util.concurrent.atomic.AtomicBoolean(true)

        val monitorJob = launch(Dispatchers.Default) {
            while (isMonitoring.get()) {
                val active = PdfBoxEngineImpl.activeRedactionJobs.get()
                if (active > maxConcurrentActive) {
                    maxConcurrentActive = active
                }
                delay(1)
            }
        }

        val d1 = async(Dispatchers.IO) {
            engine.redactArea(Uri.fromFile(f1), 0, 0.2f, 0.85f, 0.6f, 0.06f, "out_concurrent_1")
        }
        val d2 = async(Dispatchers.IO) {
            engine.redactArea(Uri.fromFile(f2), 0, 0.2f, 0.85f, 0.6f, 0.06f, "out_concurrent_2")
        }

        val res1 = d1.await()
        val res2 = d2.await()

        isMonitoring.set(false)
        monitorJob.join()

        assertTrue("Job 1 must succeed", res1 is EngineResult.Success)
        assertTrue("Job 2 must succeed", res2 is EngineResult.Success)

        Log.i(TAG, "=== TC-RED-11 CONCURRENCY SERIALIZATION AUDIT ===")
        Log.i(TAG, "  - Max observed concurrent active redaction jobs: $maxConcurrentActive")
        assertEquals("Heavy redaction jobs must be strictly serialized (max 1)", 1, maxConcurrentActive)
        assertEquals("Active jobs after completion must be 0", 0, PdfBoxEngineImpl.activeRedactionJobs.get())
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-12: Coroutine Cancellation While Waiting For Memory Mutex
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_12_CancellationWhileWaitingForMemoryMutex() = runBlocking {
        val f1 = File(testDocsDir, "fixture_cancel_1.pdf")
        val f2 = File(testDocsDir, "fixture_cancel_2.pdf")
        RedactionTestFixtures.createUnrotatedA4Fixture(f1, "SECRET_CANCEL_1")
        RedactionTestFixtures.createUnrotatedA4Fixture(f2, "SECRET_CANCEL_2")

        // Start job 1
        val job1 = async(Dispatchers.IO) {
            engine.redactArea(Uri.fromFile(f1), 0, 0.2f, 0.85f, 0.6f, 0.06f, "out_cancel_1")
        }

        // Give job 1 a head start to acquire the mutex
        delay(10)

        // Start job 2 on a separate coroutine
        val job2 = launch(Dispatchers.IO) {
            engine.redactArea(Uri.fromFile(f2), 0, 0.2f, 0.85f, 0.6f, 0.06f, "out_cancel_2")
        }

        // Cancel job 2 while it waits for the mutex
        delay(15)
        job2.cancelAndJoin()
        assertTrue("Job 2 must be cancelled", job2.isCancelled)

        // Job 1 should still finish cleanly
        val res1 = job1.await()
        assertTrue("Job 1 must succeed despite sibling cancellation", res1 is EngineResult.Success)
        assertEquals("Active jobs after cancellation must be 0", 0, PdfBoxEngineImpl.activeRedactionJobs.get())
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-13: Resource Cleanup on Failure
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_13_ResourceCleanupOnFailure() = runBlocking {
        val nonExistentFile = File(testDocsDir, "does_not_exist.pdf")
        val res = engine.redactArea(
            sourceUri = Uri.fromFile(nonExistentFile),
            pageIndex = 0,
            xRatio = 0.2f,
            yRatio = 0.85f,
            widthRatio = 0.6f,
            heightRatio = 0.06f,
            outputBaseName = "out_fail_test"
        )
        assertTrue("Invalid input must return EngineResult.Error", res is EngineResult.Error)
        assertEquals("Active redaction jobs must be 0 after failure", 0, PdfBoxEngineImpl.activeRedactionJobs.get())
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-14: File Fidelity & Measured Size Comparisons (JPEG vs FlateDecode on Disk)
    // -----------------------------------------------------------------------------------------
    private fun createJpegComparisonPdf(
        sourceFile: File,
        outputFile: File,
        xRatio: Float = 0.1f,
        yRatio: Float = 0.88f,
        widthRatio: Float = 0.8f,
        heightRatio: Float = 0.08f
    ) {
        ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val rPage = renderer.openPage(0)
                val displayW = rPage.width.toFloat()
                val displayH = rPage.height.toFloat()
                val renderScale = (200f / 72f).coerceAtMost(2880f / maxOf(rPage.width, rPage.height).toFloat())
                val bmpW = (rPage.width * renderScale).toInt().coerceAtLeast(1)
                val bmpH = (rPage.height * renderScale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                rPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                rPage.close()

                val rectLeft = (bmpW * xRatio).coerceIn(0f, bmpW.toFloat())
                val rectTop = (bmpH * (1.0f - yRatio - heightRatio)).coerceIn(0f, bmpH.toFloat())
                val rectRight = (rectLeft + bmpW * widthRatio).coerceAtMost(bmpW.toFloat())
                val rectBottom = (rectTop + bmpH * heightRatio).coerceAtMost(bmpH.toFloat())
                val paint = android.graphics.Paint().apply {
                    color = Color.BLACK
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, paint)

                PDDocument.load(sourceFile).use { srcDoc ->
                    PDDocument().use { outDoc ->
                        val newPage = PDPage(PDRectangle(displayW, displayH))
                        newPage.cropBox = PDRectangle(displayW, displayH)
                        newPage.rotation = 0
                        outDoc.addPage(newPage)
                        val ximg = JPEGFactory.createFromImage(outDoc, bmp, 0.92f)
                        PDPageContentStream(outDoc, newPage).use { cs ->
                            cs.drawImage(ximg, 0f, 0f, displayW, displayH)
                        }
                        outDoc.save(outputFile)
                    }
                }
                bmp.recycle()
            }
        }
    }

    @Test
    fun testTC_RED_14_FileFidelityAndSizeComparison() = runBlocking {
        val fText = File(testDocsDir, "fixture_size_text.pdf")
        val fScan = File(testDocsDir, "fixture_size_scan.pdf")
        val fDraw = File(testDocsDir, "fixture_size_draw.pdf")

        RedactionTestFixtures.createTextHeavyFixture(fText)
        RedactionTestFixtures.createScannedImageFixture(fScan)
        RedactionTestFixtures.createTechnicalDrawingFixture(fDraw)

        // Generate FlateDecode lossless redaction
        val rText = engine.redactArea(Uri.fromFile(fText), 0, 0.1f, 0.88f, 0.8f, 0.08f, "out_size_text_flate")
        val rScan = engine.redactArea(Uri.fromFile(fScan), 0, 0.1f, 0.88f, 0.8f, 0.08f, "out_size_scan_flate")
        val rDraw = engine.redactArea(Uri.fromFile(fDraw), 0, 0.1f, 0.88f, 0.8f, 0.08f, "out_size_draw_flate")

        assertTrue(rText is EngineResult.Success)
        assertTrue(rScan is EngineResult.Success)
        assertTrue(rDraw is EngineResult.Success)

        val outFlateText = (rText as EngineResult.Success).data
        val outFlateScan = (rScan as EngineResult.Success).data
        val outFlateDraw = (rDraw as EngineResult.Success).data

        // Generate identical-bitmap JPEG (0.92) comparison PDFs
        val outJpegText = File(testDocsDir, "out_size_text_jpeg.pdf")
        val outJpegScan = File(testDocsDir, "out_size_scan_jpeg.pdf")
        val outJpegDraw = File(testDocsDir, "out_size_draw_jpeg.pdf")
        createJpegComparisonPdf(fText, outJpegText)
        createJpegComparisonPdf(fScan, outJpegScan)
        createJpegComparisonPdf(fDraw, outJpegDraw)

        Log.i(TAG, "=== TC-RED-14 ACTUAL MEASURED FILE SIZES ON DISK (BYTES) ===")
        Log.i(TAG, "  - Text-Heavy: Input=${fText.length()} B | JPEG 0.92=${outJpegText.length()} B | FlateLossless=${outFlateText.length()} B")
        Log.i(TAG, "  - Scanned-Image: Input=${fScan.length()} B | JPEG 0.92=${outJpegScan.length()} B | FlateLossless=${outFlateScan.length()} B")
        Log.i(TAG, "  - Technical-Drawing: Input=${fDraw.length()} B | JPEG 0.92=${outJpegDraw.length()} B | FlateLossless=${outFlateDraw.length()} B")

        assertTrue("Flate Text output must exist and be non-empty", outFlateText.length() > 0)
        assertTrue("JPEG Text output must exist and be non-empty", outJpegText.length() > 0)
        assertTrue("Flate Scan output must exist and be non-empty", outFlateScan.length() > 0)
        assertTrue("JPEG Scan output must exist and be non-empty", outJpegScan.length() > 0)
        assertTrue("Flate Draw output must exist and be non-empty", outFlateDraw.length() > 0)
        assertTrue("JPEG Draw output must exist and be non-empty", outJpegDraw.length() > 0)
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-15: Coroutine Cancellation During Processing
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_15_CancellationDuringProcessing() = runBlocking {
        val a1File = File(testDocsDir, "fixture_cancel_processing_a1.pdf")
        RedactionTestFixtures.createLargeFormatSheetFixture(a1File, 1684f, 2384f, "SECRET_CANCEL_PROCESSING")

        val job = launch(Dispatchers.IO) {
            engine.redactArea(Uri.fromFile(a1File), 0, 0.1f, 0.85f, 0.8f, 0.1f, "out_cancel_proc")
        }

        // Allow job to enter processing, then cancel it
        delay(12)
        job.cancelAndJoin()

        assertTrue("Job must be cancelled", job.isCancelled)
        assertEquals("Active jobs after mid-processing cancellation must be 0", 0, PdfBoxEngineImpl.activeRedactionJobs.get())
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-16: Memory Budget Calculation & Safety Reserve Audit
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_16_MemoryBudgetCalculationAudit() = runBlocking {
        // Standard A4: 595.28 x 841.89 pt at 200 DPI (renderScale ~ 2.778)
        val a4W = (595.28f * (200f / 72f)).toInt()
        val a4H = (841.89f * (200f / 72f)).toInt()
        val a4Pixels = a4W.toLong() * a4H.toLong()

        val nativeBudgetA4 = PdfBoxEngineImpl.calculateRequiredBitmapBytes(a4W, a4H)
        val javaBudgetA4 = PdfBoxEngineImpl.calculateRequiredJavaBytes(a4W, a4H)

        Log.i(TAG, "=== TC-RED-16 MEMORY ADMISSION BUDGET AUDIT ===")
        Log.i(TAG, "  - A4 Pixels: $a4Pixels (~${a4Pixels / 1_000_000f} MP)")
        Log.i(TAG, "  - A4 Native Bitmap Budget (4 B/px): $nativeBudgetA4 B (${nativeBudgetA4 / (1024 * 1024)} MiB)")
        Log.i(TAG, "  - A4 Java Heap Worst-Case Budget (16 B/px + 16 MiB): $javaBudgetA4 B (${javaBudgetA4 / (1024 * 1024)} MiB)")

        assertTrue("A4 Native budget must be <= 16 MiB", nativeBudgetA4 <= 16L * 1024L * 1024L)
        assertTrue("A4 Java budget must be <= 80 MiB", javaBudgetA4 <= 80L * 1024L * 1024L)

        // Boundary conditions and overflow-safe arithmetic verification
        assertEquals(PdfBoxEngineImpl.PDFBOX_WORKING_BYTES, PdfBoxEngineImpl.calculateRequiredJavaBytes(0, 0))
        assertEquals(0L, PdfBoxEngineImpl.calculateRequiredBitmapBytes(0, 0))
        assertEquals(Long.MAX_VALUE, PdfBoxEngineImpl.calculateRequiredJavaBytes(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, PdfBoxEngineImpl.calculateRequiredBitmapBytes(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-17: Temporary Source PDF Deletion Failure Detected and Reported
    // -----------------------------------------------------------------------------------------
    @Test
    fun testTC_RED_17_TempSourcePdfDeletionFailureDetectedAndReported() = runBlocking {
        val f = File(testDocsDir, "fixture_deletion_test.pdf")
        RedactionTestFixtures.createUnrotatedA4Fixture(f, "SECRET_DELETION_TEST")

        val warningMessages = mutableListOf<String>()
        val engineWithFailingDeleter = PdfBoxEngineImpl(
            context = testContext,
            fileDeleter = { false }, // Injected deletion seam simulates failure
            cleanupWarningLogger = { msg -> warningMessages.add(msg) }
        )

        val result = engineWithFailingDeleter.redactArea(
            sourceUri = Uri.fromFile(f),
            pageIndex = 0,
            xRatio = 0.2f,
            yRatio = 0.85f,
            widthRatio = 0.6f,
            heightRatio = 0.06f,
            outputBaseName = "out_deletion_test"
        )

        assertTrue("Redaction should complete despite temp cleanup failure", result is EngineResult.Success)
        Log.i(TAG, "=== TC-RED-17 DELETION FAILURE WARNING AUDIT ===")
        Log.i(TAG, "  - Captured cleanup warnings: ${warningMessages.size}")
        for (w in warningMessages) {
            Log.i(TAG, "    * $w")
        }

        assertTrue("Cleanup failure must be detected and logged", warningMessages.any { it.contains("Failed to delete temporary") })
        assertEquals("Active jobs must return to 0 even when deletion fails", 0, PdfBoxEngineImpl.activeRedactionJobs.get())
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-18: Multi-Fixture Compression & Memory Benchmark (FlateDecode vs JPEG 0.92)
    // -----------------------------------------------------------------------------------------
    data class BenchmarkResult(
        val name: String,
        val inputBytes: Long,
        val flateBytes: Long,
        val jpegBytes: Long,
        val flateDurationMs: Long,
        val jpegDurationMs: Long,
        val pssBeforeMb: Long,
        val pssDuringFlateMb: Long,
        val pssDuringJpegMb: Long,
        val pssAfterMb: Long
    )

    @Test
    fun testTC_RED_18_HighEntropyCompressionBenchmark() = runBlocking {
        val fixtures = listOf(
            Triple("Full-Page Noise (High-Entropy)", File(testDocsDir, "bm_full_noise.pdf")) { f: File ->
                RedactionTestFixtures.createFullPageNoiseFixture(f)
            },
            Triple("Full-Page Photo (Continuous-Tone)", File(testDocsDir, "bm_full_photo.pdf")) { f: File ->
                RedactionTestFixtures.createFullPagePhotoFixture(f)
            },
            Triple("Text-Heavy Document", File(testDocsDir, "bm_text_heavy.pdf")) { f: File ->
                RedactionTestFixtures.createTextHeavyFixture(f)
            },
            Triple("Technical Drawing (Vector Grid)", File(testDocsDir, "bm_tech_draw.pdf")) { f: File ->
                RedactionTestFixtures.createTechnicalDrawingFixture(f)
            }
        )

        val results = mutableListOf<BenchmarkResult>()

        for ((name, fFile, generator) in fixtures) {
            generator(fFile)

            val pssBeforeKb = android.os.Debug.getPss()

            // 1. Lossless FlateDecode encoding
            var flateDurationMs: Long
            val flateResult: EngineResult<File>
            var pssDuringFlateKb: Long = 0
            flateDurationMs = kotlin.system.measureTimeMillis {
                flateResult = engine.redactArea(Uri.fromFile(fFile), 0, 0.1f, 0.85f, 0.8f, 0.08f, "out_bm_flate_${fFile.nameWithoutExtension}")
                pssDuringFlateKb = android.os.Debug.getPss()
            }
            assertTrue("Flate redaction must succeed for $name", flateResult is EngineResult.Success)
            val outFlateFile = (flateResult as EngineResult.Success).data

            // 2. JPEG 0.92 encoding from identical rendered bitmap
            val outJpegFile = File(testDocsDir, "out_bm_jpeg_${fFile.nameWithoutExtension}.pdf")
            var jpegDurationMs: Long
            var pssDuringJpegKb: Long = 0
            jpegDurationMs = kotlin.system.measureTimeMillis {
                createJpegComparisonPdf(fFile, outJpegFile)
                pssDuringJpegKb = android.os.Debug.getPss()
            }
            assertTrue("JPEG comparison file must exist for $name", outJpegFile.exists() && outJpegFile.length() > 0)

            val pssAfterKb = android.os.Debug.getPss()

            results.add(
                BenchmarkResult(
                    name = name,
                    inputBytes = fFile.length(),
                    flateBytes = outFlateFile.length(),
                    jpegBytes = outJpegFile.length(),
                    flateDurationMs = flateDurationMs,
                    jpegDurationMs = jpegDurationMs,
                    pssBeforeMb = pssBeforeKb / 1024,
                    pssDuringFlateMb = pssDuringFlateKb / 1024,
                    pssDuringJpegMb = pssDuringJpegKb / 1024,
                    pssAfterMb = pssAfterKb / 1024
                )
            )
        }

        Log.i(TAG, "==========================================================================================")
        Log.i(TAG, "TC-RED-18 COMPREHENSIVE COMPRESSION & ENCODING BENCHMARK (Flate vs JPEG 0.92)")
        Log.i(TAG, "==========================================================================================")
        Log.i(TAG, String.format("%-32s | %-10s | %-10s | %-10s | %-7s | %-8s | %-8s | %-12s",
            "Fixture", "Input", "Flate", "JPEG 0.92", "Ratio", "Flate ms", "JPEG ms", "PSS (B->F->J->A)"))
        Log.i(TAG, "------------------------------------------------------------------------------------------")

        for (r in results) {
            val ratioStr = String.format("%.2fx", r.flateBytes.toDouble() / r.jpegBytes.toDouble())
            val pssTrace = "${r.pssBeforeMb}->${r.pssDuringFlateMb}->${r.pssDuringJpegMb}->${r.pssAfterMb}M"
            Log.i(TAG, String.format("%-32s | %8d B | %8d B | %8d B | %-7s | %6d ms | %6d ms | %-12s",
                r.name, r.inputBytes, r.flateBytes, r.jpegBytes, ratioStr, r.flateDurationMs, r.jpegDurationMs, pssTrace))
        }
        Log.i(TAG, "==========================================================================================")
        Log.i(TAG, "DIAGNOSTIC LIMITATIONS AUDIT:")
        Log.i(TAG, "  - Debug.getPss() samples instantaneous proportional set size; transient peak allocations")
        Log.i(TAG, "    between samples (e.g. during Deflate buffer doubling) are not captured by periodic polling.")
        Log.i(TAG, "  - Native allocations for Bitmap pixels reside in graphics/ashmem memory on API 26+.")
        Log.i(TAG, "  - FlateDecode preserves 100% pixel fidelity with zero DCT artifacts, text edge ringing,")
        Log.i(TAG, "    or high-contrast boundary bleed. High-entropy noise pages expand under Deflate due to")
        Log.i(TAG, "    lack of repeated byte sequences, whereas text and drawing pages compress cleanly.")
        Log.i(TAG, "==========================================================================================")

        for (r in results) {
            assertTrue("Flate file must be non-empty", r.flateBytes > 0)
            assertTrue("JPEG file must be non-empty", r.jpegBytes > 0)
        }
    }

    // -----------------------------------------------------------------------------------------
    // TC-RED-19: Actual Android 14 Runtime Deflater & BAOS Capacity Growth Verification
    // -----------------------------------------------------------------------------------------
    class TrackingByteArrayOutputStream(initialCapacity: Int) : java.io.ByteArrayOutputStream(initialCapacity) {
        val capacityHistory = mutableListOf<Int>()
        private var lastCapacity = initialCapacity

        init {
            capacityHistory.add(initialCapacity)
        }

        override fun write(b: Int) {
            super.write(b)
            checkCapacity()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            super.write(b, off, len)
            checkCapacity()
        }

        private fun checkCapacity() {
            val cap = buf.size
            if (cap != lastCapacity) {
                capacityHistory.add(cap)
                lastCapacity = cap
            }
        }
    }

    @Test
    fun testTC_RED_19_ActualAndroidDeflaterRuntimeBehavior() = runBlocking {
        // High-entropy deterministic byte payload: N = 300,000 bytes
        val n = 300_000
        val rng = java.util.Random(42L)
        val rawInput = ByteArray(n)
        rng.nextBytes(rawInput)

        val initialCapacity = n / 2
        val trackingBaos = TrackingByteArrayOutputStream(initialCapacity)

        val filter = com.tom_roush.pdfbox.filter.FilterFactory.INSTANCE.getFilter(com.tom_roush.pdfbox.cos.COSName.FLATE_DECODE)
        val inStream = java.io.ByteArrayInputStream(rawInput)

        filter.encode(inStream, trackingBaos, com.tom_roush.pdfbox.cos.COSDictionary(), 0)

        val finalCompressedLength = trackingBaos.size()
        val finalBufferCapacity = trackingBaos.capacityHistory.last()
        val capacityTrace = trackingBaos.capacityHistory.joinToString(" -> ") { "$it B" }

        val didExceedN = finalCompressedLength > n
        val didTriggerSecondResize = trackingBaos.capacityHistory.size >= 3

        Log.i(TAG, "==========================================================================================")
        Log.i(TAG, "TC-RED-19 ACTUAL ANDROID 14 DEFLATER RUNTIME MEASUREMENT")
        Log.i(TAG, "==========================================================================================")
        Log.i(TAG, "  - Exact raw input length N: $n B")
        Log.i(TAG, "  - Initial BAOS capacity N/2: $initialCapacity B")
        Log.i(TAG, "  - Actual compressed output length: $finalCompressedLength B")
        Log.i(TAG, "  - Every BAOS capacity growth: $capacityTrace")
        Log.i(TAG, "  - Whether output exceeds N: $didExceedN (${finalCompressedLength - n} B difference)")
        Log.i(TAG, "  - Second BAOS resize triggered: $didTriggerSecondResize (Cap: $finalBufferCapacity B)")
        Log.i(TAG, "  - Final compressed stream length: $finalCompressedLength B")
        Log.i(TAG, "==========================================================================================")

        assertTrue("Compressed output must be non-empty", finalCompressedLength > 0)
        assertTrue("Capacity must grow from initial N/2", trackingBaos.capacityHistory.size >= 2)
        assertEquals("Initial capacity in history must be N/2", initialCapacity, trackingBaos.capacityHistory.first())
    }
}
