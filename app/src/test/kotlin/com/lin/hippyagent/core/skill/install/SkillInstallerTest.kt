package com.lin.hippyagent.core.skill.install

import android.content.Context
import com.lin.hippyagent.core.skill.SkillScanner
import com.lin.hippyagent.core.skill.index.SkillIndexManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SkillInstallerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var installer: SkillInstaller
    private lateinit var skillsDir: File

    @Before
    fun setup() {
        skillsDir = tempFolder.newFolder("skills")
        installer = SkillInstaller(
            context = mockk<Context>(relaxed = true),
            skillsDir = skillsDir,
            skillScanner = SkillScanner(),
            indexManager = SkillIndexManager(skillsDir)
        )
    }

    @Test
    fun enableSkillReturnsFailureForNonExistentSkill() {
        val result = installer.enableSkill("nonexistent")
        assertTrue(result.isFailure)
    }

    @Test
    fun disableSkillReturnsFailureForNonExistentSkill() {
        val result = installer.disableSkill("nonexistent")
        assertTrue(result.isFailure)
    }

    @Test
    fun isEnabledReturnsFalseForNonExistentSkill() {
        assertFalse(installer.isEnabled("nonexistent"))
    }

    @Test
    fun uninstallSkillReturnsFailureForNonExistentSkill() {
        val result = installer.uninstallSkill("nonexistent")
        assertTrue(result.isFailure)
    }

    @Test
    fun disableSkillCreatesDisabledMarker() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        val result = installer.disableSkill("my-skill")
        assertTrue(result.isSuccess)
        assertTrue(skillDir.resolve(".disabled").exists())
    }

    @Test
    fun isEnabledReturnsTrueWhenSkillHasNoDisabledMarker() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        assertTrue(installer.isEnabled("my-skill"))
    }

    @Test
    fun isEnabledReturnsFalseWhenSkillHasDisabledMarker() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        skillDir.resolve(".disabled").createNewFile()
        assertFalse(installer.isEnabled("my-skill"))
    }

    @Test
    fun enableSkillRemovesDisabledMarker() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        skillDir.resolve(".disabled").createNewFile()
        val result = installer.enableSkill("my-skill")
        assertTrue(result.isSuccess)
        assertFalse(skillDir.resolve(".disabled").exists())
    }

    @Test
    fun enableSkillSucceedsWhenNoDisabledMarker() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        val result = installer.enableSkill("my-skill")
        assertTrue(result.isSuccess)
    }

    @Test
    fun setEnabledDelegatesCorrectly() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        installer.setEnabled("my-skill", false)
        assertFalse(installer.isEnabled("my-skill"))
        installer.setEnabled("my-skill", true)
        assertTrue(installer.isEnabled("my-skill"))
    }

    @Test
    fun uninstallSkillDeletesSkillDirectory() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        skillDir.resolve("test.txt").writeText("content")
        val result = installer.uninstallSkill("my-skill")
        assertTrue(result.isSuccess)
        assertFalse(skillDir.exists())
    }

    @Test
    fun installFromZipFailsWhenZipFileDoesNotExist() = runBlocking {
        val result = installer.installFromZip("/nonexistent/path.zip")
        assertTrue(result.isFailure)
    }

    @Test
    fun installFromZipFailsWhenZipHasNoSkillMd() = runBlocking {
        val tempZip = File.createTempFile("test", ".zip", tempFolder.root)
        try {
            ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
                zos.putNextEntry(ZipEntry("some-file.txt"))
                zos.write("hello".toByteArray())
                zos.closeEntry()
            }
            val result = installer.installFromZip(tempZip.absolutePath)
            assertTrue(result.isFailure)
        } finally {
            tempZip.delete()
        }
    }
}
