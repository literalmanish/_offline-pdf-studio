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

class PdfRendererHelper(
    private val context: Context,
    private val file: File
) : Closeable {

    private val pfd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer: PdfRenderer = PdfRenderer(pfd)

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
            } catch (e: Exception) {
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
            } catch (e: Exception) {
                null
            } finally {
                page?.close()
            }
        }
    }

    override fun close() {
        try {
            renderer.close()
            pfd.close()
        } catch (_: Exception) {}
    }

    companion object {
        fun fromUri(context: Context, uri: Uri): PdfRendererHelper {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalArgumentException("Could not open file descriptor for $uri")
            // Write to a temporary file in cache if descriptor cannot be duplicated directly
            val tempFile = File.createTempFile("render_view_", ".pdf", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            pfd.close()
            return PdfRendererHelper(context, tempFile)
        }
    }
}
