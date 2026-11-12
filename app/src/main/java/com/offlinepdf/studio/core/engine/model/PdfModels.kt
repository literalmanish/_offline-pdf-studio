package com.offlinepdf.studio.core.engine.model

import android.graphics.Bitmap
import android.net.Uri

data class ProcessingProgress(
    val current: Int,
    val total: Int,
    val message: String,
    val percentage: Float = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
)

sealed class EngineResult<out T> {
    data class Success<out T>(val data: T) : EngineResult<T>()
    data class Error(val exception: Throwable, val userMessage: String) : EngineResult<Nothing>()
}

enum class CompressionLevel(
    val title: String,
    val description: String,
    val imageQuality: Int,
    val maxDimension: Int
) {
    LOW(
        title = "Light (Best Quality)",
        description = "High resolution preserved, slight size reduction",
        imageQuality = 85,
        maxDimension = 1800
    ),
    MEDIUM(
        title = "Balanced (Recommended)",
        description = "Ideal balance between crisp text and file size",
        imageQuality = 65,
        maxDimension = 1200
    ),
    HIGH(
        title = "Aggressive (Smallest Size)",
        description = "Significantly smaller file size, suitable for web sharing",
        imageQuality = 45,
        maxDimension = 800
    )
}

data class PageItem(
    val pageIndex: Int,
    val displayIndex: Int = pageIndex + 1,
    val rotationDegrees: Int = 0,
    val isMarkedForDeletion: Boolean = false,
    val thumbnail: Bitmap? = null
)

data class DocumentInfo(
    val uri: Uri,
    val fileName: String,
    val pageCount: Int,
    val fileSizeBytes: Long
)
