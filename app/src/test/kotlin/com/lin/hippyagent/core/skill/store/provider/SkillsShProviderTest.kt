package com.lin.hippyagent.core.skill.store.provider

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.skill.store.SkillSource
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SkillsShProviderTest {
    private lateinit var provider: SkillsShProvider

    @Before
    fun setup() {
        provider = SkillsShProvider(mockk<LinuxManager>(relaxed = true))
    }

    @Test
    fun `parseText returns items from valid output`() {
        val text = """
            owner/repo@skill-name 150 installs
            another/user@another-skill 42 installs
        """.trimIndent()
        val result = provider.parseText(text)
        assertEquals(2, result.size)
        assertEquals("owner/repo@skill-name", result[0].identifier)
        assertEquals("skill-name", result[0].name)
        assertEquals("owner", result[0].author)
        assertEquals(150L, result[0].installCount)
        assertEquals(SkillSource.SKILLS_SH, result[0].source)
        assertEquals("another/user@another-skill", result[1].identifier)
        assertEquals(42L, result[1].installCount)
    }

    @Test
    fun `parseText returns empty list for empty input`() {
        val result = provider.parseText("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseText ignores non-matching lines`() {
        val text = "some random text\nno match here"
        val result = provider.parseText(text)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseText handles singular install`() {
        val text = "owner/repo@skill 1 installs"
        val result = provider.parseText(text)
        assertEquals(1, result.size)
        assertEquals(1L, result[0].installCount)
    }

    @Test
    fun `parseText mixes matching and non-matching lines`() {
        val text = """
            header line
            owner/repo@skill 10 installs
            separator
            another/user@other 5 installs
            footer
        """.trimIndent()
        val result = provider.parseText(text)
        assertEquals(2, result.size)
        assertEquals("owner/repo@skill", result[0].identifier)
        assertEquals("another/user@other", result[1].identifier)
    }

    @Test
    fun `parseText defaults installCount to 0 when not parseable`() {
        val text = "owner/repo@skill NaN installs"
        val result = provider.parseText(text)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseText generates correct installCommand`() {
        val text = "owner/repo@my-skill 10 installs"
        val result = provider.parseText(text)
        assertEquals(1, result.size)
        assertTrue(result[0].installCommand.contains("owner/repo@my-skill"))
    }
}
