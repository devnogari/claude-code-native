package com.claudecode.native.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * State for drag and drop operations.
 *
 * @property isDragging True when a drag operation is in progress anywhere on the window.
 *                      Can be used to show a global "drop zone available" indicator.
 * @property isHovering True when the dragged content is hovering over the drop target.
 *                      Used to show the drop overlay UI.
 */
data class DropState(
    val isDragging: Boolean = false,
    val isHovering: Boolean = false
)

/**
 * Creates a modifier that handles image file drops.
 * Platform-specific implementation.
 *
 * @param enabled Whether drop handling is enabled
 * @param onDragStateChange Called when drag state changes (hovering over area)
 * @param onImageDropped Called when images are dropped
 * @return Modifier with drop handling applied
 */
@Composable
expect fun Modifier.imageDropTarget(
    enabled: Boolean = true,
    onDragStateChange: (DropState) -> Unit = {},
    onImageDropped: (List<PickedImage>) -> Unit
): Modifier
