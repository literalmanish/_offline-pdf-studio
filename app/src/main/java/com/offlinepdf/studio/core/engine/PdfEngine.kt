package com.offlinepdf.studio.core.engine

import android.net.Uri
import com.offlinepdf.studio.core.engine.model.CompressionLevel
import com.offlinepdf.studio.core.engine.model.EngineResult
import com.offlinepdf.studio.core.engine.model.ProcessingProgress
import java.io.File

interface PdfEngine {

    /**
     * Inspects total page count for a given PDF Uri
     */
    suspend fun getPageCount(uri: Uri): Int

    /**
     * Merges multiple PDF files into one output document
     */
    suspend fun merge(
        sourceUris: List<Uri>,
        outputBaseName: String = "merged",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    /**
     * Splits a PDF into multiple documents based on specified 1-based page ranges
     */
    suspend fun split(
        sourceUri: Uri,
        ranges: List<IntRange>,
        outputBaseName: String = "split_part",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<List<File>>

    /**
     * Rearranges pages, applies rotation, and deletes unmarked pages
     * [pageIndices] is the ordered list of 0-based page indices to keep
     * [rotations] maps 0-based original index to rotation angle (0, 90, 180, 270)
     */
    suspend fun reorderAndFilter(
        sourceUri: Uri,
        pageIndices: List<Int>,
        rotations: Map<Int, Int> = emptyMap(),
        outputBaseName: String = "reordered",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>

    /**
     * Downsamples embedded raster images and optimizes resources on-device
     */
    suspend fun compress(
        sourceUri: Uri,
        level: CompressionLevel = CompressionLevel.MEDIUM,
        outputBaseName: String = "compressed",
        onProgress: (ProcessingProgress) -> Unit = {}
    ): EngineResult<File>
}
