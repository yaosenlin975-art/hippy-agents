package com.lin.hippyagent.core.skill.store.provider

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.skill.store.SkillSource
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClawHubProviderTest {
    private lateinit var provider: ClawHubProvider

    @Before
    fun setup() {
        provider = ClawHubProvider(mockk<LinuxManager>(relaxed = true))
    }

    @Test
    fun `parseText returns items with confidence field`() {
        val text = """
            my-skill  My Skill Name  (0.903)
            another-skill  Another Name  (0.750)
        """.trimIndent()
        val result = provider.parseText(text)
        assertEquals(2, result.size)
        assertEquals("my-skill", result[0].identifier)
        assertEquals("My Skill Name", result[0].name)
        assertEquals(0.903f, result[0].confidence, 0.001f)
        assertEquals(SkillSource.CLAWHUB, result[0].source)
        assertEquals(0L, result[0].installCount)
    }

    @Test
    fun `parseText returns empty list for empty input`() {
        val result = provider.parseText("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseText ignores non-matching lines`() {
        val text = "header\nseparator"
        val result = provider.parseText(text)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `score does not pollute installCount`() {
        val text = "test-skill  Test  (0.500)"
        val result = provider.parseText(text)
        assertEquals(1, result.size)
        assertEquals(0L, result[0].installCount)
        assertEquals(0.500f, result[0].confidence, 0.001f)
    }

    @Test
    fun `parseText generates correct installCommand`() {
        val text = "my-skill  My Skill  (0.800)"
        val result = provider.parseText(text)
        assertEquals(1, result.size)
        assertTrue(result[0].installCommand.contains("my-skill"))
    }

    @Test
    fun `parseText defaults confidence to 0 when score is missing`() {
        val text = "my-skill  My Skill"
        val result = provider.parseText(text)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseText handles multiple entries`() {
        val text = """
            skill-a  Name A  (0.900)
            skill-b  Name B  (0.800)
            skill-c  Name C  (0.700)
        """.trimIndent()
        val result = provider.parseText(text)
        assertEquals(3, result.size)
        assertEquals("skill-a", result[0].identifier)
        assertEquals("skill-b", result[1].identifier)
        assertEquals("skill-c", result[2].identifier)
        assertEquals(0.900f, result[0].confidence, 0.001f)
        assertEquals(0.700f, result[2].confidence, 0.001f)
    }
}
