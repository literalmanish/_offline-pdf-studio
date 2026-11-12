package com.offlinepdf.studio.core.engine

import android.graphics.Bitmap
import android.net.Uri
import com.offlinepdf.studio.core.engine.model.*
import java.io.File

interface PdfEngine {

    suspend fun getPageCount(uri: Uri): Int

    // 1. Merge
    suspend fun merge(
        sourceUris: List<Uri>,
        outputBaseName: String = "merged",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 2. Split
    suspend fun split(
        sourceUri: Uri,
        ranges: List<IntRange>,
        outputBaseName: String = "split_part",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<List<File>>

    // 3. Reorder & Delete
    suspend fun reorderAndFilter(
        sourceUri: Uri,
        pageIndices: List<Int>,
        rotations: Map<Int, Int> = emptyMap(),
        outputBaseName: String = "reordered",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 4. Compress
    suspend fun compress(
        sourceUri: Uri,
        level: CompressionLevel = CompressionLevel.MEDIUM,
        outputBaseName: String = "compressed",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 5. Rotate Pages
    suspend fun rotatePages(
        sourceUri: Uri,
        rotationDegrees: Int = 90,
        outputBaseName: String = "rotated",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 6. Reverse Pages
    suspend fun reversePages(
        sourceUri: Uri,
        outputBaseName: String = "reversed",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 7. Duplicate Pages
    suspend fun duplicatePages(
        sourceUri: Uri,
        pageIndices: List<Int>,
        outputBaseName: String = "duplicated",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 8. Insert Blank Pages
    suspend fun insertBlankPages(
        sourceUri: Uri,
        positions: List<Int>,
        outputBaseName: String = "with_blank_pages",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 9. Crop Pages
    suspend fun cropPages(
        sourceUri: Uri,
        marginPoints: Float = 36f,
        outputBaseName: String = "cropped",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 10. N-Up (Pages per Sheet: 2 or 4)
    suspend fun nUp(
        sourceUri: Uri,
        pagesPerSheet: Int = 2,
        outputBaseName: String = "nup",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 11. Images to PDF
    suspend fun imagesToPdf(
        imageUris: List<Uri>,
        outputBaseName: String = "images_to_pdf",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 12. PDF to Images
    suspend fun pdfToImages(
        sourceUri: Uri,
        format: String = "PNG",
        outputBaseName: String = "page_images",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<List<File>>

    // 13. Extract Embedded Images
    suspend fun extractImages(
        sourceUri: Uri,
        outputBaseName: String = "extracted_images",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<List<File>>

    // 14. Extract Text
    suspend fun extractText(
        sourceUri: Uri,
        outputBaseName: String = "extracted_text",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 15. Text to PDF
    suspend fun textToPdf(
        text: String,
        title: String = "Document",
        outputBaseName: String = "text_document",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 16. Protect / Encrypt PDF
    suspend fun protectPdf(
        sourceUri: Uri,
        userPassword: String,
        ownerPassword: String = userPassword,
        outputBaseName: String = "protected",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 17. Unlock / Decrypt PDF
    suspend fun unlockPdf(
        sourceUri: Uri,
        password: String,
        outputBaseName: String = "unlocked",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 18. Read Metadata
    suspend fun getMetadata(sourceUri: Uri): DocumentMetadata

    // 19. Edit Metadata
    suspend fun editMetadata(
        sourceUri: Uri,
        metadata: DocumentMetadata,
        outputBaseName: String = "metadata_updated",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 20. Sanitize / Remove Metadata
    suspend fun sanitizeMetadata(
        sourceUri: Uri,
        outputBaseName: String = "sanitized",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 21. Flatten PDF
    suspend fun flattenPdf(
        sourceUri: Uri,
        outputBaseName: String = "flattened",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 22. Add Watermark
    suspend fun addWatermark(
        sourceUri: Uri,
        text: String,
        position: WatermarkPosition = WatermarkPosition.CENTER_DIAGONAL,
        opacity: Float = 0.25f,
        outputBaseName: String = "watermarked",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 23. Add Page Numbers
    suspend fun addPageNumbers(
        sourceUri: Uri,
        position: PageNumberPosition = PageNumberPosition.BOTTOM_CENTER,
        formatPattern: String = "Page {n} of {total}",
        startPage: Int = 1,
        outputBaseName: String = "page_numbered",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 24. Add Header and Footer
    suspend fun addHeaderFooter(
        sourceUri: Uri,
        headerText: String,
        footerText: String,
        outputBaseName: String = "header_footer",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 25. Add Stamp
    suspend fun addStamp(
        sourceUri: Uri,
        stamp: StandardStamp,
        customText: String? = null,
        outputBaseName: String = "stamped",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 26. Sign PDF
    suspend fun signPdf(
        sourceUri: Uri,
        signatureBitmap: Bitmap,
        pageIndex: Int,
        xRatio: Float = 0.5f,
        yRatio: Float = 0.15f,
        outputBaseName: String = "signed",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 27. Convert to Grayscale
    suspend fun convertToGrayscale(
        sourceUri: Uri,
        outputBaseName: String = "grayscale",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 28. Invert Colors (Night Mode)
    suspend fun invertColors(
        sourceUri: Uri,
        outputBaseName: String = "inverted",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 29. Repair PDF
    suspend fun repairPdf(
        sourceUri: Uri,
        outputBaseName: String = "repaired",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    // 30. Extract Bookmarks
    suspend fun extractBookmarks(sourceUri: Uri): List<String>

    // 31. Compare PDFs
    suspend fun comparePdfs(
        uriA: Uri,
        uriB: Uri
    ): Pair<Int, Int>

    // 32. View / Render is handled via PdfRendererHelper
}
