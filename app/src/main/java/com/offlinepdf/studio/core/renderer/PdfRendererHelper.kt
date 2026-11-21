package com.offlinepdf.studio.core.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException

class PdfRendererHelper private constructor(
    private val pfd: ParcelFileDescriptor,
    private val tempFile: File? = null
) : Closeable {

    constructor(context: Context, file: File) : this(
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
        if (file.name.startsWith("render_view_")) file else null
    )

    private val renderer: PdfRenderer

    init {
        try {
            renderer = PdfRenderer(pfd)
        } catch (t: Throwable) {
            try {
                pfd.close()
            } catch (_: Throwable) {}
            tempFile?.delete()
            throw t
        }
    }

    val pageCount: Int
        get() = renderer.pageCount

    /**
     * Renders a specific page to a Bitmap on Dispatchers.IO.
     * Android's PdfRenderer only allows one page open simultaneously, so we strictly open, render, and close.
     */
    suspend fun renderPage(
        pageIndex: Int,
        destWidth: Int = 1080,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (pageIndex !in 0 until renderer.pageCount) return@withContext null

        synchronized(renderer) {
            var page: PdfRenderer.Page? = null
            try {
                page = renderer.openPage(pageIndex)
                val aspectRatio = page.height.toFloat() / page.width.toFloat()
                val targetHeight = (destWidth * aspectRatio).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(destWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(backgroundColor)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (t: Throwable) {
                null
            } finally {
                page?.close()
            }
        }
    }

    /**
     * Generates a lightweight thumbnail for the visual reorder/delete grid
     */
    suspend fun renderThumbnail(
        pageIndex: Int,
        maxDimension: Int = 300
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (pageIndex !in 0 until renderer.pageCount) return@withContext null

        synchronized(renderer) {
            var page: PdfRenderer.Page? = null
            try {
                page = renderer.openPage(pageIndex)
                val scale = if (page.width > page.height) {
                    maxDimension.toFloat() / page.width.toFloat()
                } else {
                    maxDimension.toFloat() / page.height.toFloat()
                }

                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } catch (t: Throwable) {
                null
            } finally {
                page?.close()
            }
        }
    }

    override fun close() {
        try {
            renderer.close()
        } catch (_: Throwable) {}
        try {
            pfd.close()
        } catch (_: Throwable) {}
        try {
            tempFile?.delete()
        } catch (_: Throwable) {}
    }

    companion object {
        fun fromUri(context: Context, uri: Uri): PdfRendererHelper {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Could not open file descriptor for $uri")
            try {
                // Direct zero-copy descriptor rendering
                return PdfRendererHelper(pfd, null)
            } catch (t: Throwable) {
                try {
                    pfd.close()
                } catch (_: Throwable) {}

                // If uri was a 0-byte or corrupt file where direct open threw, don't attempt redundant spooling
                // if it's already an unrenderable PDF (or if input stream is empty)
                val tempFile = File.createTempFile("render_view_", ".pdf", context.cacheDir)
                try {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException("Could not open input stream for $uri")
                    input.use { inStream ->
                        tempFile.outputStream().use { output ->
                            inStream.copyTo(output)
                        }
                    }
                    if (tempFile.length() == 0L) {
                        tempFile.delete()
                        throw IOException("Cannot render 0-byte PDF stream: $uri")
                    }
                    val fallbackPfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    return PdfRendererHelper(fallbackPfd, tempFile)
                } catch (fallbackEx: Throwable) {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                    throw fallbackEx
                }
            }
        }
    }
}
