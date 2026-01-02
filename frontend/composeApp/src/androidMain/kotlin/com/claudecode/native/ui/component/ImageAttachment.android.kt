package com.claudecode.native.ui.component

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Decode raw image bytes to ImageBitmap on Android.
 */
actual fun decodeImageBitmap(data: ByteArray, mediaType: String): ImageBitmap? {
    return try {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * Decode base64 string to ImageBitmap on Android.
 */
actual fun decodeBase64ToBitmap(base64Data: String, mediaType: String): ImageBitmap? {
    return try {
        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}
