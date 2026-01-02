package com.claudecode.native.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Android implementation of ImagePicker using ActivityResultContract.
 *
 * Note: This implementation requires activity context registration.
 * For a full implementation, you would need to register the launcher in the Activity.
 * This is a simplified version that works with the content resolver directly.
 */
actual class ImagePicker actual constructor() {

    actual suspend fun pickImages(): List<PickedImage> {
        // Android image picking requires Activity context and ActivityResultLauncher
        // For now, return empty list - full implementation needs Activity integration
        // The recommended approach is to use rememberLauncherForActivityResult in Compose
        return emptyList()
    }

    companion object {
        private var activityRef: WeakReference<ComponentActivity>? = null
        private var pendingCallback: ((List<Uri>) -> Unit)? = null
        private var launcher: ActivityResultLauncher<Intent>? = null

        /**
         * Initialize the image picker with an activity.
         * Call this in your Activity's onCreate.
         */
        fun init(activity: ComponentActivity) {
            activityRef = WeakReference(activity)
            launcher = activity.registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val uris = mutableListOf<Uri>()
                result.data?.let { data ->
                    // Check for multiple selection
                    data.clipData?.let { clipData ->
                        for (i in 0 until clipData.itemCount) {
                            clipData.getItemAt(i).uri?.let { uris.add(it) }
                        }
                    }
                    // Check for single selection
                    if (uris.isEmpty()) {
                        data.data?.let { uris.add(it) }
                    }
                }
                pendingCallback?.invoke(uris)
                pendingCallback = null
            }
        }

        /**
         * Pick images using the registered launcher.
         * Returns list of PickedImage from selected URIs.
         */
        suspend fun pickImagesWithActivity(): List<PickedImage> = suspendCancellableCoroutine { cont ->
            val activity = activityRef?.get()
            val currentLauncher = launcher

            if (activity == null || currentLauncher == null) {
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }

            pendingCallback = { uris ->
                val images = uris.mapNotNull { uri ->
                    readImageFromUri(activity, uri)
                }
                cont.resume(images)
            }

            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
            }

            currentLauncher.launch(Intent.createChooser(intent, "Select Images"))

            cont.invokeOnCancellation {
                pendingCallback = null
            }
        }

        private fun readImageFromUri(context: Context, uri: Uri): PickedImage? {
            return try {
                val contentResolver = context.contentResolver
                val data = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return null

                val mediaType = contentResolver.getType(uri) ?: "image/jpeg"
                val fileName = getFileName(context, uri)

                // Resize image if needed
                val resized = ImageResizer.resize(data, mediaType)

                PickedImage(
                    data = resized.data,
                    mediaType = resized.mediaType,
                    fileName = fileName
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun getFileName(context: Context, uri: Uri): String? {
            var fileName: String? = null
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
            return fileName
        }
    }
}
