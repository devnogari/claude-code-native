package com.claudecode.native.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * Android implementation of ImageResizer using Bitmap API.
 */
actual object ImageResizer {

    actual fun resize(data: ByteArray, mediaType: String, config: ImageResizeConfig): ResizedImage {
        // Skip if below size threshold
        if (data.size < config.sizeThreshold) {
            return ResizedImage(data, mediaType, wasResized = false)
        }

        try {
            // Decode bitmap
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight

            if (originalWidth <= 0 || originalHeight <= 0) {
                return ResizedImage(data, mediaType, wasResized = false)
            }

            // Calculate scale factor
            val widthRatio = config.maxWidth.toDouble() / originalWidth
            val heightRatio = config.maxHeight.toDouble() / originalHeight
            val scaleFactor = min(1.0, min(widthRatio, heightRatio))

            val needsScaling = scaleFactor < 1.0
            val needsConversion = config.convertPngToJpeg && mediaType == "image/png"

            if (!needsScaling && !needsConversion) {
                return ResizedImage(data, mediaType, wasResized = false)
            }

            // Calculate sample size for efficient decoding
            val sampleSize = calculateSampleSize(originalWidth, originalHeight, config.maxWidth, config.maxHeight)

            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)
                ?: return ResizedImage(data, mediaType, wasResized = false)

            // Calculate new dimensions
            val newWidth = (originalWidth * scaleFactor).toInt()
            val newHeight = (originalHeight * scaleFactor).toInt()

            // Scale if needed
            val scaledBitmap = if (bitmap.width != newWidth || bitmap.height != newHeight) {
                val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                if (scaled != bitmap) {
                    bitmap.recycle()
                }
                scaled
            } else {
                bitmap
            }

            // Determine output format
            val outputAsJpeg = needsConversion || mediaType == "image/jpeg"
            val outputMediaType = if (outputAsJpeg) "image/jpeg" else mediaType
            val format = if (outputAsJpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            val quality = (config.quality * 100).toInt()

            // For JPEG output, need to handle transparency by adding white background
            val bitmapToCompress = if (outputAsJpeg && scaledBitmap.hasAlpha()) {
                val rgbBitmap = Bitmap.createBitmap(
                    scaledBitmap.width,
                    scaledBitmap.height,
                    Bitmap.Config.RGB_565
                )
                val canvas = Canvas(rgbBitmap)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                scaledBitmap.recycle()
                rgbBitmap
            } else {
                scaledBitmap
            }

            // Compress
            val outputStream = ByteArrayOutputStream()
            bitmapToCompress.compress(format, quality, outputStream)
            bitmapToCompress.recycle()

            val outputBytes = outputStream.toByteArray()

            // Only use resized if actually smaller
            return if (outputBytes.size < data.size) {
                ResizedImage(outputBytes, outputMediaType, wasResized = true)
            } else {
                ResizedImage(data, mediaType, wasResized = false)
            }
        } catch (e: Exception) {
            return ResizedImage(data, mediaType, wasResized = false)
        }
    }

    private fun calculateSampleSize(
        originalWidth: Int,
        originalHeight: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Int {
        var sampleSize = 1
        if (originalHeight > maxHeight || originalWidth > maxWidth) {
            val halfHeight = originalHeight / 2
            val halfWidth = originalWidth / 2
            while (halfHeight / sampleSize >= maxHeight && halfWidth / sampleSize >= maxWidth) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }
}
