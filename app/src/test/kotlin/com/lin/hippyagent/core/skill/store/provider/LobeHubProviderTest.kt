package com.lin.hippyagent.core.skill.store.provider

import com.lin.hippyagent.core.linux.LinuxManager
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LobeHubProviderTest {
    private lateinit var provider: LobeHubProvider

    @Before
    fun setup() {
        provider = LobeHubProvider(mockk<LinuxManager>(relaxed = true))
    }

    @Test
    fun `parseJson returns items from valid JSON`() {
        val json = """
        {
            "items": [
                {
                    "identifier": "test/skill",
                    "name": "Test Skill",
                    "description": "A test skill",
                    "author": "tester",
                    "category": "utility",
                    "installCount": 100,
                    "ratingAverage": 4.5,
                    "version": "1.0.0",
                    "tags": ["test", "utility"]
                }
            ]
        }
        """.trimIndent()
        val result = provider.parseJson(json)
        assertEquals(1, result.size)
        assertEquals("test/skill", result[0].identifier)
        assertEquals("Test Skill", result[0].name)
        assertEquals("A test skill", result[0].description)
        assertEquals("tester", result[0].author)
        assertEquals("utility", result[0].category)
        assertEquals(100L, result[0].installCount)
        assertEquals(4.5f, result[0].rating, 0.001f)
        assertEquals("1.0.0", result[0].version)
        assertEquals(listOf("test", "utility"), result[0].tags)
        assertTrue(result[0].installCommand.contains("test/skill"))
    }

    @Test
    fun `parseJson returns empty list for empty items`() {
        val json = """{"items": []}"""
        val result = provider.parseJson(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseJson returns empty list for invalid JSON`() {
        val result = provider.parseJson("not json")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseJson handles missing fields gracefully`() {
        val json = """{"items": [{"identifier": "test"}]}"""
        val result = provider.parseJson(json)
        assertEquals(1, result.size)
        assertEquals("test", result[0].identifier)
        assertEquals("", result[0].name)
        assertEquals(0L, result[0].installCount)
    }

    @Test
    fun `parseJson returns empty list when items key is missing`() {
        val json = """{"data": []}"""
        val result = provider.parseJson(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseJson parses author from nested object`() {
        val json = """
        {
            "items": [
                {
                    "identifier": "test/skill",
                    "author": {"name": "nested_author"}
                }
            ]
        }
        """.trimIndent()
        val result = provider.parseJson(json)
        assertEquals(1, result.size)
        assertEquals("nested_author", result[0].author)
    }

    @Test
    fun `parseJson parses multiple items`() {
        val json = """
        {
            "items": [
                {"identifier": "a/one", "name": "One"},
                {"identifier": "b/two", "name": "Two"},
                {"identifier": "c/three", "name": "Three"}
            ]
        }
        """.trimIndent()
        val result = provider.parseJson(json)
        assertEquals(3, result.size)
        assertEquals("a/one", result[0].identifier)
        assertEquals("b/two", result[1].identifier)
        assertEquals("c/three", result[2].identifier)
    }

    @Test
    fun `parseJson parses isValidated and homepage fields`() {
        val json = """
        {
            "items": [
                {
                    "identifier": "val/skill",
                    "isValidated": true,
                    "homepage": "https://example.com"
                }
            ]
        }
        """.trimIndent()
        val result = provider.parseJson(json)
        assertEquals(1, result.size)
        assertTrue(result[0].isValidated)
        assertEquals("https://example.com", result[0].detailUrl)
    }

    @Test
    fun `parseJson parses github stars`() {
        val json = """
        {
            "items": [
                {
                    "identifier": "star/skill",
                    "github": {"stars": 42}
                }
            ]
        }
        """.trimIndent()
        val result = provider.parseJson(json)
        assertEquals(1, result.size)
        assertEquals(42L, result[0].starsCount)
    }
}
