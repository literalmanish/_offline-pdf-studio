package com.offlinepdf.studio.core.engine.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.offlinepdf.studio.core.engine.PdfEngine
import com.offlinepdf.studio.core.engine.model.CompressionLevel
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.engine.model.ProcessingProgress
import com.offlinepdf.studio.core.util.FileStorageManager
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext

class PdfBoxEngineImpl(
    private val context: Context,
    private val storageManager: FileStorageManager = FileStorageManager(context)
) : PdfEngine {

    override suspend fun getPageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        val tempFile = storageManager.copyToTempFile(uri, "count_")
        try {
            PDDocument.load(tempFile).use { doc ->
                doc.numberOfPages
            }
        } finally {
            tempFile.delete()
        }
    }

    override suspend fun merge(
        sourceUris: List<Uri>,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        if (sourceUris.isEmpty()) {
            return@withContext EngineResult.Error(
                IllegalArgumentException("No files selected for merge"),
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
                onProgress(
                    ProcessingProgress(
                        current = index + 1,
                        total = total,
                        message = "Preparing document ${index + 1} of $total..."
                    )
                )
                val tempFile = storageManager.copyToTempFile(uri, "merge_src_${index}_")
                tempSources.add(tempFile)
                merger.addSource(tempFile)
            }

            onProgress(
                ProcessingProgress(
                    current = total,
                    total = total,
                    message = "Merging documents into single file..."
                )
            )

            // Use temp file memory buffer to prevent OutOfMemory on low-end devices
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())

            EngineResult.Success(outputFile)
        } catch (e: Exception) {
            EngineResult.Error(e, "Merge failed: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            tempSources.forEach { it.delete() }
        }
    }

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
                    onProgress(
                        ProcessingProgress(
                            current = partIndex + 1,
                            total = totalParts,
                            message = "Extracting part ${partIndex + 1} of $totalParts..."
                        )
                    )

                    PDDocument().use { targetDoc ->
                        for (pageNumber in range) {
                            coroutineContext.ensureActive()
                            val pageIndex = pageNumber - 1 // 1-based to 0-based
                            if (pageIndex in 0 until totalPages) {
                                val page = sourceDoc.getPage(pageIndex)
                                targetDoc.importPage(page)
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

            if (outputFiles.isEmpty()) {
                EngineResult.Error(
                    IllegalStateException("No valid pages found in specified ranges"),
                    "Could not extract pages from the requested range."
                )
            } else {
                EngineResult.Success(outputFiles)
            }
        } catch (e: Exception) {
            outputFiles.forEach { it.delete() }
            EngineResult.Error(e, "Split failed: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            sourceTemp.delete()
        }
    }

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
                    val totalPages = pageIndices.size

                    for ((step, originalIndex) in pageIndices.withIndex()) {
                        coroutineContext.ensureActive()
                        onProgress(
                            ProcessingProgress(
                                current = step + 1,
                                total = totalPages,
                                message = "Processing page ${step + 1} of $totalPages..."
                            )
                        )

                        if (originalIndex in 0 until sourceDoc.numberOfPages) {
                            val importedPage = targetDoc.importPage(sourceDoc.getPage(originalIndex))
                            val addedRotation = rotations[originalIndex] ?: 0
                            if (addedRotation != 0) {
                                importedPage.rotation = (importedPage.rotation + addedRotation) % 360
                            }
                        }
                    }

                    onProgress(
                        ProcessingProgress(
                            current = totalPages,
                            total = totalPages,
                            message = "Saving rearranged document..."
                        )
                    )
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

    override suspend fun compress(
        sourceUri: Uri,
        level: CompressionLevel,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val sourceTemp = storageManager.copyToTempFile(sourceUri, "compress_src_")
        val outputFile = storageManager.createOutputFile(outputBaseName)

        try {
            PDDocument.load(sourceTemp).use { doc ->
                val totalPages = doc.numberOfPages

                for (pageIndex in 0 until totalPages) {
                    coroutineContext.ensureActive()
                    onProgress(
                        ProcessingProgress(
                            current = pageIndex + 1,
                            total = totalPages,
                            message = "Optimizing page ${pageIndex + 1} of $totalPages..."
                        )
                    )

                    val page = doc.getPage(pageIndex)
                    optimizePageResources(doc, page, level)
                }

                onProgress(
                    ProcessingProgress(
                        current = totalPages,
                        total = totalPages,
                        message = "Writing compressed document..."
                    )
                )

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

    /**
     * Inspects XObjects on a page and downsamples high-res embedded bitmaps to achieve size reduction
     */
    private fun optimizePageResources(doc: PDDocument, page: PDPage, level: CompressionLevel) {
        val resources: PDResources = page.resources ?: return
        val xObjectNames = resources.xObjectNames ?: return

        for (name in xObjectNames) {
            val xObject = resources.getXObject(name)
            if (xObject is PDImageXObject) {
                try {
                    val originalBitmap = xObject.image ?: continue
                    val originalWidth = originalBitmap.width
                    val originalHeight = originalBitmap.height

                    // Only downsample if exceeds maximum configured dimension
                    if (originalWidth > level.maxDimension || originalHeight > level.maxDimension) {
                        val scale = minOf(
                            level.maxDimension.toFloat() / originalWidth.toFloat(),
                            level.maxDimension.toFloat() / originalHeight.toFloat()
                        )
                        val targetWidth = (originalWidth * scale).toInt().coerceAtLeast(1)
                        val targetHeight = (originalHeight * scale).toInt().coerceAtLeast(1)

                        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)
                        val qualityFraction = level.imageQuality / 100f

                        val compressedImageXObject = JPEGFactory.createFromImage(doc, scaledBitmap, qualityFraction)
                        resources.put(name, compressedImageXObject)

                        if (scaledBitmap != originalBitmap) {
                            scaledBitmap.recycle()
                        }
                    }
                } catch (_: Exception) {
                    // If an image cannot be parsed or downsampled, keep original without failing whole document
                }
            }
        }
    }
}
