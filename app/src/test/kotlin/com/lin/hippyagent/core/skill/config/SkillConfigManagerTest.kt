package com.lin.hippyagent.core.skill.config

import com.lin.hippyagent.core.skill.SkillConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillConfigManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var manager: SkillConfigManager

    @Before
    fun setup() {
        val skillsDir = tempFolder.newFolder("skills")
        manager = SkillConfigManager(skillsDir)
    }

    @Test
    fun `load returns default config when file does not exist`() {
        val config = manager.load("test-skill")
        assertEquals("test-skill", config.skillId)
        assertTrue(config.secrets.isEmpty())
        assertTrue(config.settings.isEmpty())
    }

    @Test
    fun `save and load roundtrip`() {
        val config = SkillConfig(
            skillId = "test-skill",
            secrets = mapOf("api_key" to "secret123"),
            settings = mapOf("color" to "blue")
        )
        manager.save(config)
        val loaded = manager.load("test-skill")
        assertEquals("secret123", loaded.secrets["api_key"])
        assertEquals("blue", loaded.settings["color"])
    }

    @Test
    fun `getSecret returns null for non-existent key`() {
        assertNull(manager.getSecret("test-skill", "nonexistent"))
    }

    @Test
    fun `saveSecret persists secret`() {
        manager.saveSecret("test-skill", "token", "abc123")
        assertEquals("abc123", manager.getSecret("test-skill", "token"))
    }

    @Test
    fun `saveSecret preserves existing secrets`() {
        manager.saveSecret("test-skill", "key1", "val1")
        manager.saveSecret("test-skill", "key2", "val2")
        assertEquals("val1", manager.getSecret("test-skill", "key1"))
        assertEquals("val2", manager.getSecret("test-skill", "key2"))
    }

    @Test
    fun `save overwrites existing config`() {
        val config1 = SkillConfig(skillId = "test", secrets = mapOf("old" to "value"))
        manager.save(config1)
        val config2 = SkillConfig(skillId = "test", secrets = mapOf("new" to "updated"))
        manager.save(config2)
        val loaded = manager.load("test")
        assertNull(loaded.secrets["old"])
        assertEquals("updated", loaded.secrets["new"])
    }

    @Test
    fun `getSecret returns null for non-existent skill`() {
        assertNull(manager.getSecret("nonexistent", "key"))
    }

    @Test
    fun `saveSecret creates config dir if missing`() {
        manager.saveSecret("new-skill", "key", "val")
        assertEquals("val", manager.getSecret("new-skill", "key"))
    }
}
