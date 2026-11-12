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
    LOW("Light (Best Quality)", "High resolution preserved, slight size reduction", 85, 1800),
    MEDIUM("Balanced (Recommended)", "Ideal balance between crisp text and file size", 65, 1200),
    HIGH("Aggressive (Smallest Size)", "Significantly smaller file size, ideal for web/email", 45, 800)
}

enum class WatermarkPosition {
    CENTER_DIAGONAL,
    CENTER_HORIZONTAL,
    TOP,
    BOTTOM
}

enum class PageNumberPosition {
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    TOP_LEFT
}

data class PageItem(
    val pageIndex: Int,
    val displayIndex: Int = pageIndex + 1,
    val rotationDegrees: Int = 0,
    val isMarkedForDeletion: Boolean = false,
    val thumbnail: Bitmap? = null
)

data class DocumentMetadata(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "Offline PDF Studio"
)

enum class StandardStamp(val label: String, val colorHex: Long) {
    CONFIDENTIAL("CONFIDENTIAL", 0xFFD32F2F),
    APPROVED("APPROVED", 0xFF2E7D32),
    DRAFT("DRAFT", 0xFFE65100),
    COPY("COPY", 0xFF1976D2),
    FINAL("FINAL", 0xFF388E3C),
    REJECTED("REJECTED", 0xFFC2185B),
    TOP_SECRET("TOP SECRET", 0xFFB71C1C)
}

enum class ToolCategory(val title: String) {
    ORGANIZE("Organize & Edit"),
    CONVERT("Convert & Export"),
    SECURITY("Security & Privacy"),
    BRANDING("Design & Stamps"),
    OPTIMIZE("Optimize & Color"),
    VIEW("Read & Inspect")
}

data class StudioTool(
    val id: String,
    val title: String,
    val description: String,
    val category: ToolCategory,
    val iconName: String,
    val badge: String? = null
)
