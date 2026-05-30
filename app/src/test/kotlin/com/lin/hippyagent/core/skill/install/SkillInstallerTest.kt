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
    fun `enableSkill returns failure for non-existent skill`() {
        val result = installer.enableSkill("nonexistent")
        assertTrue(result.isFailure)
    }

    @Test
    fun `disableSkill returns failure for non-existent skill`() {
        val result = installer.disableSkill("nonexistent")
        assertTrue(result.isFailure)
    }

    @Test
    fun `isEnabled returns false for non-existent skill`() {
        assertFalse(installer.isEnabled("nonexistent"))
    }

    @Test
    fun `uninstallSkill returns failure for non-existent skill`() {
        val result = installer.uninstallSkill("nonexistent")
        assertTrue(result.isFailure)
    }

    @Test
    fun `disableSkill creates disabled marker`() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        val result = installer.disableSkill("my-skill")
        assertTrue(result.isSuccess)
        assertTrue(skillDir.resolve(".disabled").exists())
    }

    @Test
    fun `isEnabled returns true when skill has no disabled marker`() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        assertTrue(installer.isEnabled("my-skill"))
    }

    @Test
    fun `isEnabled returns false when skill has disabled marker`() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        skillDir.resolve(".disabled").createNewFile()
        assertFalse(installer.isEnabled("my-skill"))
    }

    @Test
    fun `enableSkill removes disabled marker`() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        skillDir.resolve(".disabled").createNewFile()
        val result = installer.enableSkill("my-skill")
        assertTrue(result.isSuccess)
        assertFalse(skillDir.resolve(".disabled").exists())
    }

    @Test
    fun `enableSkill succeeds when no disabled marker`() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        val result = installer.enableSkill("my-skill")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `setEnabled delegates correctly`() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        installer.setEnabled("my-skill", false)
        assertFalse(installer.isEnabled("my-skill"))
        installer.setEnabled("my-skill", true)
        assertTrue(installer.isEnabled("my-skill"))
    }

    @Test
    fun `uninstallSkill deletes skill directory`() {
        val skillDir = skillsDir.resolve("my-skill")
        skillDir.mkdirs()
        skillDir.resolve("test.txt").writeText("content")
        val result = installer.uninstallSkill("my-skill")
        assertTrue(result.isSuccess)
        assertFalse(skillDir.exists())
    }

    @Test
    fun `installFromZip fails when zip file does not exist`() = runBlocking {
        val result = installer.installFromZip("/nonexistent/path.zip")
        assertTrue(result.isFailure)
    }

    @Test
    fun `installFromZip fails when zip has no SKILL.md`() = runBlocking {
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
