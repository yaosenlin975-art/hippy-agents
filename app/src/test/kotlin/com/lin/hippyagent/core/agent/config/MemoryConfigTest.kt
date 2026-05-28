package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class MemoryConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `test reme light memory config defaults`() {
        val config = ReMeLightMemoryConfig()
        assertEquals(true, config.summarizeWhenCompact)
        assertNull(config.autoMemoryInterval)
        assertEquals("0 23 * * *", config.dreamCron)
        assertEquals(false, config.rebuildMemoryIndexOnStart)
        assertEquals(false, config.recursiveFileWatcher)
    }

    @Test
    fun `test auto memory search config defaults`() {
        val config = AutoMemorySearchConfig()
        assertEquals(false, config.enabled)
        assertEquals(2, config.maxResults)
        assertEquals(0.3f, config.minScore)
        assertEquals(10.0f, config.timeout)
    }

    @Test
    fun `test embedding model config defaults`() {
        val config = EmbeddingModelConfig()
        assertEquals("openai", config.backend)
        assertEquals("", config.apiKey)
        assertEquals("", config.baseUrl)
        assertEquals("", config.modelName)
        assertEquals(1024, config.dimensions)
        assertEquals(true, config.enableCache)
        assertEquals(3000, config.maxCacheSize)
        assertEquals(8192, config.maxInputLength)
        assertEquals(10, config.maxBatchSize)
    }

    @Test
    fun `test full memory config serialization`() {
        val config = ReMeLightMemoryConfig(
            summarizeWhenCompact = false,
            autoMemoryInterval = 60,
            dreamCron = "0 */6 * * *",
            autoMemorySearchConfig = AutoMemorySearchConfig(enabled = true),
            rebuildMemoryIndexOnStart = true
        )
        val jsonString = json.encodeToString(config)
        val decoded = json.decodeFromString<ReMeLightMemoryConfig>(jsonString)
        assertEquals(false, decoded.summarizeWhenCompact)
        assertEquals(60, decoded.autoMemoryInterval)
        assertEquals("0 */6 * * *", decoded.dreamCron)
        assertEquals(true, decoded.autoMemorySearchConfig.enabled)
        assertEquals(true, decoded.rebuildMemoryIndexOnStart)
    }
}

