package com.claudecode.native.ui.viewmodel

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for ToolTracker.
 */
class ToolTrackerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `clearState should clear all tracked tools`() = runTest {
        val tracker = ToolTracker()

        // Add some tools
        val content = json.parseToJsonElement("""
            [
                {"type": "tool_use", "id": "tool1", "name": "Read", "input": {"path": "/test"}},
                {"type": "tool_result", "tool_use_id": "tool1", "content": "file contents"}
            ]
        """.trimIndent()) as JsonArray

        tracker.addToolsFromContent(content)

        // Verify tools are tracked
        var toolUses = tracker.getMatchedToolUses()
        assertEquals(1, toolUses.size)

        // Clear and verify empty
        tracker.clearState()
        toolUses = tracker.getMatchedToolUses()
        assertTrue(toolUses.isEmpty())
    }

    @Test
    fun `addToolsFromContent should track tool_use`() = runTest {
        val tracker = ToolTracker()

        val content = json.parseToJsonElement("""
            [
                {"type": "tool_use", "id": "tool123", "name": "Bash", "input": {"command": "ls -la"}}
            ]
        """.trimIndent()) as JsonArray

        tracker.addToolsFromContent(content)

        val toolUses = tracker.getMatchedToolUses()
        assertEquals(1, toolUses.size)
        assertEquals("tool123", toolUses["tool123"]?.id)
        assertEquals("Bash", toolUses["tool123"]?.name)
        assertNull(toolUses["tool123"]?.result)
    }

    @Test
    fun `addToolsFromContent should match tool_result to tool_use`() = runTest {
        val tracker = ToolTracker()

        // First add tool_use
        val toolUse = json.parseToJsonElement("""
            [{"type": "tool_use", "id": "tool456", "name": "Read", "input": {"path": "/file"}}]
        """.trimIndent()) as JsonArray
        tracker.addToolsFromContent(toolUse)

        // Then add tool_result
        val toolResult = json.parseToJsonElement("""
            [{"type": "tool_result", "tool_use_id": "tool456", "content": "file content here"}]
        """.trimIndent()) as JsonArray
        tracker.addToolsFromContent(toolResult)

        val toolUses = tracker.getMatchedToolUses()
        assertEquals(1, toolUses.size)
        assertEquals("file content here", toolUses["tool456"]?.result)
    }

    @Test
    fun `addToolsFromContent should handle tool_result before tool_use`() = runTest {
        val tracker = ToolTracker()

        // Add tool_result first (can happen with message ordering)
        val toolResult = json.parseToJsonElement("""
            [{"type": "tool_result", "tool_use_id": "tool789", "content": "result first"}]
        """.trimIndent()) as JsonArray
        tracker.addToolsFromContent(toolResult)

        // Then add tool_use
        val toolUse = json.parseToJsonElement("""
            [{"type": "tool_use", "id": "tool789", "name": "Grep", "input": {"pattern": "test"}}]
        """.trimIndent()) as JsonArray
        tracker.addToolsFromContent(toolUse)

        val toolUses = tracker.getMatchedToolUses()
        assertEquals(1, toolUses.size)
        assertEquals("Grep", toolUses["tool789"]?.name)
        assertEquals("result first", toolUses["tool789"]?.result)
    }

    @Test
    fun `addToolsFromContent should track error results`() = runTest {
        val tracker = ToolTracker()

        val content = json.parseToJsonElement("""
            [
                {"type": "tool_use", "id": "toolErr", "name": "Bash", "input": {"command": "fail"}},
                {"type": "tool_result", "tool_use_id": "toolErr", "content": "Command failed", "is_error": "true"}
            ]
        """.trimIndent()) as JsonArray

        tracker.addToolsFromContent(content)

        val toolUses = tracker.getMatchedToolUses()
        assertEquals(true, toolUses["toolErr"]?.isError)
        assertEquals("Command failed", toolUses["toolErr"]?.result)
    }

    @Test
    fun `updateBlocksWithToolResults should add results to tool blocks`() = runTest {
        val tracker = ToolTracker()

        // Setup tracked tools
        val content = json.parseToJsonElement("""
            [
                {"type": "tool_use", "id": "toolA", "name": "Read", "input": {}},
                {"type": "tool_result", "tool_use_id": "toolA", "content": "file contents"}
            ]
        """.trimIndent()) as JsonArray
        tracker.addToolsFromContent(content)

        // Create blocks without results
        val blocks = listOf(
            ContentBlock.Text("Some text"),
            ContentBlock.Tool(ToolUseInfo(id = "toolA", name = "Read", summary = "Reading file")),
            ContentBlock.Text("More text")
        )

        // Update blocks
        val updatedBlocks = tracker.updateBlocksWithToolResults(blocks)

        // Verify tool block has result
        assertEquals(3, updatedBlocks.size)
        val toolBlock = updatedBlocks[1] as ContentBlock.Tool
        assertEquals("file contents", toolBlock.info.result)
    }

    @Test
    fun `updateBlocksWithToolResultsSync should work without mutex`() = runTest {
        val tracker = ToolTracker()

        val toolUses = mapOf(
            "toolSync" to ToolUseInfo(
                id = "toolSync",
                name = "Bash",
                summary = "Running command",
                result = "output",
                isError = false
            )
        )

        val blocks = listOf(
            ContentBlock.Tool(ToolUseInfo(id = "toolSync", name = "Bash", summary = "Running command"))
        )

        val updatedBlocks = tracker.updateBlocksWithToolResultsSync(blocks, toolUses)

        val toolBlock = updatedBlocks[0] as ContentBlock.Tool
        assertEquals("output", toolBlock.info.result)
    }

    @Test
    fun `extractToolsToMaps should populate external maps`() = runTest {
        val tracker = ToolTracker()

        val toolUses = mutableMapOf<String, ToolUseInfo>()
        val toolResults = mutableMapOf<String, Pair<String, Boolean>>()

        val content = json.parseToJsonElement("""
            [
                {"type": "tool_use", "id": "extTool", "name": "Write", "input": {"path": "/file"}},
                {"type": "tool_result", "tool_use_id": "extTool", "content": "written"}
            ]
        """.trimIndent()) as JsonArray

        tracker.extractToolsToMaps(content, toolUses, toolResults)

        assertEquals(1, toolUses.size)
        assertEquals(1, toolResults.size)
        assertEquals("Write", toolUses["extTool"]?.name)
        assertEquals("written", toolResults["extTool"]?.first)
    }

    @Test
    fun `matchResultsInMaps should match external maps`() = runTest {
        val tracker = ToolTracker()

        val toolUses = mutableMapOf(
            "matchTool" to ToolUseInfo(id = "matchTool", name = "Edit", summary = "Editing")
        )
        val toolResults = mapOf(
            "matchTool" to Pair("Edit complete", false)
        )

        tracker.matchResultsInMaps(toolUses, toolResults)

        assertEquals("Edit complete", toolUses["matchTool"]?.result)
    }

    @Test
    fun `mergeAndMatchTools should combine external and internal tracking`() = runTest {
        val tracker = ToolTracker()

        // Add internal tool
        val internalContent = json.parseToJsonElement("""
            [{"type": "tool_use", "id": "internal", "name": "Glob", "input": {}}]
        """.trimIndent()) as JsonArray
        tracker.addToolsFromContent(internalContent)

        // Merge external tools
        val externalUses = mapOf(
            "external" to ToolUseInfo(id = "external", name = "Grep", summary = "Searching")
        )
        val externalResults = mapOf(
            "internal" to Pair("glob result", false),
            "external" to Pair("grep result", false)
        )

        tracker.mergeAndMatchTools(externalUses, externalResults)

        val allTools = tracker.getMatchedToolUses()
        assertEquals(2, allTools.size)
        assertEquals("glob result", allTools["internal"]?.result)
        assertEquals("grep result", allTools["external"]?.result)
    }

    @Test
    fun `addToolsFromContents should process multiple contents`() = runTest {
        val tracker = ToolTracker()

        val contents = listOf(
            json.parseToJsonElement("""[{"type": "tool_use", "id": "t1", "name": "Read", "input": {}}]""") as JsonArray,
            json.parseToJsonElement("""[{"type": "tool_use", "id": "t2", "name": "Write", "input": {}}]""") as JsonArray,
            json.parseToJsonElement("""[{"type": "tool_result", "tool_use_id": "t1", "content": "r1"}]""") as JsonArray
        )

        tracker.addToolsFromContents(contents)

        val toolUses = tracker.getMatchedToolUses()
        assertEquals(2, toolUses.size)
        assertEquals("r1", toolUses["t1"]?.result)
        assertNull(toolUses["t2"]?.result)
    }
}
