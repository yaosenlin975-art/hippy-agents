package com.lin.hippyagent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskTranslatorTest {

    // ═══════════ translate ═══════════

    @Test
    fun translate_rmRf_describesPermanentDelete() {
        val result = RiskTranslator.translate("rm -rf ~/Downloads")
        assertTrue(result, result.contains("永久删除"))
        assertTrue(result, result.contains("~/Downloads"))
        assertTrue(result, result.contains("不可恢复"))
    }

    @Test
    fun translate_plainRm_describesDeleteFile() {
        val result = RiskTranslator.translate("rm notes.txt")
        assertTrue(result, result.contains("删除文件或目录"))
        assertTrue(result, result.contains("notes.txt"))
    }

    @Test
    fun translate_gitPush_describesPushToRemote() {
        val result = RiskTranslator.translate("git push origin main")
        assertTrue(result, result.contains("推送到远程仓库"))
    }

    @Test
    fun translate_gitCommit_describesCommit() {
        val result = RiskTranslator.translate("git commit -m fix")
        assertTrue(result, result.contains("本地代码提交"))
    }

    @Test
    fun translate_npmPublish_describesPublish() {
        val result = RiskTranslator.translate("npm publish")
        assertTrue(result, result.contains("发布到公共 npm 仓库"))
    }

    @Test
    fun translate_chmod_describesPermissionChange() {
        val result = RiskTranslator.translate("chmod 777 script.sh")
        assertTrue(result, result.contains("权限"))
        assertTrue(result, result.contains("777"))
        assertTrue(result, result.contains("script.sh"))
    }

    @Test
    fun translate_cpR_describesRecursiveCopy() {
        val result = RiskTranslator.translate("cp -r /a /b")
        assertTrue(result, result.contains("递归复制"))
    }

    @Test
    fun translate_cat_describesReadFile() {
        val result = RiskTranslator.translate("cat /etc/hosts")
        assertTrue(result, result.contains("读取文件"))
        assertTrue(result, result.contains("/etc/hosts"))
    }

    @Test
    fun translate_curl_describesNetworkRequest() {
        val result = RiskTranslator.translate("curl -s https://example.com")
        assertTrue(result, result.contains("网络请求"))
    }

    @Test
    fun translate_kill9_describesForceKill() {
        val result = RiskTranslator.translate("kill -9 1234")
        assertTrue(result, result.contains("强制终止"))
    }

    @Test
    fun translate_sudo_describesSuperuserExec() {
        val result = RiskTranslator.translate("sudo apt update")
        assertTrue(result, result.contains("超级用户权限"))
    }

    @Test
    fun translate_dd_describesRawDeviceWrite() {
        val result = RiskTranslator.translate("dd if=/dev/zero of=/dev/sda bs=1M")
        assertTrue(result, result.contains("块级"))
        assertTrue(result, result.contains("数据损坏"))
    }

    @Test
    fun translate_mkfs_describesFormat() {
        val result = RiskTranslator.translate("mkfs.ext4 /dev/sdb1")
        assertTrue(result, result.contains("格式化"))
    }

    @Test
    fun translate_unknownCommand_fallsBack() {
        val result = RiskTranslator.translate("some-unknown-tool --flag")
        assertTrue(result, result.contains("将执行命令"))
        assertTrue(result, result.contains("some-unknown-tool"))
    }

    @Test
    fun translate_echoRedirect_describesWriteToFile() {
        val result = RiskTranslator.translate("echo hello > /tmp/a.txt")
        assertTrue(result, result.contains("写入文件"))
        assertTrue(result, result.contains("/tmp/a.txt"))
    }

    // ═══════════ estimateRisk ═══════════

    @Test
    fun estimateRisk_sudo_high() {
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateRisk("sudo rm -rf /var/log"))
    }

    @Test
    fun estimateRisk_rmRfRoot_high() {
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateRisk("rm -rf /data"))
    }

    @Test
    fun estimateRisk_dd_high() {
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateRisk("dd if=/dev/zero of=/dev/sda"))
    }

    @Test
    fun estimateRisk_mkfs_high() {
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateRisk("mkfs.ext4 /dev/sdb1"))
    }

    @Test
    fun estimateRisk_shutdown_high() {
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateRisk("shutdown -h now"))
    }

    @Test
    fun estimateRisk_pmClear_high() {
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateRisk("pm clear com.example.app"))
    }

    @Test
    fun estimateRisk_gitPushForce_high() {
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateRisk("git push --force origin main"))
    }

    @Test
    fun estimateRisk_readOnly_low() {
        assertEquals(RiskLevel.LOW, RiskTranslator.estimateRisk("cat /etc/hosts"))
        assertEquals(RiskLevel.LOW, RiskTranslator.estimateRisk("ls -la /sdcard"))
        assertEquals(RiskLevel.LOW, RiskTranslator.estimateRisk("git status"))
        assertEquals(RiskLevel.LOW, RiskTranslator.estimateRisk("pwd"))
    }

    @Test
    fun estimateRisk_medium_default() {
        assertEquals(RiskLevel.MEDIUM, RiskTranslator.estimateRisk("git push origin main"))
        assertEquals(RiskLevel.MEDIUM, RiskTranslator.estimateRisk("npm publish"))
        assertEquals(RiskLevel.MEDIUM, RiskTranslator.estimateRisk("mv a b"))
        assertEquals(RiskLevel.MEDIUM, RiskTranslator.estimateRisk("unknown command here"))
    }

    // ═══════════ estimateToolRisk ═══════════

    @Test
    fun estimateToolRisk_highTools() {
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateToolRisk("delete_file"))
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateToolRisk("execute_shell"))
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateToolRisk("execute_bash"))
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateToolRisk("pm_uninstall"))
    }

    @Test
    fun estimateToolRisk_lowTools() {
        assertEquals(RiskLevel.LOW, RiskTranslator.estimateToolRisk("read_file"))
        assertEquals(RiskLevel.LOW, RiskTranslator.estimateToolRisk("list_directory"))
        assertEquals(RiskLevel.LOW, RiskTranslator.estimateToolRisk("screen_observe"))
    }

    @Test
    fun estimateToolRisk_unknown_medium() {
        assertEquals(RiskLevel.MEDIUM, RiskTranslator.estimateToolRisk("some_unknown_tool"))
        assertEquals(RiskLevel.MEDIUM, RiskTranslator.estimateToolRisk("write_file"))
    }

    @Test
    fun estimateToolRisk_caseInsensitive() {
        assertEquals(RiskLevel.HIGH, RiskTranslator.estimateToolRisk("Execute_Shell"))
        assertEquals(RiskLevel.LOW, RiskTranslator.estimateToolRisk("READ_FILE"))
    }
}
