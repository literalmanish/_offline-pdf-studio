package com.offlinepdf.studio

import android.graphics.Bitmap
import android.graphics.Color
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import java.io.FileOutputStream

object PdfEngineTestFixtures {

    fun createMultiPagePdf(file: File, pageCount: Int = 3): File {
        PDDocument().use { doc ->
            for (i in 1..pageCount) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 16f)
                    cs.newLineAtOffset(50f, 750f)
                    cs.showText("Page $i Content")
                    cs.newLineAtOffset(0f, -30f)
                    cs.setFont(PDType1Font.HELVETICA, 12f)
                    cs.showText("Sample line of body text on page $i for testing.")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    fun createPdfWithEmbeddedImage(file: File, width: Int = 200, height: Int = 200): File {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(Color.MAGENTA)
            val ximage = JPEGFactory.createFromImage(doc, bmp, 0.9f)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(ximage, 100f, 400f, width.toFloat(), height.toFloat())
            }
            bmp.recycle()
            doc.save(file)
        }
        return file
    }

    fun createPdfWithMetadata(
        file: File,
        title: String = "Test Title",
        author: String = "Test Author",
        subject: String = "Test Subject",
        keywords: String = "test, engine, pdfbox",
        creator: String = "Offline PDF Studio"
    ): File {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val info = PDDocumentInformation().apply {
                this.title = title
                this.author = author
                this.subject = subject
                this.keywords = keywords
                this.creator = creator
            }
            doc.documentInformation = info
            doc.save(file)
        }
        return file
    }

    fun createPdfWithOutline(file: File, titles: List<String>): File {
        PDDocument().use { doc ->
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline

            titles.forEach { title ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                val item = PDOutlineItem().apply {
                    this.title = title
                }
                outline.addLast(item)
            }
            doc.save(file)
        }
        return file
    }

    fun createEncryptedPdf(file: File, password: String): File {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 12f)
                cs.newLineAtOffset(50f, 750f)
                cs.showText("Protected Secret Content")
                cs.endText()
            }
            val ap = AccessPermission()
            val spp = StandardProtectionPolicy(password, password, ap)
            spp.encryptionKeyLength = 128
            spp.permissions = ap
            doc.protect(spp)
            doc.save(file)
        }
        return file
    }

    fun createTestImageFile(file: File, width: Int = 100, height: Int = 100, color: Int = Color.BLUE): File {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(color)
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()
        return file
    }

    fun createSignatureBitmap(width: Int = 200, height: Int = 100): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            color = Color.BLUE
            strokeWidth = 6f
            style = android.graphics.Paint.Style.STROKE
        }
        canvas.drawLine(10f, 50f, 190f, 50f, paint)
        canvas.drawCircle(100f, 50f, 30f, paint)
        return bmp
    }

    fun createPdfWithAcroForm(file: File): File {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val acroForm = PDAcroForm(doc)
            doc.documentCatalog.acroForm = acroForm
            val res = com.tom_roush.pdfbox.pdmodel.PDResources()
            res.put(com.tom_roush.pdfbox.cos.COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
            acroForm.defaultResources = res
            acroForm.defaultAppearance = "/Helv 12 Tf 0 g"

            val textBox = PDTextField(acroForm).apply {
                partialName = "SampleField"
                defaultAppearance = "/Helv 12 Tf 0 g"
                value = "Interactive Text Value"
            }
            acroForm.fields.add(textBox)
            doc.save(file)
        }
        return file
    }

    /**
     * Creates a PDF with an oversized image (> 1200px) and a line of text
     * to verify downsampling and text preservation during compression.
     */
    fun createLargeImagePdf(file: File, width: Int = 1600, height: Int = 1600, text: String = "Text Content Intact"): File {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(Color.CYAN)
            val paint = android.graphics.Paint().apply {
                color = Color.BLACK
                strokeWidth = 10f
            }
            canvas.drawLine(0f, 0f, width.toFloat(), height.toFloat(), paint)

            val ximage = JPEGFactory.createFromImage(doc, bmp, 0.9f)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(ximage, 50f, 200f, 400f, 400f)
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                cs.newLineAtOffset(50f, 750f)
                cs.showText(text)
                cs.endText()
            }
            bmp.recycle()
            doc.save(file)
        }
        return file
    }

    /**
     * Creates a PDF with a centered solid colored block of exact known RGB.
     */
    fun createKnownColorPdf(file: File, r: Int, g: Int, b: Int): File {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(Color.rgb(r, g, b))

        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val ximage = JPEGFactory.createFromImage(doc, bmp, 1.0f)
            PDPageContentStream(doc, page).use { cs ->
                cs.drawImage(ximage, 100f, 300f, 200f, 200f)
            }
            doc.save(file)
        }
        bmp.recycle()
        return file
    }

    /**
     * Creates a PDF with a corrupted cross-reference (xref) table:
     * Overwrites xref byte offsets with garbage while keeping the object streams intact.
     * This forces parsers to execute recovery / xref rebuilding algorithms.
     */
    fun createCorruptedXrefPdf(file: File): File {
        val tempValid = File(file.parentFile, "temp_valid_for_corruption.pdf")
        createMultiPagePdf(tempValid, 2)
        val bytes = tempValid.readBytes()
        tempValid.delete()

        val text = String(bytes, Charsets.ISO_8859_1)
        val xrefIndex = text.lastIndexOf("xref")
        if (xrefIndex != -1 && xrefIndex + 20 < bytes.size) {
            // Corrupt the first xref entry offset
            for (i in (xrefIndex + 10) until minOf(xrefIndex + 30, bytes.size)) {
                bytes[i] = '0'.code.toByte()
            }
        }
        file.writeBytes(bytes)
        return file
    }

    /**
     * Creates a completely unrecoverable, non-PDF file.
     */
    fun createUnrecoverableFile(file: File): File {
        file.writeBytes("THIS_IS_NOT_A_PDF_FILE_RANDOM_CORRUPT_BYTES_123456789".toByteArray())
        return file
    }

    /**
     * Creates a text-heavy PDF with dense paragraphs of text on each page.
     */
    fun createDenseTextPdf(file: File, pageCount: Int = 10, linesPerPage: Int = 30): File {
        PDDocument().use { doc ->
            for (p in 1..pageCount) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                    cs.newLineAtOffset(50f, 780f)
                    cs.showText("Dense Text Document - Page $p")
                    cs.setFont(PDType1Font.HELVETICA, 10f)
                    var yOffset = -22f
                    for (line in 1..linesPerPage) {
                        cs.newLineAtOffset(0f, yOffset)
                        yOffset = -18f
                        cs.showText("Paragraph line $line: The quick brown fox jumps over the lazy dog repeatedly to simulate a realistic body of document text with high character density on page $p.")
                    }
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    /**
     * Creates a high-entropy PDF containing uncompressable random/noisy pixel data.
     */
    fun createHighEntropyPdf(file: File, pageCount: Int = 1, width: Int = 400, height: Int = 400): File {
        PDDocument().use { doc ->
            val random = java.util.Random(42L)
            for (p in 1..pageCount) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                val pixels = IntArray(width * height)
                for (i in pixels.indices) {
                    pixels[i] = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                }
                val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                val ximage = JPEGFactory.createFromImage(doc, bmp, 0.95f)
                bmp.recycle()
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(ximage, 50f, 250f, 400f, 400f)
                }
            }
            doc.save(file)
        }
        return file
    }

    /**
     * Creates a mixed PDF containing text, vector path drawings, and an embedded image on each page.
     */
    fun createMixedPdf(file: File, pageCount: Int = 5): File {
        PDDocument().use { doc ->
            val bmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(Color.BLUE)
            val ximage = JPEGFactory.createFromImage(doc, bmp, 0.85f)
            bmp.recycle()

            for (p in 1..pageCount) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    // 1. Text
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA_BOLD, 16f)
                    cs.newLineAtOffset(50f, 750f)
                    cs.showText("Mixed Page $p Header")
                    cs.endText()

                    // 2. Vector line drawing
                    cs.setStrokingColor(255, 0, 0)
                    cs.setLineWidth(3f)
                    cs.moveTo(50f, 720f)
                    cs.lineTo(540f, 720f)
                    cs.stroke()

                    // 3. Embedded Image
                    cs.drawImage(ximage, 50f, 550f, 120f, 120f)
                }
            }
            doc.save(file)
        }
        return file
    }
}
