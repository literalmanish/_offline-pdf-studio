package com.offlinepdf.studio.core.engine.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.offlinepdf.studio.core.engine.PdfEngine
import com.offlinepdf.studio.core.engine.model.*
import com.offlinepdf.studio.core.util.FileStorageManager
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

class PdfBoxEngineImpl(
    private val context: Context,
    private val storageManager: FileStorageManager = FileStorageManager(context)
) : PdfEngine {

    override suspend fun getPageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(uri, "count_")
        try {
            PDDocument.load(temp).use { it.numberOfPages }
        } finally {
            temp.delete()
        }
    }

    // 1. Merge
    override suspend fun merge(
        sourceUris: List<Uri>,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        if (sourceUris.size < 2) {
            return@withContext EngineResult.Error(
                IllegalArgumentException("Merge requires at least 2 files"),
                "Please select at least 2 PDF documents to merge."
            )
        }
        val tempSources = mutableListOf<File>()
        try {
            val merger = PDFMergerUtility()
            val outputFile = storageManager.createOutputFile(outputBaseName)
            merger.destinationFileName = outputFile.absolutePath

            val total = sourceUris.size
            for ((index, uri) in sourceUris.withIndex()) {
                coroutineContext.ensureActive()
                onProgress(ProcessingProgress(index + 1, total, "Reading file ${index + 1} of $total..."))
                val tempFile = storageManager.copyToTempFile(uri, "merge_src_${index}_")
                tempSources.add(tempFile)
                merger.addSource(tempFile)
            }
            onProgress(ProcessingProgress(total, total, "Merging documents..."))
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Merge failed: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            tempSources.forEach { it.delete() }
        }
    }

    // 2. Split
    override suspend fun split(
        sourceUri: Uri,
        ranges: List<IntRange>,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<List<File>> = withContext(Dispatchers.IO) {
        val sourceTemp = storageManager.copyToTempFile(sourceUri, "split_src_")
        val outputFiles = mutableListOf<File>()
        try {
            PDDocument.load(sourceTemp).use { sourceDoc ->
                val totalPages = sourceDoc.numberOfPages
                val totalParts = ranges.size

                for ((partIndex, range) in ranges.withIndex()) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(partIndex + 1, totalParts, "Extracting part ${partIndex + 1} of $totalParts..."))

                    PDDocument().use { targetDoc ->
                        for (pageNumber in range) {
                            val pageIndex = pageNumber - 1
                            if (pageIndex in 0 until totalPages) {
                                targetDoc.importPage(sourceDoc.getPage(pageIndex))
                            }
                        }
                        if (targetDoc.numberOfPages > 0) {
                            val partFile = storageManager.createOutputFile("${outputBaseName}_part_${partIndex + 1}")
                            targetDoc.save(partFile)
                            outputFiles.add(partFile)
                        }
                    }
                }
            }
            EngineResult.Success(outputFiles)
        } catch (e: Exception) {
            outputFiles.forEach { it.delete() }
            EngineResult.Error(e, "Split failed: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            sourceTemp.delete()
        }
    }

    // 3. Reorder & Filter
    override suspend fun reorderAndFilter(
        sourceUri: Uri,
        pageIndices: List<Int>,
        rotations: Map<Int, Int>,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val sourceTemp = storageManager.copyToTempFile(sourceUri, "reorder_src_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(sourceTemp).use { sourceDoc ->
                PDDocument().use { targetDoc ->
                    val total = pageIndices.size
                    for ((step, origIdx) in pageIndices.withIndex()) {
                        coroutineContext.ensureActive()
                        onProgress(ProcessingProgress(step + 1, total, "Reordering page ${step + 1} of $total..."))
                        if (origIdx in 0 until sourceDoc.numberOfPages) {
                            val page = targetDoc.importPage(sourceDoc.getPage(origIdx))
                            val extraRot = rotations[origIdx] ?: 0
                            if (extraRot != 0) {
                                page.rotation = (page.rotation + extraRot) % 360
                            }
                        }
                    }
                    targetDoc.save(outputFile)
                }
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            outputFile.delete()
            EngineResult.Error(e, "Reorder failed: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            sourceTemp.delete()
        }
    }

    // 4. Compress
    override suspend fun compress(
        sourceUri: Uri,
        level: CompressionLevel,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val sourceTemp = storageManager.copyToTempFile(sourceUri, "comp_src_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(sourceTemp).use { doc ->
                val total = doc.numberOfPages
                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Optimizing page ${i + 1} of $total..."))
                    val page = doc.getPage(i)
                    val resources = page.resources ?: continue
                    val xnames = resources.xObjectNames ?: continue
                    for (xname in xnames) {
                        val xobj = resources.getXObject(xname)
                        if (xobj is PDImageXObject) {
                            try {
                                val bmp = xobj.image ?: continue
                                if (bmp.width > level.maxDimension || bmp.height > level.maxDimension) {
                                    val scale = minOf(
                                        level.maxDimension.toFloat() / bmp.width.toFloat(),
                                        level.maxDimension.toFloat() / bmp.height.toFloat()
                                    )
                                    val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
                                    val newImg = JPEGFactory.createFromImage(doc, scaled, level.imageQuality / 100f)
                                    resources.put(xname, newImg)
                                    if (scaled != bmp) scaled.recycle()
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            outputFile.delete()
            EngineResult.Error(e, "Compression failed: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            sourceTemp.delete()
        }
    }

    // 5. Rotate Pages
    override suspend fun rotatePages(
        sourceUri: Uri,
        rotationDegrees: Int,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "rot_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { doc ->
                val total = doc.numberOfPages
                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Rotating page ${i + 1} of $total..."))
                    val page = doc.getPage(i)
                    page.rotation = (page.rotation + rotationDegrees) % 360
                }
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Rotation failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 6. Reverse Pages
    override suspend fun reversePages(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "rev_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { sourceDoc ->
                PDDocument().use { targetDoc ->
                    val total = sourceDoc.numberOfPages
                    for (i in (total - 1) downTo 0) {
                        coroutineContext.ensureActive()
                        onProgress(ProcessingProgress(total - i, total, "Reversing page ${total - i} of $total..."))
                        targetDoc.importPage(sourceDoc.getPage(i))
                    }
                    targetDoc.save(outputFile)
                }
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Reverse failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 7. Duplicate Pages
    override suspend fun duplicatePages(
        sourceUri: Uri,
        pageIndices: List<Int>,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "dup_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { sourceDoc ->
                PDDocument().use { targetDoc ->
                    val total = sourceDoc.numberOfPages
                    for (i in 0 until total) {
                        coroutineContext.ensureActive()
                        onProgress(ProcessingProgress(i + 1, total, "Processing page ${i + 1} of $total..."))
                        targetDoc.importPage(sourceDoc.getPage(i))
                        if (i in pageIndices) {
                            targetDoc.importPage(sourceDoc.getPage(i))
                        }
                    }
                    targetDoc.save(outputFile)
                }
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Duplicate failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 8. Insert Blank Pages
    override suspend fun insertBlankPages(
        sourceUri: Uri,
        positions: List<Int>,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "blank_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { sourceDoc ->
                PDDocument().use { targetDoc ->
                    val total = sourceDoc.numberOfPages
                    for (i in 0 until total) {
                        coroutineContext.ensureActive()
                        onProgress(ProcessingProgress(i + 1, total, "Processing page ${i + 1} of $total..."))
                        if ((i + 1) in positions) {
                            val blank = PDPage(sourceDoc.getPage(i).mediaBox ?: PDRectangle.A4)
                            targetDoc.addPage(blank)
                        }
                        targetDoc.importPage(sourceDoc.getPage(i))
                    }
                    targetDoc.save(outputFile)
                }
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Insert blank pages failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 9. Crop Pages
    override suspend fun cropPages(
        sourceUri: Uri,
        marginPoints: Float,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "crop_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { doc ->
                val total = doc.numberOfPages
                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Cropping page ${i + 1} of $total..."))
                    val page = doc.getPage(i)
                    val box = page.cropBox ?: page.mediaBox
                    val newBox = PDRectangle(
                        box.lowerLeftX + marginPoints,
                        box.lowerLeftY + marginPoints,
                        (box.width - marginPoints * 2).coerceAtLeast(50f),
                        (box.height - marginPoints * 2).coerceAtLeast(50f)
                    )
                    page.cropBox = newBox
                }
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Crop failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 10. N-Up (Pages per Sheet: 2-up)
    override suspend fun nUp(
        sourceUri: Uri,
        pagesPerSheet: Int,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "nup_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            // Render pages to bitmaps and place side-by-side or stacked on an A4 sheet
            val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val totalPages = renderer.pageCount

            PDDocument().use { targetDoc ->
                var i = 0
                while (i < totalPages) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, totalPages, "Creating N-Up sheet ${i / 2 + 1}..."))

                    val page1 = renderer.openPage(i)
                    val bmp1 = Bitmap.createBitmap(page1.width, page1.height, Bitmap.Config.ARGB_8888)
                    page1.render(bmp1, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page1.close()

                    var bmp2: Bitmap? = null
                    if (i + 1 < totalPages) {
                        val page2 = renderer.openPage(i + 1)
                        bmp2 = Bitmap.createBitmap(page2.width, page2.height, Bitmap.Config.ARGB_8888)
                        page2.render(bmp2, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page2.close()
                    }

                    // Combined sheet
                    val sheetWidth = PDRectangle.A4.width
                    val sheetHeight = PDRectangle.A4.height
                    val targetPage = PDPage(PDRectangle.A4)
                    targetDoc.addPage(targetPage)

                    val xobj1 = JPEGFactory.createFromImage(targetDoc, bmp1, 0.8f)
                    PDPageContentStream(targetDoc, targetPage).use { cs ->
                        val halfHeight = sheetHeight / 2f
                        cs.drawImage(xobj1, 36f, halfHeight + 10f, sheetWidth - 72f, halfHeight - 36f)
                        if (bmp2 != null) {
                            val xobj2 = JPEGFactory.createFromImage(targetDoc, bmp2, 0.8f)
                            cs.drawImage(xobj2, 36f, 20f, sheetWidth - 72f, halfHeight - 36f)
                            bmp2.recycle()
                        }
                    }
                    bmp1.recycle()
                    i += 2
                }
                renderer.close()
                pfd.close()
                targetDoc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "N-Up failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 11. Images to PDF
    override suspend fun imagesToPdf(
        imageUris: List<Uri>,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument().use { doc ->
                val total = imageUris.size
                for ((idx, uri) in imageUris.withIndex()) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(idx + 1, total, "Adding image ${idx + 1} of $total..."))
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream) ?: return@use
                        val pageRect = PDRectangle(bitmap.width.toFloat(), bitmap.height.toFloat())
                        val page = PDPage(pageRect)
                        doc.addPage(page)
                        val ximage = JPEGFactory.createFromImage(doc, bitmap, 0.85f)
                        PDPageContentStream(doc, page).use { cs ->
                            cs.drawImage(ximage, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                        }
                        bitmap.recycle()
                    }
                }
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Images to PDF failed: ${e.localizedMessage}")
        }
    }

    // 12. PDF to Images
    override suspend fun pdfToImages(
        sourceUri: Uri,
        format: String,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<List<File>> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "p2img_")
        val outputFiles = mutableListOf<File>()
        try {
            val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val total = renderer.pageCount
            for (i in 0 until total) {
                coroutineContext.ensureActive()
                onProgress(ProcessingProgress(i + 1, total, "Rendering page ${i + 1} of $total..."))
                val page = renderer.openPage(i)
                val width = page.width * 2
                val height = page.height * 2
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val ext = if (format.equals("JPEG", true) || format.equals("JPG", true)) "jpg" else "png"
                val compressFormat = if (ext == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                val imgFile = File(context.filesDir, "${outputBaseName}_page_${i + 1}.$ext")
                FileOutputStream(imgFile).use { out ->
                    bitmap.compress(compressFormat, 90, out)
                }
                bitmap.recycle()
                outputFiles.add(imgFile)
            }
            renderer.close()
            pfd.close()
            EngineResult.Success(outputFiles)
        } catch (e: Exception) {
            EngineResult.Error(e, "PDF to Images failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 13. Extract Embedded Images
    override suspend fun extractImages(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<List<File>> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "extimg_")
        val outputFiles = mutableListOf<File>()
        try {
            PDDocument.load(temp).use { doc ->
                val total = doc.numberOfPages
                var imgIndex = 1
                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Scanning page ${i + 1} of $total..."))
                    val page = doc.getPage(i)
                    val resources = page.resources ?: continue
                    val xnames = resources.xObjectNames ?: continue
                    for (xname in xnames) {
                        val xobj = resources.getXObject(xname)
                        if (xobj is PDImageXObject) {
                            val bmp = xobj.image ?: continue
                            val imgFile = File(context.filesDir, "${outputBaseName}_${imgIndex++}.png")
                            FileOutputStream(imgFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            outputFiles.add(imgFile)
                        }
                    }
                }
            }
            EngineResult.Success(outputFiles)
        } catch (e: Exception) {
            EngineResult.Error(e, "Image extraction failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 14. Extract Text
    override suspend fun extractText(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "txt_")
        val outputFile = File(context.filesDir, "${outputBaseName}.txt")
        try {
            PDDocument.load(temp).use { doc ->
                onProgress(ProcessingProgress(1, 1, "Extracting text..."))
                val stripper = PDFTextStripper()
                val text = stripper.getText(doc)
                outputFile.writeText(text)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Text extraction failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 15. Text to PDF
    override suspend fun textToPdf(
        text: String,
        title: String,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument().use { doc ->
                val lines = text.lines()
                val font = PDType1Font.HELVETICA
                val titleFont = PDType1Font.HELVETICA_BOLD
                val fontSize = 11f
                val lineHeight = 15f
                val margin = 50f
                val pageHeight = PDRectangle.A4.height
                val startY = pageHeight - margin

                var page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                var cs = PDPageContentStream(doc, page)
                var y = startY

                // Title
                cs.beginText()
                cs.setFont(titleFont, 16f)
                cs.newLineAtOffset(margin, y)
                cs.showText(title)
                cs.endText()
                y -= 30f

                cs.beginText()
                cs.setFont(font, fontSize)
                cs.newLineAtOffset(margin, y)

                for (line in lines) {
                    if (y < margin + 20f) {
                        cs.endText()
                        cs.close()
                        page = PDPage(PDRectangle.A4)
                        doc.addPage(page)
                        cs = PDPageContentStream(doc, page)
                        y = startY
                        cs.beginText()
                        cs.setFont(font, fontSize)
                        cs.newLineAtOffset(margin, y)
                    }
                    val safeLine = line.replace("\t", "    ").replace("[^\\x00-\\x7F]".toRegex(), "")
                    cs.showText(safeLine)
                    cs.newLineAtOffset(0f, -lineHeight)
                    y -= lineHeight
                }
                cs.endText()
                cs.close()
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Text to PDF failed: ${e.localizedMessage}")
        }
    }

    // 16. Protect / Encrypt PDF
    override suspend fun protectPdf(
        sourceUri: Uri,
        userPassword: String,
        ownerPassword: String,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "enc_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { doc ->
                onProgress(ProcessingProgress(1, 1, "Encrypting document..."))
                val ap = AccessPermission()
                val spp = StandardProtectionPolicy(ownerPassword, userPassword, ap)
                spp.encryptionKeyLength = 128
                spp.permissions = ap
                doc.protect(spp)
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Encryption failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 17. Unlock / Decrypt PDF
    override suspend fun unlockPdf(
        sourceUri: Uri,
        password: String,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "dec_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp, password).use { doc ->
                onProgress(ProcessingProgress(1, 1, "Removing password protection..."))
                doc.isAllSecurityToBeRemoved = true
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Unlock failed: Check if password is correct. (${e.localizedMessage})")
        } finally {
            temp.delete()
        }
    }

    // 18. Read Metadata
    override suspend fun getMetadata(sourceUri: Uri): DocumentMetadata = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "meta_")
        try {
            PDDocument.load(temp).use { doc ->
                val info = doc.documentInformation
                DocumentMetadata(
                    title = info?.title ?: "",
                    author = info?.author ?: "",
                    subject = info?.subject ?: "",
                    keywords = info?.keywords ?: "",
                    creator = info?.creator ?: ""
                )
            }
        } finally {
            temp.delete()
        }
    }

    // 19. Edit Metadata
    override suspend fun editMetadata(
        sourceUri: Uri,
        metadata: DocumentMetadata,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "meta_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { doc ->
                val info = doc.documentInformation ?: PDDocumentInformation()
                info.title = metadata.title
                info.author = metadata.author
                info.subject = metadata.subject
                info.keywords = metadata.keywords
                info.creator = metadata.creator
                doc.documentInformation = info
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Metadata update failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 20. Sanitize / Remove Metadata
    override suspend fun sanitizeMetadata(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "san_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { doc ->
                onProgress(ProcessingProgress(1, 1, "Sanitizing tracking information..."))
                doc.documentInformation = PDDocumentInformation()
                doc.documentCatalog.metadata = null
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Sanitize failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 21. Flatten PDF
    override suspend fun flattenPdf(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "flat_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { doc ->
                onProgress(ProcessingProgress(1, 1, "Flattening annotations & form fields..."))
                doc.documentCatalog.acroForm?.flatten()
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Flatten failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 22. Add Watermark
    override suspend fun addWatermark(
        sourceUri: Uri,
        text: String,
        position: WatermarkPosition,
        opacity: Float,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "wm_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { doc ->
                val font = PDType1Font.HELVETICA_BOLD
                val total = doc.numberOfPages

                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Watermarking page ${i + 1} of $total..."))
                    val page = doc.getPage(i)
                    val box = page.cropBox ?: page.mediaBox
                    val w = box.width
                    val h = box.height

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        val gs = PDExtendedGraphicsState()
                        gs.nonStrokingAlphaConstant = opacity
                        cs.setGraphicsStateParameters(gs)
                        cs.setNonStrokingColor(0.6f, 0.6f, 0.6f)

                        when (position) {
                            WatermarkPosition.CENTER_DIAGONAL -> {
                                cs.beginText()
                                cs.setFont(font, 48f)
                                cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(45.0), w / 4f, h / 4f))
                                cs.showText(text)
                                cs.endText()
                            }
                            WatermarkPosition.CENTER_HORIZONTAL -> {
                                cs.beginText()
                                cs.setFont(font, 36f)
                                cs.newLineAtOffset(w / 4f, h / 2f)
                                cs.showText(text)
                                cs.endText()
                            }
                            WatermarkPosition.TOP -> {
                                cs.beginText()
                                cs.setFont(font, 20f)
                                cs.newLineAtOffset(w / 4f, h - 40f)
                                cs.showText(text)
                                cs.endText()
                            }
                            WatermarkPosition.BOTTOM -> {
                                cs.beginText()
                                cs.setFont(font, 20f)
                                cs.newLineAtOffset(w / 4f, 40f)
                                cs.showText(text)
                                cs.endText()
                            }
                        }
                    }
                }
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Watermark failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 23. Add Page Numbers
    override suspend fun addPageNumbers(
        sourceUri: Uri,
        position: PageNumberPosition,
        formatPattern: String,
        startPage: Int,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "num_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { doc ->
                val font = PDType1Font.HELVETICA
                val total = doc.numberOfPages

                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Numbering page ${i + 1} of $total..."))
                    val page = doc.getPage(i)
                    val box = page.cropBox ?: page.mediaBox
                    val pageNum = startPage + i
                    val text = formatPattern
                        .replace("{n}", pageNum.toString())
                        .replace("{total}", total.toString())

                    val x = when (position) {
                        PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.TOP_LEFT -> 40f
                        PageNumberPosition.BOTTOM_CENTER, PageNumberPosition.TOP_CENTER -> box.width / 2f - 30f
                        PageNumberPosition.BOTTOM_RIGHT, PageNumberPosition.TOP_RIGHT -> box.width - 80f
                    }
                    val y = when (position) {
                        PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.BOTTOM_CENTER, PageNumberPosition.BOTTOM_RIGHT -> 30f
                        PageNumberPosition.TOP_LEFT, PageNumberPosition.TOP_CENTER, PageNumberPosition.TOP_RIGHT -> box.height - 35f
                    }

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.beginText()
                        cs.setFont(font, 10f)
                        cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
                        cs.newLineAtOffset(x, y)
                        cs.showText(text)
                        cs.endText()
                    }
                }
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Page numbering failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 24. Header & Footer
    override suspend fun addHeaderFooter(
        sourceUri: Uri,
        headerText: String,
        footerText: String,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "hf_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { doc ->
                val font = PDType1Font.HELVETICA
                val total = doc.numberOfPages

                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Adding header/footer to page ${i + 1} of $total..."))
                    val page = doc.getPage(i)
                    val box = page.cropBox ?: page.mediaBox

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setFont(font, 9f)
                        cs.setNonStrokingColor(0.3f, 0.3f, 0.3f)

                        if (headerText.isNotBlank()) {
                            cs.beginText()
                            cs.newLineAtOffset(40f, box.height - 30f)
                            cs.showText(headerText)
                            cs.endText()
                        }
                        if (footerText.isNotBlank()) {
                            cs.beginText()
                            cs.newLineAtOffset(40f, 25f)
                            cs.showText(footerText)
                            cs.endText()
                        }
                    }
                }
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Header/Footer failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 25. Add Stamp
    override suspend fun addStamp(
        sourceUri: Uri,
        stamp: StandardStamp,
        customText: String?,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "stamp_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        val text = customText?.takeIf { it.isNotBlank() } ?: stamp.label
        try {
            PDDocument.load(temp).use { doc ->
                val font = PDType1Font.HELVETICA_BOLD
                val total = doc.numberOfPages

                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Applying stamp to page ${i + 1} of $total..."))
                    val page = doc.getPage(i)
                    val box = page.cropBox ?: page.mediaBox

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        val stampX = box.width - 170f
                        val stampY = box.height - 70f
                        val stampWidth = 140f
                        val stampHeight = 36f

                        // Draw stamp red/green rounded border
                        cs.setStrokingColor(0.85f, 0.1f, 0.1f)
                        cs.setLineWidth(2f)
                        cs.addRect(stampX, stampY, stampWidth, stampHeight)
                        cs.stroke()

                        cs.beginText()
                        cs.setFont(font, 14f)
                        cs.setNonStrokingColor(0.85f, 0.1f, 0.1f)
                        cs.newLineAtOffset(stampX + 12f, stampY + 11f)
                        cs.showText(text)
                        cs.endText()
                    }
                }
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Stamp failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 26. Sign PDF
    override suspend fun signPdf(
        sourceUri: Uri,
        signatureBitmap: Bitmap,
        pageIndex: Int,
        xRatio: Float,
        yRatio: Float,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "sign_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            PDDocument.load(temp).use { doc ->
                if (pageIndex in 0 until doc.numberOfPages) {
                    onProgress(ProcessingProgress(1, 1, "Embedding digital signature..."))
                    val page = doc.getPage(pageIndex)
                    val box = page.cropBox ?: page.mediaBox
                    val ximg = LosslessFactory.createFromImage(doc, signatureBitmap)
                    val targetW = 150f
                    val targetH = (signatureBitmap.height.toFloat() / signatureBitmap.width.toFloat()) * targetW

                    val posX = (box.width * xRatio).coerceIn(20f, box.width - targetW - 20f)
                    val posY = (box.height * yRatio).coerceIn(20f, box.height - targetH - 20f)

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.drawImage(ximg, posX, posY, targetW, targetH)
                    }
                }
                doc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Sign failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 27. Convert to Grayscale
    override suspend fun convertToGrayscale(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "gray_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val total = renderer.pageCount

            PDDocument().use { targetDoc ->
                val grayPaint = Paint().apply {
                    val matrix = ColorMatrix()
                    matrix.setSaturation(0f)
                    colorFilter = ColorMatrixColorFilter(matrix)
                }

                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Converting page ${i + 1} of $total to grayscale..."))
                    val page = renderer.openPage(i)
                    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    val grayBmp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(grayBmp)
                    canvas.drawBitmap(bmp, 0f, 0f, grayPaint)
                    bmp.recycle()

                    val targetPage = PDPage(PDRectangle(grayBmp.width.toFloat(), grayBmp.height.toFloat()))
                    targetDoc.addPage(targetPage)
                    val ximg = JPEGFactory.createFromImage(targetDoc, grayBmp, 0.8f)
                    PDPageContentStream(targetDoc, targetPage).use { cs ->
                        cs.drawImage(ximg, 0f, 0f, grayBmp.width.toFloat(), grayBmp.height.toFloat())
                    }
                    grayBmp.recycle()
                }
                renderer.close()
                pfd.close()
                targetDoc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Grayscale conversion failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 28. Invert Colors (Night Mode)
    override suspend fun invertColors(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "inv_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val total = renderer.pageCount

            PDDocument().use { targetDoc ->
                val invertPaint = Paint().apply {
                    val invertMatrix = ColorMatrix(
                        floatArrayOf(
                            -1.0f,  0.0f,  0.0f, 0.0f, 255.0f,
                             0.0f, -1.0f,  0.0f, 0.0f, 255.0f,
                             0.0f,  0.0f, -1.0f, 0.0f, 255.0f,
                             0.0f,  0.0f,  0.0f, 1.0f,   0.0f
                        )
                    )
                    colorFilter = ColorMatrixColorFilter(invertMatrix)
                }

                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Inverting colors for page ${i + 1} of $total..."))
                    val page = renderer.openPage(i)
                    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    val invBmp = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(invBmp)
                    canvas.drawBitmap(bmp, 0f, 0f, invertPaint)
                    bmp.recycle()

                    val targetPage = PDPage(PDRectangle(invBmp.width.toFloat(), invBmp.height.toFloat()))
                    targetDoc.addPage(targetPage)
                    val ximg = JPEGFactory.createFromImage(targetDoc, invBmp, 0.8f)
                    PDPageContentStream(targetDoc, targetPage).use { cs ->
                        cs.drawImage(ximg, 0f, 0f, invBmp.width.toFloat(), invBmp.height.toFloat())
                    }
                    invBmp.recycle()
                }
                renderer.close()
                pfd.close()
                targetDoc.save(outputFile)
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Invert colors failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 29. Repair PDF
    override suspend fun repairPdf(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "rep_")
        val outputFile = storageManager.createOutputFile(outputBaseName)
        try {
            onProgress(ProcessingProgress(1, 1, "Rebuilding PDF cross-reference tables..."))
            PDDocument.load(temp).use { doc ->
                PDDocument().use { cleanDoc ->
                    for (i in 0 until doc.numberOfPages) {
                        cleanDoc.importPage(doc.getPage(i))
                    }
                    cleanDoc.save(outputFile)
                }
            }
            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Repair failed: ${e.localizedMessage}")
        } finally {
            temp.delete()
        }
    }

    // 30. Extract Bookmarks
    override suspend fun extractBookmarks(sourceUri: Uri): List<String> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "bm_")
        val results = mutableListOf<String>()
        try {
            PDDocument.load(temp).use { doc ->
                val outline = doc.documentCatalog.documentOutline ?: return@use
                var item = outline.firstChild
                while (item != null) {
                    results.add(item.title)
                    item = item.nextSibling
                }
            }
        } catch (_: Exception) {}
        finally {
            temp.delete()
        }
        results
    }

    // 31. Compare PDFs
    override suspend fun comparePdfs(uriA: Uri, uriB: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val countA = getPageCount(uriA)
        val countB = getPageCount(uriB)
        Pair(countA, countB)
    }
}
