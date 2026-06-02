package com.lin.hippyagent.core.skill.index

import com.lin.hippyagent.core.skill.SkillIndex
import com.lin.hippyagent.core.skill.SkillIndexEntry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillIndexManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: SkillIndexManager
    private lateinit var skillsDir: File

    @Before
    fun setup() {
        skillsDir = tempFolder.newFolder("skills")
        manager = SkillIndexManager(skillsDir)
    }

    @Test
    fun loadIndexReturnsEmptyWhenNoFileExists() {
        val index = manager.loadIndex()
        assertTrue(index.skills.isEmpty())
    }

    @Test
    fun saveIndexAndLoadIndexRoundtrip() {
        val index = SkillIndex(
            version = 12345L,
            skills = mapOf(
                "test" to SkillIndexEntry(
                    id = "test",
                    name = "Test Skill",
                    description = "desc",
                    version = "1.0.0"
                )
            )
        )
        manager.saveIndex(index)
        val loaded = manager.loadIndex()
        assertEquals(1, loaded.skills.size)
        assertEquals("test", loaded.skills["test"]?.id)
        assertEquals("Test Skill", loaded.skills["test"]?.name)
        assertEquals(12345L, loaded.version)
    }

    @Test
    fun saveIndexAndLoadIndexWithMultipleSkills() {
        val entries = mapOf(
            "skill-a" to SkillIndexEntry(id = "skill-a", name = "A", description = "a", version = "1.0.0"),
            "skill-b" to SkillIndexEntry(id = "skill-b", name = "B", description = "b", version = "2.0.0")
        )
        val index = SkillIndex(version = 999L, skills = entries)
        manager.saveIndex(index)
        val loaded = manager.loadIndex()
        assertEquals(2, loaded.skills.size)
        assertEquals("A", loaded.skills["skill-a"]?.name)
        assertEquals("B", loaded.skills["skill-b"]?.name)
    }

    @Test
    fun getManifestReturnsNullForNonExistentSkill() {
        assertNull(manager.getManifest("nonexistent"))
    }

    @Test
    fun getSkillDirReturnsNullForNonExistentSkill() {
        assertNull(manager.getSkillDir("nonexistent"))
    }

    @Test
    fun getSkillDirReturnsDirectoryWhenSkillExists() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        skillDir.resolve("SKILL.md").writeText("---\nname: My Skill\n---\nContent")
        val dir = manager.getSkillDir("my-skill")
        assertNotNull(dir)
        assertEquals("my-skill", dir?.name)
    }

    @Test
    fun getManifestReturnsManifestFromSkillMdFrontmatter() {
        val content = """---
name: My Skill
description: A description
version: 2.0.0
---
Skill content here"""
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        skillDir.resolve("SKILL.md").writeText(content)

        val manifest = manager.getManifest("my-skill")
        assertNotNull(manifest)
        assertEquals("My Skill", manifest?.name)
        assertEquals("A description", manifest?.description)
        assertEquals("2.0.0", manifest?.version)
    }

    @Test
    fun getManifestReturnsCachedManifestWhenMtimeMatches() {
        val content = """---
name: Cached Skill
description: desc
---
Content"""
        val skillDir = skillsDir.resolve("cached-skill")
        skillDir.mkdirs()
        val skillMd = skillDir.resolve("SKILL.md")
        skillMd.writeText(content)

        val index = SkillIndex(
            version = 1L,
            skills = mapOf(
                "cached-skill" to SkillIndexEntry(
                    id = "cached-skill",
                    name = "Cached Skill",
                    description = "desc",
                    version = "1.0.0",
                    manifestMtime = skillMd.lastModified(),
                    manifestJson = """{"name":"Cached Skill","description":"desc","version":"1.0.0","source":"builtin","tools":[],"triggers":{"keywords":[],"fileExtensions":[],"scenarios":[],"shouldUse":[],"shouldNotUse":[]},"requires":{"bins":[],"envs":[],"minApiLevel":21},"dependencies":[],"permissions":[],"channels":["all"],"protected":false}"""
                )
            )
        )
        manager.saveIndex(index)
        val manifest = manager.getManifest("cached-skill")
        assertNotNull(manifest)
        assertEquals("Cached Skill", manifest?.name)
    }

    @Test
    fun invalidateClearsCachedIndex() {
        val index = SkillIndex(
            version = 100L,
            skills = mapOf(
                "a" to SkillIndexEntry(id = "a", name = "A", description = "d", version = "1.0.0")
            )
        )
        manager.saveIndex(index)
        val first = manager.loadIndex()
        assertEquals(1, first.skills.size)
        manager.invalidate()
        val second = manager.loadIndex()
        assertEquals(1, second.skills.size)
        assertNotSame(first, second)
    }

    @Test
    fun parseSkillInfoReadsSkillMdFrontmatter() {
        val content = """---
name: My Skill
description: A description
version: 2.0.0
---
Some content"""
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        skillDir.resolve("SKILL.md").writeText(content)

        val skillInfo = manager.parseSkillInfo(skillDir)
        assertEquals("my-skill", skillInfo.id)
        assertEquals("My Skill", skillInfo.name)
        assertEquals("A description", skillInfo.description)
        assertEquals("2.0.0", skillInfo.version)
        assertFalse(skillInfo.isBuiltin)
    }

    @Test
    fun parseSkillInfoReadsBuiltinFlag() {
        val content = """---
name: Built-in
builtin: true
---
Content"""
        val skillDir = skillsDir.resolve("builtin-skill")
        skillDir.mkdirs()
        skillDir.resolve("SKILL.md").writeText(content)

        val skillInfo = manager.parseSkillInfo(skillDir)
        assertTrue(skillInfo.isBuiltin)
    }

    @Test(expected = IllegalStateException::class)
    fun parseSkillInfoThrowsWhenSkillMdMissing() {
        val skillDir = skillsDir.resolve("no-skill")
        skillDir.mkdirs()
        manager.parseSkillInfo(skillDir)
    }

    @Test
    fun loadIndexReReadsFromDiskAfterInvalidate() {
        val index = SkillIndex(
            version = 50L,
            skills = mapOf("x" to SkillIndexEntry(id = "x", name = "X", description = "d", version = "1.0.0"))
        )
        manager.saveIndex(index)
        val first = manager.loadIndex()
        assertNotNull(first.skills["x"])
        manager.invalidate()
        val second = manager.loadIndex()
        assertNotNull(second.skills["x"])
        assertNotSame(first, second)
    }
}
