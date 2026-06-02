package com.lin.hippyagent.core.skill.store.provider

import com.lin.hippyagent.core.linux.LinuxManager
import com.lin.hippyagent.core.skill.store.SkillSource
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SkillHubProviderTest {
    private lateinit var provider: SkillHubProvider

    @Before
    fun setup() {
        provider = SkillHubProvider(
            linuxManager = mockk<LinuxManager>(relaxed = true),
            clawHubProvider = mockk<ClawHubProvider>(relaxed = true)
        )
    }

    @Test
    fun `parseHtml extracts items from skill cards`() {
        val html = """
            <html><body>
              <article class="skill-card">
                <a href="/skills/code-review">
                  <h3>Code Review</h3>
                </a>
                <p>Automated PR review tool</p>
                <span class="category">code-quality</span>
                <div>1,234 下载</div>
                <div>4.5 AI 评分</div>
              </article>
              <article class="skill-card">
                <a href="/skills/test-runner">
                  <h3>Test Runner</h3>
                </a>
                <p>Run pytest easily</p>
                <span class="category">testing</span>
                <div>5.6K 下载</div>
                <div>4.50 评分</div>
              </article>
            </body></html>
        """.trimIndent()

        val items = provider.parseHtml(html, query = "")
        assertEquals(2, items.size)
        assertEquals("code-review", items[0].identifier)
        assertEquals("Code Review", items[0].name)
        assertEquals(SkillSource.SKILLHUB, items[0].source)
        assertEquals(1234L, items[0].installCount)
        assertEquals(4.5f, items[0].rating, 0.001f)
        assertEquals(5600L, items[1].installCount)
        assertEquals(4.50f, items[1].rating, 0.001f)
    }

    @Test
    fun `parseHtml returns empty for blank input`() {
        assertTrue(provider.parseHtml("", "").isEmpty())
        assertTrue(provider.parseHtml("   ", "").isEmpty())
    }

    @Test
    fun `parseHtml de-duplicates same slug`() {
        val html = """
            <html><body>
              <article class="skill-card">
                <a href="/skills/dup"><h3>First</h3></a>
              </article>
              <article class="skill-card">
                <a href="/skills/dup"><h3>Duplicate</h3></a>
              </article>
            </body></html>
        """.trimIndent()
        val items = provider.parseHtml(html, "")
        assertEquals(1, items.size)
    }

    @Test
    fun `parseHtml swallows malformed html gracefully`() {
        val html = "<html><body><article class=\"skill-card\"><a href=\"/skills/x\""
        val items = provider.parseHtml(html, "")
        assertNotNull(items)
    }

    @Test
    fun `installCommand uses npx clawhub not clawdhub`() {
        val html = """
            <html><body>
              <article class="skill-card">
                <a href="/skills/foo"><h3>Foo</h3></a>
              </article>
            </body></html>
        """.trimIndent()
        val items = provider.parseHtml(html, "")
        assertEquals(1, items.size)
        assertEquals("npx -y clawhub install foo", items[0].installCommand)
    }

    @Test
    fun `parseHtml extracts millions with M suffix`() {
        val html = """
            <html><body>
              <article class="skill-card">
                <a href="/skills/pop"><h3>Pop</h3></a>
                <p>popular</p>
                <div>2.3M 下载</div>
              </article>
            </body></html>
        """.trimIndent()
        val items = provider.parseHtml(html, "")
        assertEquals(2_300_000L, items[0].installCount)
    }

    @Test
    fun `parseHtml defaults rating to -1 when missing`() {
        val html = """
            <html><body>
              <article class="skill-card">
                <a href="/skills/norating"><h3>NoRating</h3></a>
                <p>desc</p>
                <div>100 下载</div>
              </article>
            </body></html>
        """.trimIndent()
        val items = provider.parseHtml(html, "")
        assertEquals(100L, items[0].installCount)
        assertEquals(-1f, items[0].rating, 0.001f)
    }
}
