package com.claudecode.native.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.claudecode.native.ui.viewmodel.AttachedImage
import com.claudecode.native.ui.viewmodel.ImageSource

/**
 * Button for attaching images to a message.
 * Opens a file picker dialog when clicked.
 */
@Composable
fun ImageAttachmentButton(
    onImagesSelected: (List<AttachedImage>) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = {
            // File picker is handled by platform-specific implementation
            // This is a placeholder that will be connected to ImagePicker
        },
        enabled = enabled,
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Default.AttachFile,
            contentDescription = "Attach image",
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            }
        )
    }
}

/**
 * Preview row showing attached images with remove buttons.
 */
@Composable
fun AttachedImagesPreview(
    images: List<AttachedImage>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (images.isEmpty()) return

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(images, key = { it.id }) { image ->
            AttachedImageThumbnail(
                image = image,
                onRemove = { onRemove(image.id) }
            )
        }
    }
}

/**
 * Single image thumbnail with remove button.
 */
@Composable
private fun AttachedImageThumbnail(
    image: AttachedImage,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(80.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // Image placeholder - actual image loading depends on platform
        val bitmap = remember(image.id) { decodeImageBitmap(image.data, image.mediaType) }

        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = image.fileName ?: "Attached image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Fallback placeholder
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // Remove button
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(20.dp)
                .background(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove image",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(14.dp)
            )
        }

        // File name tooltip (optional)
        image.fileName?.let { name ->
            Text(
                text = name.take(12) + if (name.length > 12) "..." else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(2.dp)
            )
        }
    }
}

/**
 * Image block displayed in a message bubble.
 * Renders an image from ImageSource with click-to-expand capability.
 */
@Composable
fun ImageBlock(
    source: ImageSource,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    when (source) {
        is ImageSource.Base64 -> {
            val bitmap = remember(source.data) {
                decodeBase64ToBitmap(source.data, source.mediaType)
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Image",
                    modifier = modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClick)
                        .heightIn(max = 300.dp)
                        .widthIn(max = 400.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                // Error placeholder
                Box(
                    modifier = modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Image failed to load",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/**
 * Full-screen image viewer dialog.
 */
@Composable
fun ImageViewerDialog(
    source: ImageSource,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        text = {
            when (source) {
                is ImageSource.Base64 -> {
                    val bitmap = remember(source.data) {
                        decodeBase64ToBitmap(source.data, source.mediaType)
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Full size image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 500.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    )
}

/**
 * Decode raw image bytes to ImageBitmap.
 * Platform-specific implementation.
 */
expect fun decodeImageBitmap(data: ByteArray, mediaType: String): ImageBitmap?

/**
 * Decode base64 string to ImageBitmap.
 * Platform-specific implementation.
 */
expect fun decodeBase64ToBitmap(base64Data: String, mediaType: String): ImageBitmap?
