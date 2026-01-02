package com.claudecode.native.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android implementation of image drop target.
 *
 * Android doesn't have desktop-style drag and drop for files.
 * Users should use the image picker button instead.
 * This is a no-op implementation that just returns the modifier unchanged.
 */
@Composable
actual fun Modifier.imageDropTarget(
    enabled: Boolean,
    onDragStateChange: (DropState) -> Unit,
    onImageDropped: (List<PickedImage>) -> Unit
): Modifier {
    // Android doesn't support desktop-style file drag and drop
    // Return the modifier unchanged
    return this
}
