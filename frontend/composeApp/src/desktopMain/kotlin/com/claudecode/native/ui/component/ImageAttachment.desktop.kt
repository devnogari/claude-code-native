package com.claudecode.native.ui.component

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.util.Base64

/**
 * Decode raw image bytes to ImageBitmap on Desktop (JVM).
 */
actual fun decodeImageBitmap(data: ByteArray, mediaType: String): ImageBitmap? {
    return try {
        Image.makeFromEncoded(data).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * Decode base64 string to ImageBitmap on Desktop (JVM).
 */
actual fun decodeBase64ToBitmap(base64Data: String, mediaType: String): ImageBitmap? {
    return try {
        val bytes = Base64.getDecoder().decode(base64Data)
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}
