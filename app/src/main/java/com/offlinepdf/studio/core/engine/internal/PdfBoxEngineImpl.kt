package com.offlinepdf.studio.core.engine.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.offlinepdf.studio.core.engine.PdfEngine
import com.offlinepdf.studio.core.engine.model.*
import com.offlinepdf.studio.core.util.FileStorageManager
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSDocument
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import android.app.ActivityManager
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.coroutines.coroutineContext

class PdfBoxEngineImpl(
    private val context: Context,
    private val storageManager: FileStorageManager = FileStorageManager(context),
    internal val fileDeleter: (File) -> Boolean = { it.delete() },
    internal val publicationVerifier: (destination: File, expectedLength: Long) -> Boolean = { dest, len ->
        dest.exists() && dest.length() == len && dest.length() > 0L
    },
    internal val afterLinkAction: suspend (stage: File, dest: File) -> Unit = { _, _ -> },
    internal val publishOperation: (source: Path, target: Path) -> Path = { source, target ->
        Files.move(source, target)
    },
    internal val cleanupWarningLogger: (String) -> Unit = { message -> Log.w("PdfBoxEngineImpl", message) },
    internal val prePublishAction: (stage: File) -> Unit = {}
) : PdfEngine {

    // Backwards-compatible alias for linkOperation
    internal val linkOperation: (Path, Path) -> Path
        get() = publishOperation

    companion object {
        internal val publicationLock = Any()
        internal val redactionJobMutex = Mutex()
        internal val activeRedactionJobs = java.util.concurrent.atomic.AtomicInteger(0)

        internal const val JAVA_BYTES_PER_PIXEL = 16L
        internal const val PDFBOX_WORKING_BYTES = 16L * 1024L * 1024L // 16 MiB
        internal const val PDFBOX_MAIN_MEMORY_SCRATCH_BYTES = 4L * 1024L * 1024L // 4 MiB main-memory scratch per PDDocument
        internal const val PDFBOX_TOTAL_SCRATCH_STORAGE_BYTES = 64L * 1024L * 1024L // 64 MiB total scratch storage per PDDocument (in-memory + disk-backed)
        internal const val NATIVE_BYTES_PER_PIXEL = 4L
        internal const val JAVA_SAFETY_RESERVE = 16L * 1024L * 1024L // 16 MiB safety reserve for GC and other threads
        internal const val NATIVE_SAFETY_RESERVE = 32L * 1024L * 1024L // 32 MiB native reserve

        /**
         * Conservative engineering admission estimate of Java heap bytes required for lossless rasterization.
         *
         * Allocation model breakdown:
         * - Raw RGB byte array: 3.0 B/px (allocated in LosslessFactory.createFromRGBImage)
         * - ByteArrayOutputStream initial buffer: 1.5 B/px (rawBytes.length / 2)
         * - First BAOS doubling: 3.0 B/px (triggered when compressed output exceeds 1.5 B/px)
         * - Second BAOS doubling: 6.0 B/px (triggered when compressed output exceeds 3.0 B/px due to Deflate framing/expansion)
         *   During second resize copy: old 3.0 B/px + new 6.0 B/px live simultaneously = 9.0 B/px
         *   Combined live memory during second resize: 3.0 (RGB) + 9.0 (BAOS) = 12.0 B/px
         * - toByteArray() duplicate compressed array copy: 3.0 B/px (in LosslessFactory.prepareImageXObject)
         * - COSStream / ScratchFile in-memory page buffering: 3.0 B/px (in PDImageXObject.createRawStream)
         * - Engineering allowance for small chunk buffers (e.g. 4KB IOUtils copy buffer), Deflater stream structures,
         *   and alignment padding: 1.0 B/px (note: this 1.0 B/px is an engineering allowance, not a measured allocation).
         * Total: 16.0 B/px conservative admission estimate.
         *
         * Separate Fixed Allowances:
         * - PDFBOX_WORKING_BYTES (16 MiB): Fixed baseline working allowance for PDDocument object graph, page tree,
         *   COS objects, and streams. Note that while ScratchFile bounds in-memory stream pages (up to 4 MiB per PDDocument),
         *   the PDF COS object graph, parser structures, fonts, and resource caches remain allocated directly on the Java heap
         *   outside ScratchFile limits; this 16 MiB allowance is an engineering estimate, not an absolute guarantee for arbitrary document syntax complexity.
         * - JAVA_SAFETY_RESERVE (16 MiB): Separate runtime safety reserve maintained for GC headroom and concurrent thread activity.
         *
         * Important: This admission formula is a conservative engineering estimate used for admission control
         * and adaptive downscaling; it does not constitute an absolute mathematical guarantee against every possible OOM
         * condition under extreme heap fragmentation or driver-level memory pressure.
         */
        internal fun calculateRequiredJavaBytes(width: Int, height: Int): Long {
            if (width <= 0 || height <= 0) return PDFBOX_WORKING_BYTES
            val w = width.toLong()
            val h = height.toLong()
            val maxPixels = (Long.MAX_VALUE - PDFBOX_WORKING_BYTES) / JAVA_BYTES_PER_PIXEL
            val pixels = try {
                Math.multiplyExact(w, h)
            } catch (_: ArithmeticException) {
                return Long.MAX_VALUE
            }
            if (pixels > maxPixels) return Long.MAX_VALUE
            return (pixels * JAVA_BYTES_PER_PIXEL) + PDFBOX_WORKING_BYTES
        }

        internal fun calculateRequiredBitmapBytes(width: Int, height: Int): Long {
            if (width <= 0 || height <= 0) return 0L
            val w = width.toLong()
            val h = height.toLong()
            val maxPixels = Long.MAX_VALUE / NATIVE_BYTES_PER_PIXEL
            val pixels = try {
                Math.multiplyExact(w, h)
            } catch (_: ArithmeticException) {
                return Long.MAX_VALUE
            }
            if (pixels > maxPixels) return Long.MAX_VALUE
            return pixels * NATIVE_BYTES_PER_PIXEL
        }
    }

    /**
     * Constructs a destination PDDocument bounded by the provided MemoryUsageSetting.
     * Uses ScratchFile(memSetting) directly and initializes the root catalog and pages tree
     * via supported public COSDocument / PDDocument constructors without silent fallbacks or private reflection.
     */
    internal fun createBoundedTargetDocument(memSetting: MemoryUsageSetting): PDDocument {
        val scratch = ScratchFile(memSetting)
        var cosDoc: COSDocument? = null
        var doc: PDDocument? = null
        try {
            cosDoc = COSDocument(scratch)
            val trailer = COSDictionary()
            cosDoc.trailer = trailer
            val root = COSDictionary()
            trailer.setItem(COSName.ROOT, root)
            root.setItem(COSName.TYPE, COSName.CATALOG)
            root.setItem(COSName.VERSION, COSName.getPDFName("1.4"))
            val pages = COSDictionary()
            root.setItem(COSName.PAGES, pages)
            pages.setItem(COSName.TYPE, COSName.PAGES)
            val kids = COSArray()
            pages.setItem(COSName.KIDS, kids)
            pages.setItem(COSName.COUNT, COSInteger.ZERO)

            doc = PDDocument(cosDoc)
            return doc
        } catch (t: Throwable) {
            if (doc != null) {
                try {
                    doc.close()
                } catch (cleanupEx: Throwable) {
                    t.addSuppressed(cleanupEx)
                    cleanupWarningLogger("Failed to close PDDocument during initialization abort: ${cleanupEx.message}")
                }
            } else if (cosDoc != null) {
                try {
                    cosDoc.close()
                } catch (cleanupEx: Throwable) {
                    t.addSuppressed(cleanupEx)
                    cleanupWarningLogger("Failed to close COSDocument during initialization abort: ${cleanupEx.message}")
                }
            } else {
                try {
                    scratch.close()
                } catch (cleanupEx: Throwable) {
                    t.addSuppressed(cleanupEx)
                    cleanupWarningLogger("Failed to close ScratchFile during initialization abort: ${cleanupEx.message}")
                }
            }
            throw t
        }
    }

    /**
     * Structurally validates the staging PDF before publication:
     * 1. File existence and non-zero length.
     * 2. PDF magic header "%PDF-".
     * 3. PDFBox parsing under bounded memory (non-encrypted, matching page count, non-null target page with bounding box).
     * 4. Android PdfRenderer verification (page count and valid dimensions).
     * 5. Actual low-resolution rendering of the target page to verify graphic integrity and uncorrupted content streams.
     *
     * Fails closed by throwing an IOException if any structural condition is violated,
     * ensuring invalid staging files are never published.
     */
    internal fun verifyStagedPdf(stage: File, expectedPages: Int, targetIndex: Int, memSetting: MemoryUsageSetting) {
        if (!stage.exists() || stage.length() == 0L) {
            throw IOException("Staged redaction PDF verification failed: file is missing or empty (${stage.absolutePath})")
        }
        FileInputStream(stage).use { fis ->
            val header = ByteArray(5)
            val read = fis.read(header)
            if (read < 5 || String(header, Charsets.US_ASCII) != "%PDF-") {
                throw IOException("Staged redaction PDF verification failed: invalid PDF header in ${stage.absolutePath}")
            }
        }
        PDDocument.load(stage, memSetting).use { testDoc ->
            if (testDoc.isEncrypted) {
                throw IOException("Staged redaction PDF verification failed: unexpected encryption in ${stage.absolutePath}")
            }
            if (testDoc.numberOfPages != expectedPages) {
                throw IOException("Staged redaction PDF verification failed: page count mismatch (expected $expectedPages, got ${testDoc.numberOfPages})")
            }
            val page = testDoc.getPage(targetIndex)
                ?: throw IOException("Staged redaction PDF verification failed: target page $targetIndex is null")
            if (page.mediaBox == null && page.cropBox == null) {
                throw IOException("Staged redaction PDF verification failed: target page $targetIndex has no bounding box")
            }
        }

        var pfdStaged: ParcelFileDescriptor? = null
        var rendererStaged: PdfRenderer? = null
        var testPage: PdfRenderer.Page? = null
        var verifyBmp: Bitmap? = null
        var primaryError: Throwable? = null
        try {
            pfdStaged = ParcelFileDescriptor.open(stage, ParcelFileDescriptor.MODE_READ_ONLY)
            rendererStaged = PdfRenderer(pfdStaged)
            if (rendererStaged.pageCount != expectedPages) {
                throw IOException("Staged redaction PDF verification failed: PdfRenderer page count mismatch (expected $expectedPages, got ${rendererStaged.pageCount})")
            }
            testPage = rendererStaged.openPage(targetIndex)
            if (testPage.width <= 0 || testPage.height <= 0) {
                throw IOException("Staged redaction PDF verification failed: target page has invalid dimensions (${testPage.width}x${testPage.height})")
            }

            // Low-resolution test render to verify actual graphic rasterizability and font/content stream integrity
            val renderW = (testPage.width / 8).coerceIn(16, 128)
            val renderH = (testPage.height / 8).coerceIn(16, 128)
            verifyBmp = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            testPage.render(verifyBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        } catch (t: Throwable) {
            primaryError = t
            throw t
        } finally {
            try {
                verifyBmp?.recycle()
            } catch (e: Throwable) {
                primaryError?.addSuppressed(e)
                cleanupWarningLogger("Error recycling verification bitmap: ${e.message}")
            } finally {
                verifyBmp = null
                try {
                    testPage?.close()
                } catch (e: Throwable) {
                    primaryError?.addSuppressed(e)
                    cleanupWarningLogger("Error closing testPage during verification: ${e.message}")
                } finally {
                    testPage = null
                    try {
                        rendererStaged?.close()
                    } catch (e: Throwable) {
                        primaryError?.addSuppressed(e)
                        cleanupWarningLogger("Error closing rendererStaged during verification: ${e.message}")
                    } finally {
                        rendererStaged = null
                        try {
                            pfdStaged?.close()
                        } catch (e: Throwable) {
                            primaryError?.addSuppressed(e)
                            cleanupWarningLogger("Error closing pfdStaged during verification: ${e.message}")
                        } finally {
                            pfdStaged = null
                        }
                    }
                }
            }
        }
    }

    /**
     * Shared publication implementation executing the move-based publication workflow:
     * 1. Acquires [publicationLock] first.
     * 2. Checks cancellation immediately before the move ([ensureActive] and [prePublishAction]).
     * 3. Probes candidates ([baseDestination], `_1.pdf`, `_2.pdf`...) without overwriting.
     * 4. Moves [staged] into candidate destination via [publishOperation].
     * 5. Establishes the commit point upon successful move: destination is now owned and committed.
     * 6. Executes [afterLinkAction] (nonessential hook: errors logged as warnings without aborting).
     * 7. Cleans unpublished staging file if still present.
     * 8. Executes [publicationVerifier]:
     *    - If verifier passes: returns committed [File].
     *    - If verifier fails: logs warning and throws [PostPublicationVerificationException] carrying
     *      the committed file so callers preserve it and report the verification issue explicitly.
     */
    internal suspend fun publishStagedPdf(
        staged: File,
        baseDestination: File,
        expectedLength: Long,
        onStageTransferred: () -> Unit = {},
        onCommitted: (File) -> Unit = {}
    ): File {
        val parentDir = baseDestination.parentFile ?: baseDestination.absoluteFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            val created = parentDir.mkdirs()
            if (!created && !parentDir.exists()) {
                throw IOException("Failed to create destination directory: ${parentDir.absolutePath}")
            }
        }

        val baseName = baseDestination.nameWithoutExtension
        var destination: File? = null
        var attempt = 0
        val maxAttempts = 100
        var published = false

        try {
            synchronized(publicationLock) {
                // Check cancellation while waiting for publication lock or immediately before move
                coroutineContext.ensureActive()
                prePublishAction(staged)
                coroutineContext.ensureActive()

                while (attempt < maxAttempts && !published) {
                    val candidate = if (attempt == 0) {
                        baseDestination
                    } else {
                        File(parentDir, "${baseName}_$attempt.pdf")
                    }

                    try {
                        // Move-based, no-replace publication under single-process coordination (publicationLock + activeReservations).
                        // Files.move without REPLACE_EXISTING prevents accidental overwrite by throwing FileAlreadyExistsException on collision.
                        coroutineContext.ensureActive()
                        publishOperation(staged.toPath(), candidate.toPath())
                        destination = candidate
                        published = true
                        onCommitted(candidate)
                        if (!staged.exists()) {
                            onStageTransferred()
                        }
                    } catch (_: FileAlreadyExistsException) {
                        attempt++
                    } catch (e: UnsupportedOperationException) {
                        throw IOException("Publication unsupported on this filesystem: ${e.message}", e)
                    } catch (e: SecurityException) {
                        throw IOException("Security policy denied file publication: ${e.message}", e)
                    } catch (e: FileSystemException) {
                        throw IOException("Filesystem error during publication: ${e.message}", e)
                    }
                }
            }

            val committedDestination: File = destination ?: throw IOException(
                "Failed to publish PDF: could not reserve a non-colliding destination in ${parentDir?.absolutePath}"
            )

            // Post-publication hook for tests (nonessential: does not affect published document integrity)
            try {
                try {
                    afterLinkAction(staged, committedDestination)
                } catch (e: CancellationException) {
                    cleanupWarningLogger("Post-publication action cancelled after publication commit; committed output preserved: ${committedDestination.absolutePath}")
                } catch (e: Throwable) {
                    cleanupWarningLogger("Post-publication hook threw exception after publication commit (${committedDestination.absolutePath}): ${e.message}")
                }
            } finally {
                // Clean up staging file if still present (e.g. if custom publishOperation copied instead of moved)
                if (staged.exists()) {
                    var deleted = false
                    try {
                        deleted = fileDeleter(staged)
                    } catch (e: Exception) {
                        cleanupWarningLogger("Exception deleting staging file ${staged.absolutePath}: ${e.message}")
                    }
                    if (!deleted && staged.exists()) {
                        cleanupWarningLogger("Cleanup warning: failed to delete staging file ${staged.absolutePath}")
                    }
                }
            }

            // Verification of published destination (document integrity)
            var verifierException: Exception? = null
            val verifierPassed = try {
                publicationVerifier(committedDestination, expectedLength)
            } catch (e: CancellationException) {
                cleanupWarningLogger("Post-publication verification cancelled after publication commit; committed output preserved: ${committedDestination.absolutePath}")
                throw e
            } catch (e: Exception) {
                verifierException = e
                cleanupWarningLogger("Post-publication verifier threw exception: ${e.message}")
                false
            }

            if (!verifierPassed) {
                cleanupWarningLogger("Post-publication verification check failed for committed output: ${committedDestination.absolutePath}")
                throw PostPublicationVerificationException(
                    committedFile = committedDestination,
                    message = "Published destination PDF verification failed: ${committedDestination.absolutePath}",
                    cause = verifierException
                )
            }

            return committedDestination
        } finally {
            storageManager.releaseReservation(baseDestination)
            destination?.let { storageManager.releaseReservation(it) }
        }
    }

    /**
     * Executes a direct-write PDF operation with strictly enforced reservation lifecycle:
     * - Acquires output destination reservation.
     * - Protects against concurrent collision.
     * - Automatically cleans up temporary files and partial output files upon error or cancellation.
     * - Reliably releases in-flight reservation in finally block on every exit path.
     * - Propagates CancellationException.
     */
    private inline fun executeDirectPdfOperation(
        outputBaseName: String,
        operationName: String,
        tempFilesToClean: List<File?> = emptyList(),
        additionalCleanup: () -> Unit = {},
        block: (outputFile: File) -> Unit
    ): EngineResult<File> {
        val outputFile = storageManager.createOutputFile(outputBaseName)
        var isSuccess = false
        try {
            block(outputFile)
            isSuccess = true
            return EngineResult.Success(outputFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return EngineResult.Error(e, "$operationName failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            storageManager.releaseReservation(outputFile)
            if (!isSuccess && outputFile.exists()) {
                outputFile.delete()
            }
            tempFilesToClean.forEach { it?.delete() }
            try { additionalCleanup() } catch (_: Throwable) {}
        }
    }

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
        executeDirectPdfOperation(
            outputBaseName = outputBaseName,
            operationName = "Merge",
            additionalCleanup = { tempSources.forEach { it.delete() } }
        ) { outputFile ->
            val merger = PDFMergerUtility()
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
        var isSuccess = false
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
            isSuccess = true
            EngineResult.Success(outputFiles)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EngineResult.Error(e, "Split failed: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            outputFiles.forEach { storageManager.releaseReservation(it) }
            if (!isSuccess) {
                outputFiles.forEach { it.delete() }
            }
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
        executeDirectPdfOperation(outputBaseName, "Reorder", listOf(sourceTemp)) { outputFile ->
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
        executeDirectPdfOperation(outputBaseName, "Compression", listOf(sourceTemp)) { outputFile ->
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
        executeDirectPdfOperation(outputBaseName, "Rotation", listOf(temp)) { outputFile ->
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
        }
    }

    // 6. Reverse Pages
    override suspend fun reversePages(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "rev_")
        executeDirectPdfOperation(outputBaseName, "Reverse", listOf(temp)) { outputFile ->
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
        executeDirectPdfOperation(outputBaseName, "Duplicate", listOf(temp)) { outputFile ->
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
        executeDirectPdfOperation(outputBaseName, "Insert blank pages", listOf(temp)) { outputFile ->
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
        executeDirectPdfOperation(outputBaseName, "Crop", listOf(temp)) { outputFile ->
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
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        executeDirectPdfOperation(
            outputBaseName = outputBaseName,
            operationName = "N-Up",
            tempFilesToClean = listOf(temp),
            additionalCleanup = {
                try { renderer?.close() } catch (_: Throwable) {}
                try { pfd?.close() } catch (_: Throwable) {}
            }
        ) { outputFile ->
            pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
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
                targetDoc.save(outputFile)
            }
        }
    }

    // 11. Images to PDF
    override suspend fun imagesToPdf(
        imageUris: List<Uri>,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        executeDirectPdfOperation(outputBaseName, "Images to PDF") { outputFile ->
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
        var prefix: String? = null
        var isSuccess = false
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            prefix = storageManager.reserveExportPrefix(outputBaseName, targetDir = context.filesDir)
            pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val total = renderer.pageCount
            val ext = if (format.equals("JPEG", true) || format.equals("JPG", true)) "jpg" else "png"
            val compressFormat = if (ext == "jpg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            for (i in 0 until total) {
                coroutineContext.ensureActive()
                onProgress(ProcessingProgress(i + 1, total, "Rendering page ${i + 1} of $total..."))
                val page = renderer.openPage(i)
                val maxDim = 2560
                val scale = 2.0f.coerceAtMost(maxDim.toFloat() / maxOf(page.width, page.height).toFloat())
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val imgFile = File(context.filesDir, "${prefix}_page_${i + 1}.$ext")
                FileOutputStream(imgFile).use { out ->
                    bitmap.compress(compressFormat, 90, out)
                }
                bitmap.recycle()
                outputFiles.add(imgFile)
            }
            isSuccess = true
            EngineResult.Success(outputFiles)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            EngineResult.Error(Exception(t), "PDF to Images failed: ${t.localizedMessage ?: "Memory or rendering error"}")
        } finally {
            prefix?.let { storageManager.releaseExportPrefix(it, targetDir = context.filesDir) }
            if (!isSuccess) {
                outputFiles.forEach { it.delete() }
            }
            try { renderer?.close() } catch (_: Throwable) {}
            try { pfd?.close() } catch (_: Throwable) {}
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
        var prefix: String? = null
        var isSuccess = false
        try {
            prefix = storageManager.reserveExportPrefix(outputBaseName, targetDir = context.filesDir)
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
                            val imgFile = File(context.filesDir, "${prefix}_${imgIndex++}.png")
                            FileOutputStream(imgFile).use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            outputFiles.add(imgFile)
                        }
                    }
                }
            }
            isSuccess = true
            EngineResult.Success(outputFiles)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EngineResult.Error(e, "Image extraction failed: ${e.localizedMessage}")
        } finally {
            prefix?.let { storageManager.releaseExportPrefix(it, targetDir = context.filesDir) }
            if (!isSuccess) {
                outputFiles.forEach { it.delete() }
            }
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
        var outputFile: File? = null
        var isSuccess = false
        try {
            outputFile = storageManager.createOutputFile(outputBaseName, extension = "txt", targetDir = context.filesDir)
            PDDocument.load(temp).use { doc ->
                onProgress(ProcessingProgress(1, 1, "Extracting text..."))
                val stripper = PDFTextStripper()
                outputFile.bufferedWriter().use { writer ->
                    stripper.writeText(doc, writer)
                }
            }
            isSuccess = true
            EngineResult.Success(outputFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EngineResult.Error(e, "Text extraction failed: ${e.localizedMessage}")
        } finally {
            outputFile?.let { storageManager.releaseReservation(it) }
            if (!isSuccess && outputFile?.exists() == true) {
                outputFile.delete()
            }
            temp.delete()
        }
    }

    // 15. Text to PDF with Full Native Unicode & Devanagari Complex Text Shaping
    override suspend fun textToPdf(
        text: String,
        title: String,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("txt2pdf_", ".tmp", context.cacheDir)
        var stagingFile: File? = null
        var baseDestinationFile: File? = null
        val pdfDocument = android.graphics.pdf.PdfDocument()
        var activePage: android.graphics.pdf.PdfDocument.Page? = null
        var isPdfClosed = false
        var isSuccess = false
        var caughtThrowable: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()

        val safeDelete: (File?, Boolean) -> Boolean = { file, isRequired ->
            if (file == null || !file.exists()) {
                true
            } else {
                try {
                    val deleted = fileDeleter(file)
                    if (!deleted && file.exists()) {
                        val ex = IOException("File.delete() returned false for: ${file.absolutePath}")
                        cleanupFailures.add(ex)
                        caughtThrowable?.addSuppressed(ex)
                        cleanupWarningLogger("Cleanup warning: ${ex.message}")
                        false
                    } else {
                        true
                    }
                } catch (e: Exception) {
                    cleanupFailures.add(e)
                    caughtThrowable?.addSuppressed(e)
                    cleanupWarningLogger("Cleanup exception: ${e.message}")
                    false
                }
            }
        }

        try {
            coroutineContext.ensureActive()
            onProgress(ProcessingProgress(1, 2, "Measuring and shaping Unicode text layout..."))

            // Standard ISO 216 A4 page dimensions in standard PDF points (72 pt/in)
            val pageWidth = 595
            val pageHeight = 842
            val margin = 48
            val printableWidth = pageWidth - (margin * 2)

            // High-legibility typography with complete Android font fallback chain (Roboto + Noto Sans Devanagari)
            val titlePaint = TextPaint().apply {
                isAntiAlias = true
                textSize = 17f
                color = Color.BLACK
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val bodyPaint = TextPaint().apply {
                isAntiAlias = true
                textSize = 11.5f
                color = Color.rgb(33, 33, 33)
                typeface = Typeface.DEFAULT
            }

            val cleanTitle = title.trim()
            val cleanText = text.replace("\t", "    ")

            // Build Title StaticLayout if title exists
            val titleLayout = if (cleanTitle.isNotBlank()) {
                StaticLayout.Builder.obtain(cleanTitle, 0, cleanTitle.length, titlePaint, printableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build()
            } else null

            // Build Body StaticLayout with HarfBuzz complex shaping, ligatures, and word wrapping
            val bodyLayout = if (cleanText.isNotEmpty()) {
                StaticLayout.Builder.obtain(cleanText, 0, cleanText.length, bodyPaint, printableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.15f)
                    .setIncludePad(true)
                    .build()
            } else null

            val totalBodyLines = bodyLayout?.lineCount ?: 0
            var currentBodyLine = 0
            var pageIndex = 0

            onProgress(ProcessingProgress(2, 2, "Generating vector PDF pages..."))

            while (currentBodyLine < totalBodyLines || pageIndex == 0) {
                coroutineContext.ensureActive()
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                activePage = page
                val canvas = page.canvas

                var availableY = margin.toFloat()

                // Render Title on first page only
                if (pageIndex == 0 && titleLayout != null) {
                    canvas.save()
                    canvas.translate(margin.toFloat(), availableY)
                    titleLayout.draw(canvas)
                    canvas.restore()
                    availableY += titleLayout.height + 18f
                }

                if (bodyLayout != null && currentBodyLine < totalBodyLines) {
                    val pageContentHeight = (pageHeight - margin).toFloat() - availableY
                    val startLineForPage = currentBodyLine
                    val startLineTop = bodyLayout.getLineTop(startLineForPage)

                    var endLineForPage = startLineForPage
                    while (endLineForPage < totalBodyLines) {
                        val currentHeight = bodyLayout.getLineBottom(endLineForPage) - startLineTop
                        if (currentHeight > pageContentHeight && endLineForPage > startLineForPage) {
                            break
                        }
                        endLineForPage++
                    }

                    canvas.save()
                    // Clip canvas strictly to printable bounds to prevent bleeding
                    canvas.clipRect(
                        margin.toFloat(),
                        availableY,
                        (pageWidth - margin).toFloat(),
                        (pageHeight - margin).toFloat()
                    )
                    canvas.translate(margin.toFloat(), availableY - startLineTop)
                    bodyLayout.draw(canvas)
                    canvas.restore()

                    currentBodyLine = endLineForPage
                }

                // Disassociate activePage BEFORE finishPage so it can never be finished twice
                val pageToFinish = activePage
                activePage = null
                pdfDocument.finishPage(pageToFinish)
                pageIndex++

                // Handle single-page empty document case
                if (bodyLayout == null || currentBodyLine >= totalBodyLines) {
                    break
                }
            }

            coroutineContext.ensureActive()

            // 1. Write the completed document to temporary file
            FileOutputStream(tempFile).use { out ->
                pdfDocument.writeTo(out)
            }

            // 2. Close PdfDocument completely. If close() throws, it is caught as a generation failure
            pdfDocument.close()
            isPdfClosed = true

            // 3. Verify the temporary file before publishing
            val tempLength = tempFile.length()
            if (!tempFile.exists() || tempLength == 0L) {
                throw IOException("PDF document was generated with 0 bytes: ${tempFile.absolutePath}")
            }

            // 4. Safe destination directory creation
            val baseDestination = storageManager.createOutputFile(outputBaseName)
            baseDestinationFile = baseDestination
            val parentDir = baseDestination.parentFile ?: baseDestination.absoluteFile.parentFile
            if (parentDir != null && !parentDir.exists()) {
                val created = parentDir.mkdirs()
                if (!created && !parentDir.exists()) {
                    throw IOException("Failed to create destination directory: ${parentDir.absolutePath}")
                }
            }

            // 5. Create a uniquely named staging file owned solely by this operation
            val stage = File.createTempFile(".stage_", ".tmp", parentDir)
            stagingFile = stage

            tempFile.copyTo(stage, overwrite = true)
            if (!stage.exists() || stage.length() != tempLength || stage.length() == 0L) {
                throw IOException("Staged PDF copy verification failed: ${stage.absolutePath}")
            }

            // 6. Collision-safe publication without race conditions using shared helper
            val verifiedDestination = publishStagedPdf(
                staged = stage,
                baseDestination = baseDestination,
                expectedLength = tempLength,
                onStageTransferred = { stagingFile = null }
            )

            // 7. Clean up temporary cache file (non-fatal warning if deletion fails)
            safeDelete(tempFile, false)

            isSuccess = true
            EngineResult.Success(verifiedDestination)
        } catch (e: CancellationException) {
            caughtThrowable = e
            throw e
        } catch (e: PostPublicationVerificationException) {
            caughtThrowable = e
            safeDelete(tempFile, false)
            EngineResult.Error(
                exception = e,
                userMessage = "PDF was created at ${e.committedFile.name}, but post-publication verification failed: ${e.localizedMessage ?: "Verification error"}",
                committedFile = e.committedFile
            )
        } catch (e: Exception) {
            caughtThrowable = e
            EngineResult.Error(e, "Text to PDF failed: ${e.localizedMessage ?: e.javaClass.simpleName}")
        } finally {
            // Clean up native PdfDocument resources if not closed successfully
            if (!isPdfClosed) {
                if (activePage != null) {
                    val unclosedPage = activePage
                    activePage = null
                    try {
                        pdfDocument.finishPage(unclosedPage)
                    } catch (e: Exception) {
                        cleanupFailures.add(e)
                        caughtThrowable?.addSuppressed(e)
                        cleanupWarningLogger("Error finishing active page during cleanup: ${e.message}")
                    }
                }
                try {
                    pdfDocument.close()
                } catch (e: Exception) {
                    cleanupFailures.add(e)
                    caughtThrowable?.addSuppressed(e)
                    cleanupWarningLogger("Error closing PdfDocument during cleanup: ${e.message}")
                }
            }

            // Clean up staging file if left behind due to a publication failure or if success-path deletion failed
            if (stagingFile != null && stagingFile?.exists() == true) {
                if (safeDelete(stagingFile, false)) {
                    stagingFile = null
                } else {
                    cleanupWarningLogger("Cleanup warning: Staging file could not be deleted: ${stagingFile?.absolutePath}")
                }
            }

            // Clean up temporary cache file
            if (tempFile.exists()) {
                if (!safeDelete(tempFile, false)) {
                    cleanupWarningLogger("Cleanup warning: Cache temp file could not be deleted: ${tempFile.absolutePath}")
                }
            }

            // Release reservation in case operation aborted before reaching publishStagedPdf
            baseDestinationFile?.let { storageManager.releaseReservation(it) }

            // Destination ownership note: Once published into the public documents directory,
            // deleting it by pathname in finally is unsafe because concurrent UI actions
            // (e.g. Clear Internal Documents in SettingsScreen or user deletion in FilesScreen) can remove
            // or replace pathnames outside publicationLock. Therefore, we never delete destination by pathname;
            // post-publication verification failures are reported via EngineResult.Error without risking deletion
            // of replacement files.
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
        executeDirectPdfOperation(outputBaseName, "Encryption", listOf(temp)) { outputFile ->
            PDDocument.load(temp).use { doc ->
                onProgress(ProcessingProgress(1, 1, "Encrypting document..."))
                val ap = AccessPermission()
                val spp = StandardProtectionPolicy(ownerPassword, userPassword, ap)
                spp.encryptionKeyLength = 128
                spp.permissions = ap
                doc.protect(spp)
                doc.save(outputFile)
            }
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
        executeDirectPdfOperation(outputBaseName, "Unlock", listOf(temp)) { outputFile ->
            PDDocument.load(temp, password).use { doc ->
                onProgress(ProcessingProgress(1, 1, "Removing password protection..."))
                doc.isAllSecurityToBeRemoved = true
                doc.save(outputFile)
            }
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
        executeDirectPdfOperation(outputBaseName, "Metadata update", listOf(temp)) { outputFile ->
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
        }
    }

    // 20. Sanitize / Remove Metadata
    override suspend fun sanitizeMetadata(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "san_")
        executeDirectPdfOperation(outputBaseName, "Sanitize", listOf(temp)) { outputFile ->
            PDDocument.load(temp).use { doc ->
                onProgress(ProcessingProgress(1, 1, "Sanitizing tracking information..."))
                doc.documentInformation = PDDocumentInformation()
                doc.documentCatalog.metadata = null
                doc.save(outputFile)
            }
        }
    }

    // 21. Flatten PDF
    override suspend fun flattenPdf(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "flat_")
        executeDirectPdfOperation(outputBaseName, "Flatten", listOf(temp)) { outputFile ->
            PDDocument.load(temp).use { doc ->
                onProgress(ProcessingProgress(1, 1, "Flattening annotations & form fields..."))
                doc.documentCatalog.acroForm?.flatten()
                doc.save(outputFile)
            }
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
        executeDirectPdfOperation(outputBaseName, "Watermark", listOf(temp)) { outputFile ->
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
        executeDirectPdfOperation(outputBaseName, "Page numbering", listOf(temp)) { outputFile ->
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
        executeDirectPdfOperation(outputBaseName, "Header/Footer", listOf(temp)) { outputFile ->
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
        val text = customText?.takeIf { it.isNotBlank() } ?: stamp.label
        executeDirectPdfOperation(outputBaseName, "Stamp", listOf(temp)) { outputFile ->
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
        executeDirectPdfOperation(outputBaseName, "Sign", listOf(temp)) { outputFile ->
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
        }
    }

    // 27. Convert to Grayscale
    override suspend fun convertToGrayscale(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "gray_")
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        executeDirectPdfOperation(
            outputBaseName = outputBaseName,
            operationName = "Grayscale conversion",
            tempFilesToClean = listOf(temp),
            additionalCleanup = {
                try { renderer?.close() } catch (_: Throwable) {}
                try { pfd?.close() } catch (_: Throwable) {}
            }
        ) { outputFile ->
            pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
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
                targetDoc.save(outputFile)
            }
        }
    }

    // 28. Invert Colors (Night Mode)
    override suspend fun invertColors(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "inv_")
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        executeDirectPdfOperation(
            outputBaseName = outputBaseName,
            operationName = "Invert colors",
            tempFilesToClean = listOf(temp),
            additionalCleanup = {
                try { renderer?.close() } catch (_: Throwable) {}
                try { pfd?.close() } catch (_: Throwable) {}
            }
        ) { outputFile ->
            pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
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
                targetDoc.save(outputFile)
            }
        }
    }

    // 29. Repair PDF
    override suspend fun repairPdf(
        sourceUri: Uri,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "rep_")
        executeDirectPdfOperation(outputBaseName, "Repair", listOf(temp)) { outputFile ->
            onProgress(ProcessingProgress(1, 1, "Rebuilding PDF cross-reference tables..."))
            PDDocument.load(temp).use { doc ->
                PDDocument().use { cleanDoc ->
                    for (i in 0 until doc.numberOfPages) {
                        cleanDoc.importPage(doc.getPage(i))
                    }
                    cleanDoc.save(outputFile)
                }
            }
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

    override suspend fun compareDocumentsDetailed(uriA: Uri, uriB: Uri): PdfComparisonReport = withContext(Dispatchers.IO) {
        val (nameA, sizeA) = storageManager.getUriMetadata(uriA)
        val (nameB, sizeB) = storageManager.getUriMetadata(uriB)

        var pageCountA = 0
        var versionA = "1.4"
        var titleA = "Untitled"
        var authorA = "Unknown"
        var isEncryptedA = false
        var textLenA = 0

        var pageCountB = 0
        var versionB = "1.4"
        var titleB = "Untitled"
        var authorB = "Unknown"
        var isEncryptedB = false
        var textLenB = 0

        val tempA = storageManager.copyToTempFile(uriA, "cmpA_")
        try {
            PDDocument.load(tempA).use { doc ->
                pageCountA = doc.numberOfPages
                versionA = doc.version.toString()
                isEncryptedA = doc.isEncrypted
                val info = doc.documentInformation
                if (info != null) {
                    titleA = info.title ?: "Untitled"
                    authorA = info.author ?: "Unknown"
                }
                val stripper = PDFTextStripper()
                stripper.endPage = 5.coerceAtMost(doc.numberOfPages)
                textLenA = stripper.getText(doc).length
            }
        } catch (_: Throwable) {}
        finally { tempA.delete() }

        val tempB = storageManager.copyToTempFile(uriB, "cmpB_")
        try {
            PDDocument.load(tempB).use { doc ->
                pageCountB = doc.numberOfPages
                versionB = doc.version.toString()
                isEncryptedB = doc.isEncrypted
                val info = doc.documentInformation
                if (info != null) {
                    titleB = info.title ?: "Untitled"
                    authorB = info.author ?: "Unknown"
                }
                val stripper = PDFTextStripper()
                stripper.endPage = 5.coerceAtMost(doc.numberOfPages)
                textLenB = stripper.getText(doc).length
            }
        } catch (_: Throwable) {}
        finally { tempB.delete() }

        PdfComparisonReport(
            nameA = nameA,
            nameB = nameB,
            sizeA = sizeA,
            sizeB = sizeB,
            pageCountA = pageCountA,
            pageCountB = pageCountB,
            versionA = versionA,
            versionB = versionB,
            titleA = titleA,
            titleB = titleB,
            authorA = authorA,
            authorB = authorB,
            isEncryptedA = isEncryptedA,
            isEncryptedB = isEncryptedB,
            textLengthA = textLenA,
            textLengthB = textLenB
        )
    }

    // 32. Redact Area (Irreversible Sanitization & Blackout of Sensitive Information)
    override suspend fun redactArea(
        sourceUri: Uri,
        pageIndex: Int,
        xRatio: Float,
        yRatio: Float,
        widthRatio: Float,
        heightRatio: Float,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        // Enforce process-wide serialization for heavy bitmap rasterization & lossless Flate encoding
        redactionJobMutex.withLock {
            activeRedactionJobs.incrementAndGet()
            try {
                var temp: File? = null
                var stagingFile: File? = null
                var baseDestinationFile: File? = null
                var verifiedDestination: File? = null
                var pfd: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null
                var rPage: PdfRenderer.Page? = null
                var redactedBmp: Bitmap? = null
                try {
                    coroutineContext.ensureActive()
                    temp = storageManager.copyToTempFile(sourceUri, "redact_")

                    onProgress(ProcessingProgress(1, 2, "Rasterizing and sanitizing sensitive page..."))
                    pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(pfd)
                    val totalPages = renderer.pageCount
                    val targetIndex = pageIndex.coerceIn(0, (totalPages - 1).coerceAtLeast(0))

                    rPage = renderer.openPage(targetIndex)
                    val displayWidth = rPage.width.toFloat()
                    val displayHeight = rPage.height.toFloat()
                    val pageMaxDim = maxOf(rPage.width, rPage.height).toFloat()

                    val targetDpi = 200f
                    val baseScale = targetDpi / 72f
                    val maxDimension = 2880f
                    val maxPixelBudget = 6_291_456L // ~6.0 MP, <= 24 MB ARGB_8888 bitmap allocation

                    var renderScale = baseScale.coerceAtMost(maxDimension / pageMaxDim)
                    var bmpW = (rPage.width * renderScale).toInt().coerceAtLeast(1)
                    var bmpH = (rPage.height * renderScale).toInt().coerceAtLeast(1)

                    val currentPixels = bmpW.toLong() * bmpH.toLong()
                    if (currentPixels > maxPixelBudget) {
                        val factor = kotlin.math.sqrt(maxPixelBudget.toDouble() / currentPixels.toDouble()).toFloat()
                        renderScale *= factor
                        bmpW = (rPage.width * renderScale).toInt().coerceAtLeast(1)
                        bmpH = (rPage.height * renderScale).toInt().coerceAtLeast(1)
                    }

                    // Memory admission check: distinguish native memory from Java heap memory
                    var requiredBitmapBytes = calculateRequiredBitmapBytes(bmpW, bmpH)
                    var requiredJavaBytes = calculateRequiredJavaBytes(bmpW, bmpH)
                    val javaSafetyReserve = JAVA_SAFETY_RESERVE
                    val nativeSafetyReserve = NATIVE_SAFETY_RESERVE

                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager?.getMemoryInfo(memInfo)

                    val runtime = Runtime.getRuntime()
                    var availableJavaHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())

                    // Diagnostics: inspect actual process native heap, PSS, and system pressure
                    val nativeAllocated = Debug.getNativeHeapAllocatedSize()
                    val nativeSize = Debug.getNativeHeapSize()
                    val nativeFree = Debug.getNativeHeapFreeSize()
                    val pssKb = Debug.getPss()
                    Log.d("PdfBoxEngineImpl", "Memory admission probe: Java avail=${availableJavaHeap / (1024 * 1024)} MiB, " +
                            "Native alloc=${nativeAllocated / (1024 * 1024)} MiB, Native size=${nativeSize / (1024 * 1024)} MiB, " +
                            "Native free=${nativeFree / (1024 * 1024)} MiB, Process PSS=${pssKb / 1024} MiB, " +
                            "System avail=${memInfo.availMem / (1024 * 1024)} MiB, lowMem=${memInfo.lowMemory}")

                    var isNativeConstrained = memInfo.lowMemory || (memInfo.availMem < memInfo.threshold + requiredBitmapBytes + nativeSafetyReserve)
                    var isJavaConstrained = (availableJavaHeap - requiredJavaBytes) < javaSafetyReserve

                    // If constrained, adaptively downscale scale towards 1.0x (72 DPI)
                    if ((isNativeConstrained || isJavaConstrained) && renderScale > 1.0f) {
                        val safePixelsJava = ((availableJavaHeap - javaSafetyReserve - PDFBOX_WORKING_BYTES).coerceAtLeast(0L) / JAVA_BYTES_PER_PIXEL)
                        val maxSafePixels = safePixelsJava.coerceAtMost(maxPixelBudget)
                        if (maxSafePixels > 0) {
                            val pageBasePixels = (displayWidth * displayHeight).toDouble()
                            val computedScale = kotlin.math.sqrt(maxSafePixels.toDouble() / pageBasePixels).toFloat().coerceIn(1.0f, renderScale)
                            renderScale = computedScale
                            bmpW = (rPage.width * renderScale).toInt().coerceAtLeast(1)
                            bmpH = (rPage.height * renderScale).toInt().coerceAtLeast(1)
                            requiredBitmapBytes = calculateRequiredBitmapBytes(bmpW, bmpH)
                            requiredJavaBytes = calculateRequiredJavaBytes(bmpW, bmpH)
                            isNativeConstrained = memInfo.lowMemory || (memInfo.availMem < memInfo.threshold + requiredBitmapBytes + nativeSafetyReserve)
                            availableJavaHeap = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
                            isJavaConstrained = (availableJavaHeap - requiredJavaBytes) < javaSafetyReserve
                        }
                    }

                    if (isNativeConstrained || isJavaConstrained) {
                        return@withLock EngineResult.Error(
                            IllegalStateException(
                                "Insufficient memory for lossless rasterization (Native needed: ${requiredBitmapBytes / (1024 * 1024)} MiB, " +
                                        "Java needed: ${requiredJavaBytes / (1024 * 1024)} MiB, Java available: ${availableJavaHeap / (1024 * 1024)} MiB, " +
                                        "System LowMemory: ${memInfo.lowMemory})"
                            ),
                            "Redaction failed: Insufficient memory"
                        )
                    }

                    coroutineContext.ensureActive()

                    val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    val actualAllocatedBytes = bmp.allocationByteCount
                    Log.d("PdfBoxEngineImpl", "Bitmap allocated: ${bmp.width}x${bmp.height}, actual bytes=$actualAllocatedBytes (${actualAllocatedBytes / (1024 * 1024)} MiB)")
                    bmp.setHasAlpha(false)
                    redactedBmp = bmp
                    val canvas = android.graphics.Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    rPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    // Close rPage immediately after rendering completes
                    rPage.close()
                    rPage = null

                    coroutineContext.ensureActive()

                    // 2. Burn solid black rectangle directly into the pixel buffer
                    val rectLeft = (bmpW * xRatio).coerceIn(0f, bmpW.toFloat())
                    val rectTop = (bmpH * (1.0f - yRatio - heightRatio)).coerceIn(0f, bmpH.toFloat())
                    val rectRight = (rectLeft + bmpW * widthRatio).coerceAtMost(bmpW.toFloat())
                    val rectBottom = (rectTop + bmpH * heightRatio).coerceAtMost(bmpH.toFloat())

                    val blackPaint = Paint().apply {
                        color = Color.BLACK
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(rectLeft, rectTop, rectRight, rectBottom, blackPaint)

                    onProgress(ProcessingProgress(2, 2, "Reconstructing secure document..."))

                    coroutineContext.ensureActive()

                    // Bounded PDFBox ScratchFile setup: 4 MiB main memory, 64 MiB total scratch storage (in-memory + disk-backed)
                    val scratchDir = File(context.cacheDir, "pdf_scratch").apply { if (!exists()) mkdirs() }
                    if (!scratchDir.exists() || !scratchDir.isDirectory) {
                        throw IOException("Scratch file directory does not exist or is not a directory: ${scratchDir.absolutePath}")
                    }

                    // Verify that a file can be created, written and deleted in scratchDir
                    val probeFile = File.createTempFile(".probe_", ".tmp", scratchDir)
                    try {
                        probeFile.writeBytes(byteArrayOf(0x50, 0x44, 0x46)) // "PDF"
                        if (probeFile.length() != 3L) {
                            throw IOException("Probe file write verification failed in ${scratchDir.absolutePath}")
                        }
                    } finally {
                        if (!probeFile.delete() && probeFile.exists()) {
                            throw IOException("Probe file delete verification failed in ${scratchDir.absolutePath}")
                        }
                    }

                    val memSetting = MemoryUsageSetting.setupMixed(PDFBOX_MAIN_MEMORY_SCRATCH_BYTES, PDFBOX_TOTAL_SCRATCH_STORAGE_BYTES)
                        .setTempDir(scratchDir)

                    // Pre-flight fail-closed validation: verify ScratchFile can be instantiated without throwing
                    com.tom_roush.pdfbox.io.ScratchFile(memSetting).use { }

                    // 3. Rebuild document: replace target page with lossless FlateDecode image XObject.
                    // sourceDoc load bounds ScratchFile using memSetting (no try-catch fallback in PDDocument.load).
                    // targetDoc is constructed via createBoundedTargetDocument with explicit ScratchFile(memSetting)
                    // and COSDocument, completely eliminating silent main-memory fallbacks and private reflection.
                    val baseDestination = storageManager.createOutputFile(outputBaseName)
                    baseDestinationFile = baseDestination
                    val parentDir = baseDestination.parentFile ?: baseDestination.absoluteFile.parentFile
                    if (parentDir != null && !parentDir.exists()) {
                        val created = parentDir.mkdirs()
                        if (!created && !parentDir.exists()) {
                            throw IOException("Failed to create destination directory: ${parentDir.absolutePath}")
                        }
                    }

                    PDDocument.load(temp, memSetting).use { sourceDoc ->
                        createBoundedTargetDocument(memSetting).use { targetDoc ->
                            for (i in 0 until sourceDoc.numberOfPages) {
                                coroutineContext.ensureActive()
                                if (i == targetIndex) {
                                    val newPage = PDPage(PDRectangle(displayWidth, displayHeight))
                                    newPage.cropBox = PDRectangle(displayWidth, displayHeight)
                                    newPage.rotation = 0
                                    targetDoc.addPage(newPage)
                                    val ximg = LosslessFactory.createFromImage(targetDoc, bmp)
                                    PDPageContentStream(targetDoc, newPage).use { cs ->
                                        cs.drawImage(ximg, 0f, 0f, displayWidth, displayHeight)
                                    }
                                } else {
                                    targetDoc.importPage(sourceDoc.getPage(i))
                                }
                            }

                            // 1. Create a uniquely named staging file owned solely by this operation
                            val stage = File.createTempFile(".stage_redact_", ".tmp", parentDir)
                            stagingFile = stage

                            // 2. Save the redacted document to staging
                            targetDoc.save(stage)
                        }
                    }

                    // 3. Both destination and source PDDocument instances are now closed,
                    //    releasing and deleting their scratch files before pre-publication verification.

                    // 4. Pre-publication structural verification of the staged PDF
                    val staged = stagingFile ?: throw IOException("Staged file reference missing")
                    verifyStagedPdf(staged, totalPages, targetIndex, memSetting)
                    val stageLength = staged.length()

                    // 5. Existing move-based publication mechanism with collision handling under publicationLock via shared helper
                    val finalDestination = publishStagedPdf(
                        staged = staged,
                        baseDestination = baseDestination,
                        expectedLength = stageLength,
                        onStageTransferred = { stagingFile = null },
                        onCommitted = { committed -> verifiedDestination = committed }
                    )

                    // Publication commit point established: finalDestination holds the committed published file
                    verifiedDestination = finalDestination

                    EngineResult.Success(finalDestination)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PostPublicationVerificationException) {
                    cleanupWarningLogger("Post-publication verification check failed for committed output: ${e.committedFile.absolutePath}")
                    verifiedDestination = e.committedFile
                    EngineResult.Error(
                        exception = e,
                        userMessage = "Redacted PDF was saved at ${e.committedFile.name}, but post-publication verification failed: ${e.localizedMessage ?: "Verification error"}",
                        committedFile = e.committedFile
                    )
                } catch (e: Exception) {
                    EngineResult.Error(e, "Redaction failed: ${e.localizedMessage ?: "Processing error"}")
                } finally {
                    // Nested finally blocks: guarantees that every single resource cleanup step is attempted,
                    // even if a fatal Error (e.g. OutOfMemoryError) occurs in an earlier step,
                    // without catching or swallowing Error or Throwable.
                    var cleanupCancellationException: CancellationException? = null
                    try {
                        // 1. Close rPage first (before renderer)
                        try {
                            rPage?.close()
                        } catch (e: CancellationException) {
                            cleanupCancellationException = e
                        } catch (e: Exception) {
                            Log.w("PdfBoxEngineImpl", "Failed to close PdfRenderer.Page: ${e.message}")
                        } finally {
                            rPage = null
                        }
                    } finally {
                        try {
                            // 2. Close renderer second
                            try {
                                renderer?.close()
                            } catch (e: CancellationException) {
                                if (cleanupCancellationException == null) cleanupCancellationException = e
                            } catch (e: Exception) {
                                Log.w("PdfBoxEngineImpl", "Failed to close PdfRenderer: ${e.message}")
                            } finally {
                                renderer = null
                            }
                        } finally {
                            try {
                                // 3. Close parcel file descriptor third
                                try {
                                    pfd?.close()
                                } catch (e: CancellationException) {
                                    if (cleanupCancellationException == null) cleanupCancellationException = e
                                } catch (e: Exception) {
                                    Log.w("PdfBoxEngineImpl", "Failed to close ParcelFileDescriptor: ${e.message}")
                                } finally {
                                    pfd = null
                                }
                            } finally {
                                try {
                                    // 4. Recycle native bitmap
                                    try {
                                        redactedBmp?.recycle()
                                    } catch (e: CancellationException) {
                                        if (cleanupCancellationException == null) cleanupCancellationException = e
                                    } catch (e: Exception) {
                                        Log.w("PdfBoxEngineImpl", "Failed to recycle Bitmap: ${e.message}")
                                    } finally {
                                        redactedBmp = null
                                    }
                                } finally {
                                    try {
                                        // 5. Clean up temporary source file with deterministic retries and verification
                                        if (temp != null && temp.exists()) {
                                            var deleted = false
                                            for (attempt in 1..3) {
                                                try {
                                                    deleted = fileDeleter(temp)
                                                    if (deleted || !temp.exists()) {
                                                        deleted = true
                                                        break
                                                    }
                                                } catch (e: CancellationException) {
                                                    if (cleanupCancellationException == null) cleanupCancellationException = e
                                                } catch (e: Exception) {
                                                    cleanupWarningLogger("Exception on attempt $attempt deleting temp file ${temp.absolutePath}: ${e.message}")
                                                }
                                            }
                                            if (!deleted && temp.exists()) {
                                                cleanupWarningLogger("Failed to delete temporary redaction source file after 3 attempts: ${temp.absolutePath}")
                                            }
                                        }
                                    } finally {
                                        try {
                                            // 6. Clean up unpublished staging file (never delete verifiedDestination)
                                            val remainingStage = stagingFile
                                            if (remainingStage != null && remainingStage.exists()) {
                                                var stageDeleted = false
                                                try {
                                                    stageDeleted = fileDeleter(remainingStage)
                                                } catch (e: CancellationException) {
                                                    if (cleanupCancellationException == null) cleanupCancellationException = e
                                                } catch (e: Exception) {
                                                    cleanupWarningLogger("Exception deleting unpublished staging file ${remainingStage.absolutePath}: ${e.message}")
                                                }
                                                if (!stageDeleted && remainingStage.exists()) {
                                                    cleanupWarningLogger("Failed to delete unpublished staging file: ${remainingStage.absolutePath}")
                                                }
                                                stagingFile = null
                                            }
                                        } finally {
                                            baseDestinationFile?.let { storageManager.releaseReservation(it) }
                                            // Propagate any recorded cancellation exception after all cleanups have completed
                                            cleanupCancellationException?.let { throw it }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                // Outermost finally: guaranteed to execute even if inner finally throws an unexpected error
                activeRedactionJobs.decrementAndGet()
            }
        }
    }

    // 33. Bates Numbering (Legal Stamping)
    override suspend fun batesNumbering(
        sourceUri: Uri,
        prefix: String,
        startNumber: Int,
        digits: Int,
        position: PageNumberPosition,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "bates_")
        executeDirectPdfOperation(outputBaseName, "Bates numbering", listOf(temp)) { outputFile ->
            PDDocument.load(temp).use { doc ->
                val font = PDType1Font.COURIER_BOLD
                val total = doc.numberOfPages

                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Applying Bates stamp to page ${i + 1} of $total..."))
                    val page = doc.getPage(i)
                    val box = page.cropBox ?: page.mediaBox
                    val num = startNumber + i
                    val batesText = "$prefix${String.format(Locale.US, "%0${digits}d", num)}"

                    val x = when (position) {
                        PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.TOP_LEFT -> 40f
                        PageNumberPosition.BOTTOM_CENTER, PageNumberPosition.TOP_CENTER -> box.width / 2f - 40f
                        PageNumberPosition.BOTTOM_RIGHT, PageNumberPosition.TOP_RIGHT -> box.width - 130f
                    }
                    val y = when (position) {
                        PageNumberPosition.BOTTOM_LEFT, PageNumberPosition.BOTTOM_CENTER, PageNumberPosition.BOTTOM_RIGHT -> 30f
                        PageNumberPosition.TOP_LEFT, PageNumberPosition.TOP_CENTER, PageNumberPosition.TOP_RIGHT -> box.height - 35f
                    }

                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.beginText()
                        cs.setFont(font, 11f)
                        cs.setNonStrokingColor(0.85f, 0f, 0f)
                        cs.newLineAtOffset(x, y)
                        cs.showText(batesText)
                        cs.endText()
                    }
                }
                doc.save(outputFile)
            }
        }
    }

    // 34. Add Binding Margins
    override suspend fun addBindingMargin(
        sourceUri: Uri,
        leftMarginPoints: Float,
        rightMarginPoints: Float,
        outputBaseName: String,
        onProgress: (ProcessingProgress) -> Unit
    ): EngineResult<File> = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "binding_")
        executeDirectPdfOperation(outputBaseName, "Binding margin", listOf(temp)) { outputFile ->
            PDDocument.load(temp).use { doc ->
                val total = doc.numberOfPages
                for (i in 0 until total) {
                    coroutineContext.ensureActive()
                    onProgress(ProcessingProgress(i + 1, total, "Adding binding margin to page ${i + 1} of $total..."))
                    val page = doc.getPage(i)
                    val box = page.mediaBox
                    val newWidth = box.width + leftMarginPoints + rightMarginPoints
                    page.mediaBox = PDRectangle(newWidth, box.height)
                }
                doc.save(outputFile)
            }
        }
    }

    // 35. Document Statistics
    override suspend fun getDocumentStats(sourceUri: Uri): DocumentStatistics = withContext(Dispatchers.IO) {
        val temp = storageManager.copyToTempFile(sourceUri, "stats_")
        try {
            PDDocument.load(temp).use { doc ->
                val pages = doc.numberOfPages
                val text = try { PDFTextStripper().getText(doc) } catch (_: Exception) { "" }
                val words = text.split("\\s+".toRegex()).count { it.isNotBlank() }
                val chars = text.length

                var imgCount = 0
                for (i in 0 until pages) {
                    val res = doc.getPage(i).resources ?: continue
                    val names = res.xObjectNames ?: continue
                    for (n in names) {
                        if (res.getXObject(n) is PDImageXObject) imgCount++
                    }
                }

                DocumentStatistics(
                    pageCount = pages,
                    wordCount = words,
                    characterCount = chars,
                    imageCount = imgCount,
                    pdfVersion = "${doc.version}",
                    isEncrypted = doc.isEncrypted,
                    fileSizeBytes = temp.length()
                )
            }
        } finally {
            temp.delete()
        }
    }
}
