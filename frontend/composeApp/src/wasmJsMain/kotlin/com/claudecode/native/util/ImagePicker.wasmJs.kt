package com.claudecode.native.util

import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.coroutines.resume

/**
 * WASM/JS implementation of ImagePicker using HTML file input.
 */
actual class ImagePicker actual constructor() {

    actual suspend fun pickImages(): List<PickedImage> = suspendCancellableCoroutine { continuation ->
        val input = document.createElement("input") as HTMLInputElement
        input.type = "file"
        input.accept = "image/png,image/jpeg,image/gif,image/webp,image/bmp"
        input.multiple = true

        input.onchange = { event ->
            val files = input.files
            if (files == null || files.length == 0) {
                continuation.resume(emptyList())
            } else {
                val pickedImages = mutableListOf<PickedImage>()
                var processedCount = 0
                val totalCount = files.length

                for (i in 0 until files.length) {
                    val file = files.item(i) ?: continue
                    val reader = FileReader()

                    reader.onload = { loadEvent ->
                        val result = reader.result
                        if (result != null) {
                            // Result is ArrayBuffer, convert to ByteArray
                            val arrayBuffer = result.unsafeCast<ArrayBuffer>()
                            val uint8Array = Uint8Array(arrayBuffer)
                            val bytes = ByteArray(uint8Array.length) { idx -> uint8Array[idx] }

                            pickedImages.add(
                                PickedImage(
                                    data = bytes,
                                    mediaType = file.type,
                                    fileName = file.name
                                )
                            )
                        }
                        processedCount++
                        if (processedCount == totalCount) {
                            continuation.resume(pickedImages.toList())
                        }
                        Unit
                    }

                    reader.onerror = {
                        processedCount++
                        if (processedCount == totalCount) {
                            continuation.resume(pickedImages.toList())
                        }
                        Unit
                    }

                    reader.readAsArrayBuffer(file)
                }
            }
            Unit
        }

        // Handle cancel
        input.oncancel = {
            continuation.resume(emptyList())
            Unit
        }

        input.click()
    }
}
