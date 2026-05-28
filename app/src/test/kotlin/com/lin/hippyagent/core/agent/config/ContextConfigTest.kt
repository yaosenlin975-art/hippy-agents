package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `test light context config defaults`() {
        val config = LightContextConfig()
        assertEquals("dialog", config.dialogPath)
        assertEquals(4.0f, config.tokenCountEstimateDivisor, 0.001f)
    }

    @Test
    fun `test context compact config defaults`() {
        val config = ContextCompactConfig()
        assertEquals(true, config.enabled)
        assertEquals(0.8f, config.compactThresholdRatio, 0.001f)
        assertEquals(0.1f, config.reserveThresholdRatio, 0.001f)
        assertEquals(true, config.compactWithThinkingBlock)
        assertEquals(true, config.compactionFallbackEnabled)
        assertEquals(0.6f, config.compactionFallbackReserveRatio, 0.001f)
    }

    @Test
    fun `test tool result pruning config defaults`() {
        val config = ToolResultPruningConfig()
        assertEquals(true, config.enabled)
        assertEquals(2, config.pruningRecentN)
        assertEquals(3000, config.pruningOldMsgMaxBytes)
        assertEquals(50000, config.pruningRecentMsgMaxBytes)
        assertEquals(5, config.offloadRetentionDays)
        assertEquals(listOf(".md"), config.exemptFileExtensions)
        assertEquals(listOf("chat_with_agent"), config.exemptToolNames)
    }

    @Test
    fun `test full context config serialization`() {
        val config = LightContextConfig(
            dialogPath = "conversations",
            tokenCountEstimateDivisor = 3.5f,
            contextCompactConfig = ContextCompactConfig(enabled = false),
            toolResultPruningConfig = ToolResultPruningConfig(
                enabled = true,
                pruningRecentN = 5
            )
        )
        val jsonString = json.encodeToString(config)
        val decoded = json.decodeFromString<LightContextConfig>(jsonString)
        assertEquals("conversations", decoded.dialogPath)
        assertEquals(3.5f, decoded.tokenCountEstimateDivisor, 0.001f)
        assertEquals(false, decoded.contextCompactConfig.enabled)
        assertEquals(5, decoded.toolResultPruningConfig.pruningRecentN)
    }

    @Test
    fun `test compaction fallback config serialization`() {
        val config = ContextCompactConfig(
            compactionFallbackEnabled = false,
            compactionFallbackReserveRatio = 0.8f
        )
        val jsonString = json.encodeToString(config)
        val decoded = json.decodeFromString<ContextCompactConfig>(jsonString)
        assertEquals(false, decoded.compactionFallbackEnabled)
        assertEquals(0.8f, decoded.compactionFallbackReserveRatio, 0.001f)
    }
}
