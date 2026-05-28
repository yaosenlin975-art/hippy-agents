package com.lin.hippyagent.core.skill.builtin

import com.lin.hippyagent.core.linux.LinuxManager
import timber.log.Timber

class HimalayaSkill(private val linuxManager: LinuxManager?) {

    suspend fun checkAvailability(): Result<Boolean> {
        if (linuxManager == null) return Result.success(false)
        return try {
            val (exitCode, output) = linuxManager.exec("which himalaya", timeout = 5_000)
            Result.success(exitCode == 0 && output.isNotBlank())
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    suspend fun listEmails(account: String = "", folder: String = "INBOX", limit: Int = 20): Result<String> {
        if (linuxManager == null) return Result.failure(IllegalStateException("Linux 环境未就绪"))
        return try {
            val accountArg = if (account.isNotEmpty()) "-a $account" else ""
            val (exitCode, output) = linuxManager.exec("himalaya $accountArg envelope list -f $folder -s $limit", timeout = 30_000)
            if (exitCode == 0) Result.success(output) else Result.failure(RuntimeException("himalaya list failed (exit $exitCode): $output"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to list emails")
            Result.failure(e)
        }
    }

    suspend fun readEmail(id: String, account: String = "", folder: String = "INBOX"): Result<String> {
        if (linuxManager == null) return Result.failure(IllegalStateException("Linux 环境未就绪"))
        return try {
            val accountArg = if (account.isNotEmpty()) "-a $account" else ""
            val (exitCode, output) = linuxManager.exec("himalaya $accountArg message read -f $folder $id", timeout = 30_000)
            if (exitCode == 0) Result.success(output) else Result.failure(RuntimeException("himalaya read failed (exit $exitCode): $output"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to read email: $id")
            Result.failure(e)
        }
    }

    suspend fun searchEmails(query: String, account: String = "", folder: String = "INBOX"): Result<String> {
        if (linuxManager == null) return Result.failure(IllegalStateException("Linux 环境未就绪"))
        return try {
            val accountArg = if (account.isNotEmpty()) "-a $account" else ""
            val (exitCode, output) = linuxManager.exec("himalaya $accountArg envelope list -f $folder -w \"$query\"", timeout = 30_000)
            if (exitCode == 0) Result.success(output) else Result.failure(RuntimeException("himalaya search failed (exit $exitCode): $output"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to search emails")
            Result.failure(e)
        }
    }

    suspend fun sendEmail(to: String, subject: String, body: String, account: String = ""): Result<String> {
        if (linuxManager == null) return Result.failure(IllegalStateException("Linux 环境未就绪"))
        return try {
            val accountArg = if (account.isNotEmpty()) "-a $account" else ""
            val tmpPath = "/tmp/himalaya_mail_${System.currentTimeMillis()}.eml"
            val emailContent = buildString {
                appendLine("To: $to")
                appendLine("Subject: $subject")
                appendLine("Content-Type: text/plain; charset=utf-8")
                appendLine()
                append(body)
            }
            val writeCmd = "cat > $tmpPath << 'HEREDOC_EOF'\n$emailContent\nHEREDOC_EOF"
            val (writeExit, writeErr) = linuxManager.exec(writeCmd, timeout = 5_000)
            if (writeExit != 0) return Result.failure(RuntimeException("Write temp file failed: $writeErr"))
            val (exitCode, output) = linuxManager.exec("himalaya $accountArg message send < $tmpPath", timeout = 30_000)
            linuxManager.exec("rm -f $tmpPath", timeout = 3_000)
            if (exitCode == 0) Result.success(output) else Result.failure(RuntimeException("himalaya send failed (exit $exitCode): $output"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to send email")
            Result.failure(e)
        }
    }
}
