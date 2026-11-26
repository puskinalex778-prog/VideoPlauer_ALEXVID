package com.alexvid.player.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

object ScreenshotHelper {

    data class CaptureResult(
        val success: Boolean,
        val fileName: String = "",
        val croppedRegion: Boolean = false,
        val error: String? = null
    )

    fun findTextureView(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findTextureView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Captures the video frame from VLCVideoLayout's TextureView.
     * When zoomed in (scale > 1.01) and cropToVisibleViewport = true, renders exactly
     * the zoomed & panned area currently visible on screen without any UI controls.
     */
    fun captureVideoBitmap(
        viewportView: View,
        vlcLayout: View,
        scale: Float,
        translationX: Float,
        translationY: Float,
        cropToVisibleViewport: Boolean
    ): Pair<Bitmap?, Boolean> {
        val textureView = findTextureView(vlcLayout) ?: return null to false
        val rawBitmap = textureView.bitmap ?: return null to false

        if (!cropToVisibleViewport || scale <= 1.01f) {
            return rawBitmap to false
        }

        val viewportW = viewportView.width
        val viewportH = viewportView.height
        val texW = textureView.width.toFloat()
        val texH = textureView.height.toFloat()
        val vlcW = vlcLayout.width.toFloat()
        val vlcH = vlcLayout.height.toFloat()

        if (viewportW <= 0 || viewportH <= 0 || texW <= 0f || texH <= 0f || vlcW <= 0f || vlcH <= 0f) {
            return rawBitmap to false
        }

        // Compute textureView offset inside vlcLayout
        var offsetX = 0f
        var offsetY = 0f
        var cur: View? = textureView
        while (cur != null && cur != vlcLayout) {
            offsetX += cur.left
            offsetY += cur.top
            cur = cur.parent as? View
        }

        // Output size matches viewport aspect ratio at crisp high resolution
        val outMaxDim = maxOf(viewportW, viewportH).coerceAtMost(2160)
        val outScale = outMaxDim.toFloat() / maxOf(viewportW, viewportH).toFloat()
        val outW = (viewportW * outScale).roundToInt().coerceAtLeast(1)
        val outH = (viewportH * outScale).roundToInt().coerceAtLeast(1)

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)

        val pivotX = vlcW * 0.5f
        val pivotY = vlcH * 0.5f

        val matrix = Matrix().apply {
            // Map rawBitmap pixels -> textureView view coordinates
            postScale(texW / rawBitmap.width.toFloat(), texH / rawBitmap.height.toFloat())
            // Position textureView inside vlcLayout
            postTranslate(offsetX, offsetY)
            // Apply vlcLayout zoom around its pivot
            postScale(scale, scale, pivotX, pivotY)
            // Apply vlcLayout translation (pan)
            postTranslate(translationX, translationY)
            // Apply output scale if viewport was capped
            if (outScale != 1f) {
                postScale(outScale, outScale)
            }
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(rawBitmap, matrix, paint)
        rawBitmap.recycle()

        return result to true
    }

    /**
     * Saves PNG or JPEG screenshot into device Gallery album "Pictures/ALEXVID".
     */
    fun saveBitmapToGallery(
        context: Context,
        bitmap: Bitmap,
        isCroppedRegion: Boolean,
        format: String = "PNG"
    ): CaptureResult {
        val isJpeg = format.equals("JPEG", ignoreCase = true) || format.equals("JPG", ignoreCase = true)
        val ext = if (isJpeg) "jpg" else "png"
        val mime = if (isJpeg) "image/jpeg" else "image/png"
        val compressFormat = if (isJpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
        val quality = if (isJpeg) 94 else 100

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val suffix = if (isCroppedRegion) "_zoom" else ""
        val fileName = "ALEXVID_${timestamp}${suffix}.$ext"

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ALEXVID")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return CaptureResult(false, error = "MediaStore insert failed")

                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(compressFormat, quality, out)) {
                        throw IllegalStateException("Image compression failed")
                    }
                } ?: throw IllegalStateException("OutputStream unavailable")

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                CaptureResult(true, fileName = fileName, croppedRegion = isCroppedRegion)
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val albumDir = File(picturesDir, "ALEXVID").apply { mkdirs() }
                val outFile = File(albumDir, fileName)
                FileOutputStream(outFile).use { out ->
                    if (!bitmap.compress(compressFormat, quality, out)) {
                        throw IllegalStateException("Image compression failed")
                    }
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outFile.absolutePath),
                    arrayOf(mime),
                    null
                )
                CaptureResult(true, fileName = fileName, croppedRegion = isCroppedRegion)
            }
        } catch (e: Exception) {
            CaptureResult(false, error = e.localizedMessage)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
}
