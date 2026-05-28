package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class RunningConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `test default values`() {
        val config = RunningConfig()
        assertEquals(100, config.maxIters)
        assertEquals(false, config.autoContinueOnTextOnly)
        assertEquals(true, config.llmRetryEnabled)
        assertEquals(3, config.llmRetryMaxRetries)
        assertEquals(1.0f, config.llmRetryBackoffBase)
        assertEquals(10.0f, config.llmRetryBackoffCap)
        assertEquals(10, config.llmMaxConcurrent)
        assertEquals(600, config.llmMaxQpm)
        assertEquals(5.0f, config.llmRateLimitPause)
        assertEquals(1.0f, config.llmRateLimitJitter)
        assertEquals(300.0f, config.llmAcquireTimeout)
        assertEquals(131072, config.maxInputLength)
        assertEquals(10000, config.historyMaxLength)
        assertEquals("light", config.contextManagerBackend)
        assertEquals("remelight", config.memoryManagerBackend)
        assertEquals("memory", config.dailyMemoryDir)
    }

    @Test
    fun `test serialization and deserialization`() {
        val config = RunningConfig(
            maxIters = 50,
            llmRetryEnabled = false,
            contextManagerBackend = "heavy"
        )
        val jsonString = json.encodeToString(config)
        val decoded = json.decodeFromString<RunningConfig>(jsonString)
        assertEquals(50, decoded.maxIters)
        assertEquals(false, decoded.llmRetryEnabled)
        assertEquals("heavy", decoded.contextManagerBackend)
    }

    @Test
    fun `test compatibility with qwenpaw agent_json`() {
        val qwenpawJson = """
        {
            "max_iters": 200,
            "auto_continue_on_text_only": true,
            "llm_retry_enabled": true,
            "llm_retry_max_retries": 5,
            "llm_retry_backoff_base": 2.0,
            "llm_retry_backoff_cap": 20.0,
            "llm_max_concurrent": 5,
            "llm_max_qpm": 300,
            "llm_rate_limit_pause": 10.0,
            "llm_rate_limit_jitter": 2.0,
            "llm_acquire_timeout": 600.0,
            "max_input_length": 262144,
            "history_max_length": 20000,
            "context_manager_backend": "light",
            "memory_manager_backend": "reme_full",
            "daily_memory_dir": "daily_memory"
        }
        """.trimIndent()

        val decoded = json.decodeFromString<RunningConfig>(qwenpawJson)
        assertEquals(200, decoded.maxIters)
        assertEquals(true, decoded.autoContinueOnTextOnly)
        assertEquals(5, decoded.llmRetryMaxRetries)
        assertEquals(262144, decoded.maxInputLength)
        assertEquals("reme_full", decoded.memoryManagerBackend)
    }
}

