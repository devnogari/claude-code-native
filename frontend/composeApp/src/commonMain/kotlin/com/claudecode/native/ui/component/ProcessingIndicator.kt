package com.claudecode.native.ui.component

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Processing indicator that shows when Claude is thinking/working.
 * Displays elapsed time, animated spinner, and status text.
 *
 * @param isProcessing Whether Claude is currently processing
 * @param statusText Current status text (e.g., "Thinking", "Processing")
 * @param onStop Callback when stop button is clicked
 * @param modifier Optional modifier
 */
@Composable
fun ProcessingIndicator(
    isProcessing: Boolean,
    statusText: String = "Thinking",
    onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!isProcessing) return

    // Elapsed time tracking
    var elapsedSeconds by remember { mutableStateOf(0) }

    LaunchedEffect(isProcessing) {
        if (isProcessing) {
            elapsedSeconds = 0
            while (true) {
                kotlinx.coroutines.delay(1000)
                elapsedSeconds++
            }
        }
    }

    // Animation for spinner
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val animationPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Scale animation for spinner
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Action words that cycle
    val actionWords = listOf("Thinking", "Processing", "Analyzing", "Working", "Computing", "Reasoning")
    val actionIndex = (elapsedSeconds / 3) % actionWords.size
    val displayText = statusText.ifEmpty { actionWords[actionIndex] }

    // Spinner characters
    val spinners = listOf("✻", "✹", "✸", "✶")
    val currentSpinner = spinners[animationPhase.toInt() % spinners.size]

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1F2937), // Dark gray background
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Animated spinner
                Text(
                    text = currentSpinner,
                    fontSize = 18.sp,
                    color = Color(0xFF60A5FA), // Blue color
                    modifier = Modifier.scale(scale)
                )

                // Status text and elapsed time
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$displayText...",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                        Text(
                            text = "(${elapsedSeconds}s)",
                            fontSize = 13.sp,
                            color = Color(0xFF9CA3AF) // Gray color
                        )
                    }
                    Text(
                        text = "Press stop or esc to cancel",
                        fontSize = 11.sp,
                        color = Color(0xFF6B7280) // Darker gray
                    )
                }
            }

            // Stop button
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFDC2626), // Red
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "Stop",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Compact thinking indicator with animated dots.
 * Shows at the end of message list while streaming.
 */
@Composable
fun ThinkingBubble(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")

    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 0),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )

    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )

    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "●",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dot1Alpha)
            )
            Text(
                text = "●",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dot2Alpha)
            )
            Text(
                text = "●",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dot3Alpha)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Thinking...",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
