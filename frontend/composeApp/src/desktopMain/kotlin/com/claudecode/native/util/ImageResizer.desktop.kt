package com.claudecode.native.util

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.min

/**
 * Desktop (JVM) implementation of ImageResizer using AWT/ImageIO.
 */
actual object ImageResizer {

    actual fun resize(data: ByteArray, mediaType: String, config: ImageResizeConfig): ResizedImage {
        // Skip if below size threshold
        if (data.size < config.sizeThreshold) {
            return ResizedImage(data, mediaType, wasResized = false)
        }

        try {
            // Read the original image
            val inputStream = ByteArrayInputStream(data)
            val originalImage = ImageIO.read(inputStream)
                ?: return ResizedImage(data, mediaType, wasResized = false)

            val originalWidth = originalImage.width
            val originalHeight = originalImage.height

            // Calculate scale factor to fit within max dimensions
            val widthRatio = config.maxWidth.toDouble() / originalWidth
            val heightRatio = config.maxHeight.toDouble() / originalHeight
            val scaleFactor = min(1.0, min(widthRatio, heightRatio))

            // If no scaling needed and not converting format, return original
            val needsScaling = scaleFactor < 1.0
            val needsConversion = config.convertPngToJpeg && mediaType == "image/png"

            if (!needsScaling && !needsConversion) {
                return ResizedImage(data, mediaType, wasResized = false)
            }

            // Calculate new dimensions
            val newWidth = (originalWidth * scaleFactor).toInt()
            val newHeight = (originalHeight * scaleFactor).toInt()

            // Resize the image if needed
            val resizedImage = if (needsScaling) {
                val scaled = originalImage.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH)
                val buffered = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
                val g2d = buffered.createGraphics()
                g2d.drawImage(scaled, 0, 0, null)
                g2d.dispose()
                buffered
            } else {
                // Convert to RGB for JPEG output if needed
                if (needsConversion && originalImage.type != BufferedImage.TYPE_INT_RGB) {
                    val rgbImage = BufferedImage(originalWidth, originalHeight, BufferedImage.TYPE_INT_RGB)
                    val g2d = rgbImage.createGraphics()
                    g2d.drawImage(originalImage, 0, 0, null)
                    g2d.dispose()
                    rgbImage
                } else {
                    originalImage
                }
            }

            // Determine output format
            val outputAsJpeg = needsConversion || mediaType == "image/jpeg"
            val outputMediaType = if (outputAsJpeg) "image/jpeg" else mediaType
            val formatName = if (outputAsJpeg) "jpeg" else "png"

            // Write to output
            val outputStream = ByteArrayOutputStream()

            if (outputAsJpeg) {
                // Use ImageWriter for quality control
                val writers = ImageIO.getImageWritersByFormatName("jpeg")
                if (writers.hasNext()) {
                    val writer = writers.next()
                    val params = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = config.quality
                    }
                    val ios = ImageIO.createImageOutputStream(outputStream)
                    writer.output = ios
                    writer.write(null, IIOImage(resizedImage, null, null), params)
                    writer.dispose()
                    ios.close()
                } else {
                    ImageIO.write(resizedImage, formatName, outputStream)
                }
            } else {
                ImageIO.write(resizedImage, formatName, outputStream)
            }

            val outputBytes = outputStream.toByteArray()

            // Only use resized if it's actually smaller
            return if (outputBytes.size < data.size) {
                ResizedImage(outputBytes, outputMediaType, wasResized = true)
            } else {
                ResizedImage(data, mediaType, wasResized = false)
            }
        } catch (e: Exception) {
            // On any error, return original
            return ResizedImage(data, mediaType, wasResized = false)
        }
    }
}
