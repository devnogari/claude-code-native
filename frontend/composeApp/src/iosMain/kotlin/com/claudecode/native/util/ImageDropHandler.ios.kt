package com.claudecode.native.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * iOS implementation of image drop target.
 *
 * iOS drag and drop requires UIKit integration which is complex in Compose Multiplatform.
 * For now, this is a no-op implementation. Users can use the image picker button instead.
 *
 * Future enhancement: Implement using UIDropInteraction via Kotlin/Native interop.
 */
@Composable
actual fun Modifier.imageDropTarget(
    enabled: Boolean,
    onDragStateChange: (DropState) -> Unit,
    onImageDropped: (List<PickedImage>) -> Unit
): Modifier {
    // iOS drag and drop requires UIKit interop which is not straightforward in Compose
    // The image picker button provides the primary way to add images on iOS
    return this
}
