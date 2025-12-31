package com.claudecode.native.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders markdown text with basic formatting support.
 *
 * Supports:
 * - **bold** and __bold__
 * - *italic* and _italic_
 * - `inline code`
 * - ```code blocks```
 * - [links](url)
 * - # Headers (h1-h3)
 * - - bullet lists
 *
 * @param text The markdown text to render
 * @param modifier Optional modifier
 * @param color Text color (for non-code text)
 * @param style Base text style
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val codeTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary

    // Parse the markdown into blocks (code blocks vs regular text)
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    SelectionContainer {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.CodeBlock -> {
                        CodeBlockView(
                            code = block.code,
                            language = block.language,
                            backgroundColor = codeBackgroundColor,
                            textColor = codeTextColor
                        )
                    }
                    is MarkdownBlock.Text -> {
                        val annotatedString = remember(block.content) {
                            parseInlineMarkdown(block.content, color, linkColor, codeBackgroundColor, style)
                        }

                        Text(
                            text = annotatedString,
                            style = style.copy(color = color)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays a code block with syntax highlighting background.
 */
@Composable
private fun CodeBlockView(
    code: String,
    language: String?,
    backgroundColor: Color,
    textColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = code.trimEnd(),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp
                ),
                color = textColor
            )
        }
    }
}

/**
 * Sealed class representing markdown blocks.
 */
private sealed class MarkdownBlock {
    data class CodeBlock(val code: String, val language: String?) : MarkdownBlock()
    data class Text(val content: String) : MarkdownBlock()
}

/**
 * Parses markdown text into code blocks and regular text blocks.
 */
private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val codeBlockRegex = Regex("```(\\w*)\\n?([\\s\\S]*?)```", RegexOption.MULTILINE)

    var lastIndex = 0
    codeBlockRegex.findAll(text).forEach { match ->
        // Add text before the code block
        if (match.range.first > lastIndex) {
            val textContent = text.substring(lastIndex, match.range.first).trim()
            if (textContent.isNotEmpty()) {
                blocks.add(MarkdownBlock.Text(textContent))
            }
        }

        // Add the code block
        val language = match.groupValues[1].takeIf { it.isNotBlank() }
        val code = match.groupValues[2]
        blocks.add(MarkdownBlock.CodeBlock(code, language))

        lastIndex = match.range.last + 1
    }

    // Add remaining text
    if (lastIndex < text.length) {
        val textContent = text.substring(lastIndex).trim()
        if (textContent.isNotEmpty()) {
            blocks.add(MarkdownBlock.Text(textContent))
        }
    }

    // If no blocks were created, treat entire text as regular text
    if (blocks.isEmpty()) {
        blocks.add(MarkdownBlock.Text(text))
    }

    return blocks
}

/**
 * Parses inline markdown formatting into an AnnotatedString.
 */
private fun parseInlineMarkdown(
    text: String,
    textColor: Color,
    linkColor: Color,
    codeBackgroundColor: Color,
    baseStyle: TextStyle
): AnnotatedString {
    return buildAnnotatedString {
        var currentText = text

        // Process headers first
        val lines = currentText.split('\n')
        val processedLines = lines.mapIndexed { index, line ->
            when {
                line.startsWith("### ") -> {
                    appendLine(buildStyledLine(line.removePrefix("### "), textColor, linkColor, codeBackgroundColor, baseStyle, FontWeight.Bold, 1.1f))
                    ""
                }
                line.startsWith("## ") -> {
                    appendLine(buildStyledLine(line.removePrefix("## "), textColor, linkColor, codeBackgroundColor, baseStyle, FontWeight.Bold, 1.2f))
                    ""
                }
                line.startsWith("# ") -> {
                    appendLine(buildStyledLine(line.removePrefix("# "), textColor, linkColor, codeBackgroundColor, baseStyle, FontWeight.Bold, 1.4f))
                    ""
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    val bullet = "  • " + line.substring(2)
                    bullet
                }
                line.matches(Regex("^\\d+\\. .*")) -> {
                    val match = Regex("^(\\d+)\\. (.*)").find(line)
                    if (match != null) {
                        "  ${match.groupValues[1]}. ${match.groupValues[2]}"
                    } else {
                        line
                    }
                }
                else -> line
            }
        }.filter { it.isNotEmpty() }

        if (processedLines.isNotEmpty()) {
            currentText = processedLines.joinToString("\n")
        }

        // Process inline formatting
        appendInlineFormatting(currentText, textColor, linkColor, codeBackgroundColor)
    }
}

private fun AnnotatedString.Builder.buildStyledLine(
    text: String,
    textColor: Color,
    linkColor: Color,
    codeBackgroundColor: Color,
    baseStyle: TextStyle,
    fontWeight: FontWeight,
    sizeFactor: Float
): AnnotatedString {
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = fontWeight, fontSize = (baseStyle.fontSize.value * sizeFactor).sp)) {
            appendInlineFormatting(text, textColor, linkColor, codeBackgroundColor)
        }
    }
}

private fun AnnotatedString.Builder.appendInlineFormatting(
    text: String,
    textColor: Color,
    linkColor: Color,
    codeBackgroundColor: Color
) {
    var remaining = text
    var i = 0

    while (i < remaining.length) {
        when {
            // Bold: **text** or __text__
            remaining.substring(i).startsWith("**") -> {
                val endIndex = remaining.indexOf("**", i + 2)
                if (endIndex != -1) {
                    append(remaining.substring(0, i))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(remaining.substring(i + 2, endIndex))
                    }
                    remaining = remaining.substring(endIndex + 2)
                    i = 0
                } else {
                    i++
                }
            }
            remaining.substring(i).startsWith("__") -> {
                val endIndex = remaining.indexOf("__", i + 2)
                if (endIndex != -1) {
                    append(remaining.substring(0, i))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(remaining.substring(i + 2, endIndex))
                    }
                    remaining = remaining.substring(endIndex + 2)
                    i = 0
                } else {
                    i++
                }
            }
            // Italic: *text* or _text_ (but not ** or __)
            remaining.substring(i).startsWith("*") && !remaining.substring(i).startsWith("**") -> {
                val endIndex = findMatchingDelimiter(remaining, i + 1, "*")
                if (endIndex != -1) {
                    append(remaining.substring(0, i))
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(remaining.substring(i + 1, endIndex))
                    }
                    remaining = remaining.substring(endIndex + 1)
                    i = 0
                } else {
                    i++
                }
            }
            remaining.substring(i).startsWith("_") && !remaining.substring(i).startsWith("__") -> {
                val endIndex = findMatchingDelimiter(remaining, i + 1, "_")
                if (endIndex != -1) {
                    append(remaining.substring(0, i))
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(remaining.substring(i + 1, endIndex))
                    }
                    remaining = remaining.substring(endIndex + 1)
                    i = 0
                } else {
                    i++
                }
            }
            // Inline code: `code`
            remaining.substring(i).startsWith("`") && !remaining.substring(i).startsWith("```") -> {
                val endIndex = remaining.indexOf("`", i + 1)
                if (endIndex != -1) {
                    append(remaining.substring(0, i))
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackgroundColor,
                            fontSize = 13.sp
                        )
                    ) {
                        append(" ${remaining.substring(i + 1, endIndex)} ")
                    }
                    remaining = remaining.substring(endIndex + 1)
                    i = 0
                } else {
                    i++
                }
            }
            // Links: [text](url)
            remaining.substring(i).startsWith("[") -> {
                val linkMatch = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)").find(remaining.substring(i))
                if (linkMatch != null && linkMatch.range.first == 0) {
                    append(remaining.substring(0, i))
                    val linkText = linkMatch.groupValues[1]
                    val url = linkMatch.groupValues[2]
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(linkText)
                        }
                    }
                    remaining = remaining.substring(i + linkMatch.value.length)
                    i = 0
                } else {
                    i++
                }
            }
            else -> i++
        }
    }

    // Append remaining text
    if (remaining.isNotEmpty()) {
        append(remaining)
    }
}

private fun findMatchingDelimiter(text: String, startIndex: Int, delimiter: String): Int {
    var i = startIndex
    while (i < text.length) {
        if (text.substring(i).startsWith(delimiter) &&
            (delimiter.length == 1 || !text.substring(i).startsWith(delimiter + delimiter))) {
            // Make sure it's not escaped and has content before it
            if (i > startIndex && text[i - 1] != ' ' && text[i - 1] != '\n') {
                return i
            }
        }
        i++
    }
    return -1
}
