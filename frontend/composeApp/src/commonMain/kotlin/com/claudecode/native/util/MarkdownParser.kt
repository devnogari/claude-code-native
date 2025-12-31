package com.claudecode.native.util

/**
 * Markdown parser for converting markdown text to structured blocks.
 * Separated from UI for testability.
 */
object MarkdownParser {

    /**
     * Sealed class representing markdown blocks.
     */
    sealed class Block {
        data class CodeBlock(val code: String, val language: String?) : Block()
        data class Text(val content: String) : Block()
    }

    /**
     * Represents inline formatting within text.
     */
    sealed class InlineElement {
        data class Plain(val text: String) : InlineElement()
        data class Bold(val text: String) : InlineElement()
        data class Italic(val text: String) : InlineElement()
        data class Code(val text: String) : InlineElement()
        data class Link(val text: String, val url: String) : InlineElement()
        data class Header(val text: String, val level: Int) : InlineElement()
        data class ListItem(val text: String, val ordered: Boolean, val number: Int? = null) : InlineElement()
    }

    /**
     * Parses markdown text into code blocks and regular text blocks.
     */
    fun parseBlocks(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val codeBlockRegex = Regex("```(\\w*)\\n?([\\s\\S]*?)```", RegexOption.MULTILINE)

        var lastIndex = 0
        codeBlockRegex.findAll(text).forEach { match ->
            // Add text before the code block
            if (match.range.first > lastIndex) {
                val textContent = text.substring(lastIndex, match.range.first).trim()
                if (textContent.isNotEmpty()) {
                    blocks.add(Block.Text(textContent))
                }
            }

            // Add the code block
            val language = match.groupValues[1].takeIf { it.isNotBlank() }
            val code = match.groupValues[2]
            blocks.add(Block.CodeBlock(code, language))

            lastIndex = match.range.last + 1
        }

        // Add remaining text
        if (lastIndex < text.length) {
            val textContent = text.substring(lastIndex).trim()
            if (textContent.isNotEmpty()) {
                blocks.add(Block.Text(textContent))
            }
        }

        // If no blocks were created, treat entire text as regular text
        if (blocks.isEmpty() && text.isNotBlank()) {
            blocks.add(Block.Text(text))
        }

        return blocks
    }

    /**
     * Parses inline markdown elements from a text string.
     * Returns a list of inline elements in order.
     */
    fun parseInlineElements(text: String): List<InlineElement> {
        val elements = mutableListOf<InlineElement>()
        val lines = text.split('\n')

        for (line in lines) {
            when {
                // Headers
                line.startsWith("### ") -> {
                    elements.addAll(parseLineWithFormatting(line.removePrefix("### "), wrapIn = { InlineElement.Header(it, 3) }))
                }
                line.startsWith("## ") -> {
                    elements.addAll(parseLineWithFormatting(line.removePrefix("## "), wrapIn = { InlineElement.Header(it, 2) }))
                }
                line.startsWith("# ") -> {
                    elements.addAll(parseLineWithFormatting(line.removePrefix("# "), wrapIn = { InlineElement.Header(it, 1) }))
                }
                // Unordered lists
                line.startsWith("- ") || line.startsWith("* ") -> {
                    elements.add(InlineElement.ListItem(line.substring(2), ordered = false))
                }
                // Ordered lists
                line.matches(Regex("^\\d+\\. .*")) -> {
                    val match = Regex("^(\\d+)\\. (.*)").find(line)
                    if (match != null) {
                        elements.add(InlineElement.ListItem(
                            match.groupValues[2],
                            ordered = true,
                            number = match.groupValues[1].toIntOrNull()
                        ))
                    } else {
                        elements.addAll(parseLineFormatting(line))
                    }
                }
                else -> {
                    elements.addAll(parseLineFormatting(line))
                }
            }
        }

        return elements
    }

    private fun parseLineWithFormatting(text: String, wrapIn: (String) -> InlineElement): List<InlineElement> {
        // For headers, just return the header element with the text
        return listOf(wrapIn(text))
    }

    /**
     * Parses inline formatting (bold, italic, code, links) from a single line.
     */
    fun parseLineFormatting(line: String): List<InlineElement> {
        val elements = mutableListOf<InlineElement>()
        var remaining = line
        var plainBuffer = StringBuilder()

        fun flushPlain() {
            if (plainBuffer.isNotEmpty()) {
                elements.add(InlineElement.Plain(plainBuffer.toString()))
                plainBuffer = StringBuilder()
            }
        }

        var i = 0
        while (i < remaining.length) {
            when {
                // Bold: **text** or __text__
                remaining.substring(i).startsWith("**") -> {
                    val endIndex = remaining.indexOf("**", i + 2)
                    if (endIndex != -1) {
                        plainBuffer.append(remaining.substring(0, i))
                        flushPlain()
                        elements.add(InlineElement.Bold(remaining.substring(i + 2, endIndex)))
                        remaining = remaining.substring(endIndex + 2)
                        i = 0
                    } else {
                        i++
                    }
                }
                remaining.substring(i).startsWith("__") -> {
                    val endIndex = remaining.indexOf("__", i + 2)
                    if (endIndex != -1) {
                        plainBuffer.append(remaining.substring(0, i))
                        flushPlain()
                        elements.add(InlineElement.Bold(remaining.substring(i + 2, endIndex)))
                        remaining = remaining.substring(endIndex + 2)
                        i = 0
                    } else {
                        i++
                    }
                }
                // Italic: *text* or _text_
                remaining.substring(i).startsWith("*") && !remaining.substring(i).startsWith("**") -> {
                    val endIndex = findMatchingDelimiter(remaining, i + 1, "*")
                    if (endIndex != -1) {
                        plainBuffer.append(remaining.substring(0, i))
                        flushPlain()
                        elements.add(InlineElement.Italic(remaining.substring(i + 1, endIndex)))
                        remaining = remaining.substring(endIndex + 1)
                        i = 0
                    } else {
                        i++
                    }
                }
                remaining.substring(i).startsWith("_") && !remaining.substring(i).startsWith("__") -> {
                    val endIndex = findMatchingDelimiter(remaining, i + 1, "_")
                    if (endIndex != -1) {
                        plainBuffer.append(remaining.substring(0, i))
                        flushPlain()
                        elements.add(InlineElement.Italic(remaining.substring(i + 1, endIndex)))
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
                        plainBuffer.append(remaining.substring(0, i))
                        flushPlain()
                        elements.add(InlineElement.Code(remaining.substring(i + 1, endIndex)))
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
                        plainBuffer.append(remaining.substring(0, i))
                        flushPlain()
                        elements.add(InlineElement.Link(linkMatch.groupValues[1], linkMatch.groupValues[2]))
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
            plainBuffer.append(remaining)
        }
        flushPlain()

        return elements
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
}
