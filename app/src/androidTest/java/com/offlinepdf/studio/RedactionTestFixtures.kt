package com.offlinepdf.studio

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Fixture generator for Redaction Security Instrumentation Tests.
 * Programmatically constructs deterministic PDF test files covering:
 * - Unrotated A4 with distinct sensitive and public regions
 * - Rotated pages (90, 180, 270 degrees)
 * - Non-zero CropBox and MediaBox origins
 * - Embedded image XObjects
 * - Document Information (/Info) and XMP Metadata
 * - Interactive document structures (Annotations, AcroForm, Outlines, EmbeddedFiles)
 */
object RedactionTestFixtures {

    /**
     * Standard unrotated A4 page (595.27 x 841.88 pt) with:
     * - Sensitive secret text placed at y = 740 pt (top header, corresponding to yRatio ~ 0.88)
     * - Unredacted public text placed at y = 150 pt (footer, corresponding to yRatio ~ 0.18)
     */
    fun createUnrotatedA4Fixture(
        file: File,
        secretToken: String,
        publicNotice: String = "PUBLIC_HOSPITAL_BANNER_NOTICE"
    ) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            PDPageContentStream(doc, page).use { cs ->
                // 1. Draw top sensitive secret header (expected redaction target)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(120f, 740f)
                cs.showText(secretToken)
                cs.endText()

                // Outline marker around secret text
                cs.setStrokingColor(0, 0, 0)
                cs.addRect(115f, 730f, 360f, 25f)
                cs.stroke()

                // 2. Draw unredacted public notice in body/footer
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(120f, 150f)
                cs.showText(publicNotice)
                cs.endText()
            }
            doc.save(file)
        }
    }

    data class PdfRect(val x: Float, val y: Float, val width: Float, val height: Float)

    /**
     * Converts normalized display coordinates to native PDF coordinates according to page rotation.
     */
    fun displayToPdf(
        dispX: Float,
        dispY: Float,
        dispW: Float,
        dispH: Float,
        rotationDegrees: Int,
        pdfW: Float = 595.27563f,
        pdfH: Float = 841.88977f
    ): PdfRect {
        return when (rotationDegrees) {
            90 -> PdfRect(
                x = pdfW - dispY - dispH,
                y = dispX,
                width = dispH,
                height = dispW
            )
            180 -> PdfRect(
                x = pdfW - dispX - dispW,
                y = dispY,
                width = dispW,
                height = dispH
            )
            270 -> PdfRect(
                x = dispY,
                y = pdfH - dispX - dispW,
                width = dispH,
                height = dispW
            )
            else -> PdfRect(
                x = dispX,
                y = pdfH - dispY - dispH,
                width = dispW,
                height = dispH
            )
        }
    }

    private fun drawSolidMarker(
        cs: PDPageContentStream,
        rect: PdfRect,
        r: Float,
        g: Float,
        b: Float,
        label: String
    ) {
        cs.setNonStrokingColor(r, g, b)
        cs.addRect(rect.x, rect.y, rect.width, rect.height)
        cs.fill()

        // Also draw text label if dimensions permit
        if (rect.width >= 30f && rect.height >= 10f) {
            cs.setNonStrokingColor(1f, 1f, 1f)
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, (rect.height * 0.5f).coerceIn(8f, 12f))
            cs.newLineAtOffset(rect.x + 5f, rect.y + (rect.height * 0.25f))
            cs.showText(label)
            cs.endText()
        }
    }

    /**
     * A4 page rotated by [rotationDegrees] (0, 90, 180, 270) containing:
     * - Secret Marker (Red band): strictly inside intended blackout [X: 0.25..0.75, Y: 0.08..0.11]
     * - Adjacent Public Marker (Green band): immediately below blackout [X: 0.25..0.75, Y: 0.14..0.17]
     * - Distant Public Marker (Blue band): distant footer [X: 0.25..0.75, Y: 0.80..0.84]
     */
    fun createRotatedFixture(
        file: File,
        rotationDegrees: Int,
        secretToken: String,
        adjacentLandmark: String = "PUBLIC_ADJACENT_NOTICE",
        distantLandmark: String = "PUBLIC_DISTANT_NOTICE"
    ) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).apply {
                rotation = rotationDegrees
            }
            doc.addPage(page)

            val dispW = if (rotationDegrees == 90 || rotationDegrees == 270) 841.88977f else 595.27563f
            val dispH = if (rotationDegrees == 90 || rotationDegrees == 270) 595.27563f else 841.88977f

            val secretDisp = displayToPdf(dispW * 0.25f, dispH * 0.08f, dispW * 0.50f, dispH * 0.03f, rotationDegrees)
            val adjDisp = displayToPdf(dispW * 0.25f, dispH * 0.14f, dispW * 0.50f, dispH * 0.03f, rotationDegrees)
            val distDisp = displayToPdf(dispW * 0.25f, dispH * 0.80f, dispW * 0.50f, dispH * 0.04f, rotationDegrees)

            PDPageContentStream(doc, page).use { cs ->
                // 1. High-contrast Secret marker (Red)
                drawSolidMarker(cs, secretDisp, 0.85f, 0.15f, 0.15f, secretToken)

                // 2. Immediate Adjacent Public marker (Green)
                drawSolidMarker(cs, adjDisp, 0.15f, 0.65f, 0.15f, adjacentLandmark)

                // 3. Distant Public marker (Blue)
                drawSolidMarker(cs, distDisp, 0.15f, 0.25f, 0.85f, distantLandmark)
            }
            doc.save(file)
        }
    }

    /**
     * Page with non-zero CropBox origin offset.
     * MediaBox is [0, 0, 600, 800], CropBox is [lowerLeftX, lowerLeftY, width, height].
     */
    fun createNonZeroCropBoxFixture(
        file: File,
        secretToken: String,
        lowerLeftX: Float = 50f,
        lowerLeftY: Float = 100f,
        width: Float = 500f,
        height: Float = 600f,
        adjacentLandmark: String = "PUBLIC_ADJACENT_CROP",
        distantLandmark: String = "PUBLIC_DISTANT_CROP"
    ) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(0f, 0f, 600f, 800f)).apply {
                cropBox = PDRectangle(lowerLeftX, lowerLeftY, width, height)
            }
            doc.addPage(page)

            // In CropBox, display top-left is (lowerLeftX, lowerLeftY + height)
            val secretRect = PdfRect(lowerLeftX + width * 0.25f, lowerLeftY + height * (1f - 0.08f - 0.03f), width * 0.50f, height * 0.03f)
            val adjRect = PdfRect(lowerLeftX + width * 0.25f, lowerLeftY + height * (1f - 0.14f - 0.03f), width * 0.50f, height * 0.03f)
            val distRect = PdfRect(lowerLeftX + width * 0.25f, lowerLeftY + height * (1f - 0.80f - 0.04f), width * 0.50f, height * 0.04f)

            PDPageContentStream(doc, page).use { cs ->
                drawSolidMarker(cs, secretRect, 0.85f, 0.15f, 0.15f, secretToken)
                drawSolidMarker(cs, adjRect, 0.15f, 0.65f, 0.15f, adjacentLandmark)
                drawSolidMarker(cs, distRect, 0.15f, 0.25f, 0.85f, distantLandmark)
            }
            doc.save(file)
        }
    }

    /**
     * Page with non-zero MediaBox origin offset: [lowerLeftX, lowerLeftY, width, height].
     */
    fun createNonZeroMediaBoxFixture(
        file: File,
        secretToken: String,
        lowerLeftX: Float = 100f,
        lowerLeftY: Float = 100f,
        width: Float = 500f,
        height: Float = 700f,
        adjacentLandmark: String = "PUBLIC_ADJACENT_MEDIA",
        distantLandmark: String = "PUBLIC_DISTANT_MEDIA"
    ) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(lowerLeftX, lowerLeftY, width, height))
            doc.addPage(page)

            val secretRect = PdfRect(lowerLeftX + width * 0.25f, lowerLeftY + height * (1f - 0.08f - 0.03f), width * 0.50f, height * 0.03f)
            val adjRect = PdfRect(lowerLeftX + width * 0.25f, lowerLeftY + height * (1f - 0.14f - 0.03f), width * 0.50f, height * 0.03f)
            val distRect = PdfRect(lowerLeftX + width * 0.25f, lowerLeftY + height * (1f - 0.80f - 0.04f), width * 0.50f, height * 0.04f)

            PDPageContentStream(doc, page).use { cs ->
                drawSolidMarker(cs, secretRect, 0.85f, 0.15f, 0.15f, secretToken)
                drawSolidMarker(cs, adjRect, 0.15f, 0.65f, 0.15f, adjacentLandmark)
                drawSolidMarker(cs, distRect, 0.15f, 0.25f, 0.85f, distantLandmark)
            }
            doc.save(file)
        }
    }

    /**
     * Page with both MediaBox and CropBox having distinct non-zero origins.
     */
    fun createBothOriginsNonZeroFixture(
        file: File,
        secretToken: String,
        mediaX: Float = 50f,
        mediaY: Float = 50f,
        mediaW: Float = 650f,
        mediaH: Float = 850f,
        cropX: Float = 100f,
        cropY: Float = 150f,
        cropW: Float = 500f,
        cropH: Float = 600f,
        adjacentLandmark: String = "PUBLIC_ADJACENT_BOTH",
        distantLandmark: String = "PUBLIC_DISTANT_BOTH"
    ) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(mediaX, mediaY, mediaW, mediaH)).apply {
                cropBox = PDRectangle(cropX, cropY, cropW, cropH)
            }
            doc.addPage(page)

            val secretRect = PdfRect(cropX + cropW * 0.25f, cropY + cropH * (1f - 0.08f - 0.03f), cropW * 0.50f, cropH * 0.03f)
            val adjRect = PdfRect(cropX + cropW * 0.25f, cropY + cropH * (1f - 0.14f - 0.03f), cropW * 0.50f, cropH * 0.03f)
            val distRect = PdfRect(cropX + cropW * 0.25f, cropY + cropH * (1f - 0.80f - 0.04f), cropW * 0.50f, cropH * 0.04f)

            PDPageContentStream(doc, page).use { cs ->
                drawSolidMarker(cs, secretRect, 0.85f, 0.15f, 0.15f, secretToken)
                drawSolidMarker(cs, adjRect, 0.15f, 0.65f, 0.15f, adjacentLandmark)
                drawSolidMarker(cs, distRect, 0.15f, 0.25f, 0.85f, distantLandmark)
            }
            doc.save(file)
        }
    }

    /**
     * Page containing an embedded Image XObject and secret text.
     */
    fun createImageFixture(
        file: File,
        secretToken: String
    ) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            // Create a small 64x64 blue bitmap as original image XObject
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLUE)
            }
            val pdImage = LosslessFactory.createFromImage(doc, bitmap)

            PDPageContentStream(doc, page).use { cs ->
                // Draw image XObject
                cs.drawImage(pdImage, 120f, 650f, 100f, 50f)

                // Draw sensitive text
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(120f, 740f)
                cs.showText(secretToken)
                cs.endText()
            }
            bitmap.recycle()
            doc.save(file)
        }
    }

    /**
     * Document with explicit Document Information (/Info) and XMP Metadata (/Metadata).
     */
    fun createMetadataFixture(
        file: File,
        secretToken: String,
        author: String = "Dr. Alice Smith",
        title: String = "Classified Patient Dossier",
        subject: String = "TOP_SECRET_MEDICAL_SUBJECT",
        keywords: String = "Confidential, Patient, SSN"
    ) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(120f, 740f)
                cs.showText(secretToken)
                cs.endText()
            }

            // Set Document Information dictionary
            val info = PDDocumentInformation().apply {
                this.author = author
                this.title = title
                this.subject = subject
                this.keywords = keywords
            }
            doc.documentInformation = info

            // Set Catalog XMP Metadata stream
            val xmp = """
                <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                    <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                        <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                            <dc:creator><rdf:Seq><rdf:li>$author</rdf:li></rdf:Seq></dc:creator>
                            <dc:title><rdf:Alt><rdf:li xml:lang="x-default">$title</rdf:li></rdf:Alt></dc:title>
                            <dc:description><rdf:Alt><rdf:li xml:lang="x-default">$subject</rdf:li></rdf:Alt></dc:description>
                        </rdf:Description>
                    </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>
            """.trimIndent()
            val metadata = PDMetadata(doc)
            metadata.importXMPMetadata(xmp.toByteArray(Charsets.UTF_8))
            doc.documentCatalog.metadata = metadata

            doc.save(file)
        }
    }

    /**
     * Multi-page document with rich structure:
     * - Page 0 (target page): secret header + public notice + page 0 annotation
     * - Page 1 (non-target page): vector text + vector lines + page 1 annotation
     * - AcroForm with text field
     * - Document Outlines (bookmarks)
     * - Embedded file attachment in /Names
     */
    fun createStructuredMultiPageFixture(
        file: File,
        page0Secret: String,
        page0Public: String,
        page1Text: String
    ) {
        PDDocument().use { doc ->
            // Page 0
            val page0 = PDPage(PDRectangle.A4)
            doc.addPage(page0)
            PDPageContentStream(doc, page0).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(120f, 740f)
                cs.showText(page0Secret)
                cs.endText()

                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(120f, 150f)
                cs.showText(page0Public)
                cs.endText()
            }
            // Page 0 annotation
            val annot0 = PDAnnotationText().apply {
                rectangle = PDRectangle(100f, 700f, 20f, 20f)
                contents = "PAGE0_SECRET_NOTE"
            }
            page0.annotations = mutableListOf(annot0)

            // Page 1
            val page1 = PDPage(PDRectangle.A4)
            doc.addPage(page1)
            PDPageContentStream(doc, page1).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(120f, 600f)
                cs.showText(page1Text)
                cs.endText()

                // Vector lines
                cs.setStrokingColor(0, 0, 255)
                cs.moveTo(100f, 500f)
                cs.lineTo(500f, 500f)
                cs.stroke()
            }
            // Page 1 annotation
            val annot1 = PDAnnotationText().apply {
                rectangle = PDRectangle(100f, 400f, 20f, 20f)
                contents = "PAGE1_PUBLIC_NOTE"
            }
            page1.annotations = mutableListOf(annot1)

            // Document Outlines
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            val chapter1 = PDOutlineItem().apply { title = "Chapter 1: Patient Data" }
            val chapter2 = PDOutlineItem().apply { title = "Chapter 2: Public Summary" }
            outline.addLast(chapter1)
            outline.addLast(chapter2)

            // Document AcroForm
            val acroForm = PDAcroForm(doc)
            doc.documentCatalog.acroForm = acroForm
            val res = PDResources()
            res.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
            acroForm.defaultResources = res
            acroForm.defaultAppearance = "/Helv 12 Tf 0 g"
            val textField = PDTextField(acroForm).apply {
                partialName = "patient_ssn"
                defaultAppearance = "/Helv 12 Tf 0 g"
                setValue("FORM_SECRET_SSN_1234")
            }
            acroForm.fields.add(textField)

            // Document Embedded Files
            val efTree = PDEmbeddedFilesNameTreeNode()
            val fileSpec = PDComplexFileSpecification().apply {
                this.file = "secret_attachment.txt"
                val ef = PDEmbeddedFile(doc, ByteArrayInputStream("SECRET_ATTACHMENT_PAYLOAD".toByteArray(Charsets.UTF_8)))
                this.embeddedFile = ef
            }
            efTree.names = mapOf("secret_attachment.txt" to fileSpec)
            val nameDict = PDDocumentNameDictionary(doc.documentCatalog).apply {
                embeddedFiles = efTree
            }
            doc.documentCatalog.names = nameDict

            doc.save(file)
        }
    }

    /**
     * Fixture for verifying lossless encoding fidelity and ringing-free borders (TC-RED-09).
     * Draws pure color reference blocks:
     * - Secret text and box at top (to be redacted)
     * - Pure Green test patch (RGB: 0, 255, 0) adjacent to redaction border
     * - Pure Blue test patch (RGB: 0, 0, 255) distant marker
     */
    fun createLosslessFidelityFixture(
        file: File,
        secretText: String = "LOSSLESS_SECRET_DATA",
        publicText: String = "LOSSLESS_PUBLIC_FOOTER"
    ) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            PDPageContentStream(doc, page).use { cs ->
                // 1. Secret text region at top
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(120f, 740f)
                cs.showText(secretText)
                cs.endText()

                // 2. High-contrast pure Green reference patch (adjacent to redaction: x=120..220, y=660..710)
                cs.setNonStrokingColor(0, 255, 0)
                cs.addRect(120f, 660f, 100f, 50f)
                cs.fill()

                // 3. High-contrast pure Blue reference patch (distant: x=120..220, y=200..250)
                cs.setNonStrokingColor(0, 0, 255)
                cs.addRect(120f, 200f, 100f, 50f)
                cs.fill()

                // 4. Public text at bottom
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(120f, 150f)
                cs.showText(publicText)
                cs.endText()
            }
            doc.save(file)
        }
    }

    /**
     * Fixture for verifying large-format sheet scaling and memory safety (TC-RED-10).
     * Creates an ISO A1 Architectural drawing sheet (1684 x 2384 pt).
     */
    fun createLargeFormatSheetFixture(
        file: File,
        widthPt: Float = 1684f,
        heightPt: Float = 2384f,
        secretText: String = "CONFIDENTIAL_ARCHITECTURAL_BLUEPRINT"
    ) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(widthPt, heightPt))
            doc.addPage(page)

            PDPageContentStream(doc, page).use { cs ->
                // Draw title block / header with secret text
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 24f)
                cs.newLineAtOffset(200f, heightPt - 200f)
                cs.showText(secretText)
                cs.endText()

                // Border outline
                cs.setStrokingColor(0, 0, 0)
                cs.setLineWidth(4f)
                cs.addRect(50f, 50f, widthPt - 100f, heightPt - 100f)
                cs.stroke()

                // Diagonal layout vectors
                cs.moveTo(50f, 50f)
                cs.lineTo(widthPt - 50f, heightPt - 50f)
                cs.stroke()
            }
            doc.save(file)
        }
    }

    /**
     * Dense text-heavy document fixture (multiple paragraphs of text).
     */
    fun createTextHeavyFixture(file: File, secretMarker: String = "CONFIDENTIAL_PATIENT_RECORDS") {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(50f, 800f)
                cs.showText(secretMarker)
                cs.endText()

                // Draw 30 lines of dense text
                for (i in 1..30) {
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 10f)
                    cs.newLineAtOffset(50f, 780f - (i * 22f))
                    cs.showText("Paragraph line $i: The medical treatment protocol requires continuous vital sign observation, therapeutic medication adherence, and documented diagnostic imaging records.")
                    cs.endText()
                }
            }
            doc.save(file)
        }
    }

    /**
     * Scanned document fixture (full-page raster image).
     */
    fun createScannedImageFixture(file: File, secretMarker: String = "SCANNED_SECRET_NOTE") {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            // Synthetic scanned texture: 400x600 bitmap with noise & shapes
            val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(Color.rgb(245, 245, 240))
            val paint = android.graphics.Paint().apply {
                color = Color.rgb(40, 40, 180)
                strokeWidth = 3f
            }
            for (i in 0 until 20) {
                canvas.drawLine(20f, i * 30f, 380f, i * 30f + 10f, paint)
            }
            val ximg = LosslessFactory.createFromImage(doc, bmp)
            bmp.recycle()

            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(ximg, 50f, 100f, 495f, 642f)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
                cs.newLineAtOffset(100f, 760f)
                cs.showText(secretMarker)
                cs.endText()
            }
            doc.save(file)
        }
    }

    /**
     * Technical drawing fixture (dense vector lines, grid lines and markers).
     */
    fun createTechnicalDrawingFixture(file: File, secretMarker: String = "DRAWING_SECRET_SPEC") {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 16f)
                cs.newLineAtOffset(100f, 780f)
                cs.showText(secretMarker)
                cs.endText()

                // Draw dense vector grid
                cs.setStrokingColor(180, 180, 180)
                cs.setLineWidth(0.5f)
                for (x in 50..540 step 15) {
                    cs.moveTo(x.toFloat(), 50f)
                    cs.lineTo(x.toFloat(), 750f)
                    cs.stroke()
                }
                for (y in 50..750 step 15) {
                    cs.moveTo(50f, y.toFloat())
                    cs.lineTo(540f, y.toFloat())
                    cs.stroke()
                }
            }
            doc.save(file)
        }
    }

    /**
     * High-entropy synthetic fixture: full-page continuous-tone pseudo-random RGB noise.
     * Designed to evaluate Deflate/LZ77 worst-case compression expansion vs lossy JPEG DCT quantization.
     */
    fun createHighEntropyNoiseFixture(file: File, secretMarker: String = "HIGH_ENTROPY_SECRET_SPEC") {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            // High-entropy 400x600 bitmap filled with deterministic pseudo-random RGB pixels
            val rng = java.util.Random(42L)
            val bmp = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(400 * 600)
            for (i in pixels.indices) {
                val r = rng.nextInt(256)
                val g = rng.nextInt(256)
                val b = rng.nextInt(256)
                pixels[i] = Color.rgb(r, g, b)
            }
            bmp.setPixels(pixels, 0, 400, 0, 0, 400, 600)
            val ximg = LosslessFactory.createFromImage(doc, bmp)
            bmp.recycle()

            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(ximg, 50f, 100f, 495f, 642f)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(100f, 760f)
                cs.showText(secretMarker)
                cs.endText()
            }
            doc.save(file)
        }
    }

    /**
     * High-entropy synthetic fixture: full-page continuous-tone pseudo-random RGB noise.
     * Full-page coverage (595.28 x 841.89 pt).
     */
    fun createFullPageNoiseFixture(file: File, secretMarker: String = "FULL_PAGE_NOISE_SECRET") {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            // Full-page bitmap (595 x 842) filled with deterministic pseudo-random RGB pixels
            val rng = java.util.Random(1337L)
            val bmpW = 595
            val bmpH = 842
            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(bmpW * bmpH)
            for (i in pixels.indices) {
                val r = rng.nextInt(256)
                val g = rng.nextInt(256)
                val b = rng.nextInt(256)
                pixels[i] = Color.rgb(r, g, b)
            }
            bmp.setPixels(pixels, 0, bmpW, 0, 0, bmpW, bmpH)
            val ximg = LosslessFactory.createFromImage(doc, bmp)
            bmp.recycle()

            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(ximg, 0f, 0f, 595.27563f, 841.88977f)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(100f, 760f)
                cs.showText(secretMarker)
                cs.endText()
            }
            doc.save(file)
        }
    }

    /**
     * Continuous-tone photographic fixture: full-page smooth color gradients and radial lighting transitions.
     * Simulates natural photographic imagery with smooth DCT block transitions.
     */
    fun createFullPagePhotoFixture(file: File, secretMarker: String = "FULL_PAGE_PHOTO_SECRET") {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            val bmpW = 595
            val bmpH = 842
            val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val shader1 = android.graphics.LinearGradient(
                0f, 0f, bmpW.toFloat(), bmpH.toFloat(),
                intArrayOf(
                    Color.rgb(220, 80, 50),
                    Color.rgb(240, 180, 40),
                    Color.rgb(60, 150, 220),
                    Color.rgb(80, 40, 160),
                    Color.rgb(20, 20, 60)
                ),
                floatArrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f),
                android.graphics.Shader.TileMode.CLAMP
            )
            val paint = android.graphics.Paint().apply {
                shader = shader1
            }
            canvas.drawRect(0f, 0f, bmpW.toFloat(), bmpH.toFloat(), paint)

            val radialPaint = android.graphics.Paint().apply {
                shader = android.graphics.RadialGradient(
                    bmpW * 0.5f, bmpH * 0.4f, bmpW * 0.4f,
                    Color.argb(180, 255, 240, 200),
                    Color.argb(0, 0, 0, 0),
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(bmpW * 0.5f, bmpH * 0.4f, bmpW * 0.4f, radialPaint)

            val ximg = LosslessFactory.createFromImage(doc, bmp)
            bmp.recycle()

            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(ximg, 0f, 0f, 595.27563f, 841.88977f)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(100f, 760f)
                cs.showText(secretMarker)
                cs.endText()
            }
            doc.save(file)
        }
    }
}
