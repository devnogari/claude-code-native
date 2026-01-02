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
import com.claudecode.native.util.openUrl

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
 * Supports diff syntax highlighting with colored +/- lines.
 */
@Composable
private fun CodeBlockView(
    code: String,
    language: String?,
    backgroundColor: Color,
    textColor: Color
) {
    // Detect if this is a diff block
    val isDiff = language == "diff" || code.lines().any { line ->
        line.startsWith("+") || line.startsWith("-") || line.startsWith("@@")
    }

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
            if (isDiff) {
                // Render diff with colored lines
                DiffCodeBlock(
                    code = code.trimEnd(),
                    textColor = textColor
                )
            } else {
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
}

/**
 * Renders diff code with colored lines.
 * - Lines starting with + are green (additions)
 * - Lines starting with - are red (deletions)
 * - Lines starting with @@ are blue (hunk headers)
 */
@Composable
private fun DiffCodeBlock(
    code: String,
    textColor: Color
) {
    val additionColor = Color(0xFF22C55E) // Green
    val deletionColor = Color(0xFFEF4444) // Red
    val hunkHeaderColor = Color(0xFF60A5FA) // Blue
    val additionBgColor = Color(0xFF22C55E).copy(alpha = 0.15f)
    val deletionBgColor = Color(0xFFEF4444).copy(alpha = 0.15f)

    Column {
        code.lines().forEach { line ->
            val (lineColor, bgColor) = when {
                line.startsWith("+++") || line.startsWith("---") -> textColor.copy(alpha = 0.7f) to Color.Transparent
                line.startsWith("+") -> additionColor to additionBgColor
                line.startsWith("-") -> deletionColor to deletionBgColor
                line.startsWith("@@") -> hunkHeaderColor to Color.Transparent
                else -> textColor to Color.Transparent
            }

            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp
                ),
                color = lineColor,
                modifier = if (bgColor != Color.Transparent) {
                    Modifier
                        .fillMaxWidth()
                        .background(bgColor)
                        .padding(horizontal = 4.dp)
                } else {
                    Modifier.padding(horizontal = 4.dp)
                }
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

        // Process headers and list items first
        val lines = currentText.split('\n')
        val processedLines = mutableListOf<String>()

        for (line in lines) {
            val trimmedLine = line.trimStart()
            val leadingSpaces = line.length - trimmedLine.length
            val indent = " ".repeat(leadingSpaces)

            when {
                trimmedLine.startsWith("### ") -> {
                    appendLine(buildStyledLine(trimmedLine.removePrefix("### "), textColor, linkColor, codeBackgroundColor, baseStyle, FontWeight.Bold, 1.1f))
                }
                trimmedLine.startsWith("## ") -> {
                    appendLine(buildStyledLine(trimmedLine.removePrefix("## "), textColor, linkColor, codeBackgroundColor, baseStyle, FontWeight.Bold, 1.2f))
                }
                trimmedLine.startsWith("# ") -> {
                    appendLine(buildStyledLine(trimmedLine.removePrefix("# "), textColor, linkColor, codeBackgroundColor, baseStyle, FontWeight.Bold, 1.4f))
                }
                trimmedLine.startsWith("- ") -> {
                    processedLines.add("$indent  • ${trimmedLine.substring(2)}")
                }
                trimmedLine.startsWith("* ") -> {
                    processedLines.add("$indent  • ${trimmedLine.substring(2)}")
                }
                trimmedLine.matches(Regex("^\\d+\\. .*")) -> {
                    val match = Regex("^(\\d+)\\. (.*)").find(trimmedLine)
                    if (match != null) {
                        processedLines.add("$indent  ${match.groupValues[1]}. ${match.groupValues[2]}")
                    } else {
                        processedLines.add(line)
                    }
                }
                else -> processedLines.add(line)
            }
        }

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
                    withLink(
                        LinkAnnotation.Url(
                            url = url,
                            linkInteractionListener = { openUrl(url) }
                        )
                    ) {
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
            // Bare URLs: https://... or http://...
            remaining.substring(i).startsWith("http://") || remaining.substring(i).startsWith("https://") -> {
                // Match URL until whitespace, closing paren, or end of string
                val urlMatch = Regex("https?://[^\\s)\\]>\"']+").find(remaining.substring(i))
                if (urlMatch != null && urlMatch.range.first == 0) {
                    append(remaining.substring(0, i))
                    val url = urlMatch.value.trimEnd('.', ',', ':', ';', '!', '?') // Remove trailing punctuation
                    withLink(
                        LinkAnnotation.Url(
                            url = url,
                            linkInteractionListener = { openUrl(url) }
                        )
                    ) {
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(url)
                        }
                    }
                    // Adjust remaining to account for trimmed punctuation
                    val actualLength = url.length
                    remaining = remaining.substring(i + actualLength)
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
            // Make sure there's content between the delimiters
            if (i > startIndex) {
                return i
            }
        }
        i++
    }
    return -1
}
