package com.claudecode.native.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.claudecode.native.ui.viewmodel.AttachedImage
import com.claudecode.native.util.Platform

/**
 * Chat input bar for composing and sending messages.
 *
 * Features:
 * - Text input with multiline support
 * - Image attachment support with preview
 * - Send/Stop button states based on streaming
 * - Keyboard shortcuts:
 *   - Enter to send, Shift+Enter for newline
 *   - ESC to stop generation
 *   - Shift+Tab or Alt+M to cycle operation mode
 * - Queue messages during streaming
 *
 * @param inputTextValue Current text field value with selection state
 * @param onInputChange Callback when input text changes
 * @param isStreaming Whether the assistant is currently generating a response
 * @param isConnected Whether connected to the server (or in draft mode)
 * @param attachedImages List of images attached to the message
 * @param onAttachImages Callback to open image picker
 * @param onRemoveImage Callback to remove an attached image by ID
 * @param onSend Callback to send the message
 * @param onStop Callback to stop response generation
 * @param onModeToggle Callback to cycle through operation modes (Shift+Tab or Alt+M)
 * @param canFocus Whether the input field can receive focus (for iOS keyboard handling)
 * @param modifier Modifier for the input bar container
 */
@Composable
fun ChatInputBar(
    inputTextValue: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    isStreaming: Boolean,
    isConnected: Boolean,
    attachedImages: List<AttachedImage> = emptyList(),
    onAttachImages: () -> Unit = {},
    onRemoveImage: (String) -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    onModeToggle: () -> Unit = {},
    canFocus: Boolean = true,
    modifier: Modifier = Modifier
) {
    val hasContent = inputTextValue.text.isNotBlank() || attachedImages.isNotEmpty()

    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // Attached images preview row
            if (attachedImages.isNotEmpty()) {
                AttachedImagesPreview(
                    images = attachedImages,
                    onRemove = onRemoveImage,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Image attachment button (enabled even during streaming for queued messages)
                IconButton(
                    onClick = onAttachImages,
                    enabled = isConnected
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Attach image",
                        tint = if (isConnected) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }

                OutlinedTextField(
                    value = inputTextValue,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusProperties { this.canFocus = canFocus }
                        .onPreviewKeyEvent { keyEvent ->
                            when {
                                // ESC to stop streaming (Desktop)
                                keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown && isStreaming -> {
                                    onStop()
                                    true // Consume the event
                                }
                                // Shift+Tab to cycle operation mode
                                keyEvent.key == Key.Tab && keyEvent.type == KeyEventType.KeyDown && keyEvent.isShiftPressed -> {
                                    onModeToggle()
                                    true // Consume the event
                                }
                                // Alt+M to cycle operation mode
                                keyEvent.key == Key.M && keyEvent.type == KeyEventType.KeyDown && keyEvent.isAltPressed -> {
                                    onModeToggle()
                                    true // Consume the event
                                }
                                // Desktop: Enter to send (without Shift), Shift+Enter for newline
                                keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown -> {
                                    if (keyEvent.isShiftPressed) {
                                        // Shift+Enter: Insert newline at cursor position and move cursor down
                                        // If text is selected, replace selection with newline
                                        val currentText = inputTextValue.text
                                        val selectionStart = inputTextValue.selection.start
                                        val selectionEnd = inputTextValue.selection.end
                                        val newText = currentText.substring(0, selectionStart) + "\n" + currentText.substring(selectionEnd)
                                        val newCursorPos = selectionStart + 1
                                        onInputChange(TextFieldValue(
                                            text = newText,
                                            selection = TextRange(newCursorPos)
                                        ))
                                        true // Consume the event
                                    } else if (hasContent && isConnected) {
                                        // Enter without Shift: Send message
                                        onSend()
                                        true // Consume the event
                                    } else {
                                        // Empty input: do nothing
                                        true
                                    }
                                }
                                else -> false
                            }
                        },
                    placeholder = {
                        Text(
                            if (!isConnected) "Read-only (viewing history)"
                            else if (isStreaming) "Type to queue message..."
                            else "Type a message..."
                        )
                    },
                    enabled = true,  // Always enabled - allow typing to queue messages during streaming
                    singleLine = false,
                    minLines = 1,
                    maxLines = 8,  // Allow more lines for multiline input (Shift+Enter to add newlines on desktop)
                    // iOS: Use Default action to allow Enter for newlines (send via button only)
                    // Desktop/Web: Use Send action (Enter sends, Shift+Enter for newlines)
                    keyboardOptions = KeyboardOptions(
                        imeAction = if (Platform.isIOS) ImeAction.Default else ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (hasContent && isConnected) {
                                onSend()
                            }
                        }
                    )
                )

                // Show both Stop button (when streaming) and Send button (always)
                if (isStreaming) {
                    // Stop button during streaming
                    IconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Stop generation"
                        )
                    }
                }

                // Send button - always visible, queues message during streaming
                IconButton(
                    onClick = onSend,
                    enabled = hasContent && isConnected,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isStreaming) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (isStreaming) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isStreaming) "Queue message" else "Send message"
                    )
                }
            }
        }
    }
}
