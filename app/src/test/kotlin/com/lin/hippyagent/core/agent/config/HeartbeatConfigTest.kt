package com.lin.hippyagent.core.agent.config

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

class HeartbeatConfigTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `test default values`() {
        val config = HeartbeatConfig()
        assertEquals(false, config.enabled)
        assertEquals("6h", config.every)
        assertEquals("main", config.target)
    }

    @Test
    fun `test with active hours`() {
        val config = HeartbeatConfig(
            enabled = true,
            every = "30m",
            target = "last",
            activeHours = ActiveHoursConfig(start = "09:00", end = "23:00")
        )
        val jsonString = json.encodeToString(config)
        val decoded = json.decodeFromString<HeartbeatConfig>(jsonString)
        assertEquals(true, decoded.enabled)
        assertEquals("30m", decoded.every)
        assertEquals("last", decoded.target)
        assertNotNull(decoded.activeHours)
        assertEquals("09:00", decoded.activeHours?.start)
        assertEquals("23:00", decoded.activeHours?.end)
    }

    @Test
    fun `test compatibility with qwenpaw format`() {
        val qwenpawJson = """
        {
            "enabled": true,
            "every": "1h",
            "target": "last",
            "active_hours": {
                "start": "08:00",
                "end": "22:00"
            }
        }
        """.trimIndent()

        val decoded = json.decodeFromString<HeartbeatConfig>(qwenpawJson)
        assertEquals(true, decoded.enabled)
        assertEquals("1h", decoded.every)
        assertEquals("last", decoded.target)
        assertNotNull(decoded.activeHours)
    }
}

