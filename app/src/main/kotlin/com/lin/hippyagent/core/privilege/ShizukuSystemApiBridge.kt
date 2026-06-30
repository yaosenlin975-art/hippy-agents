package com.lin.hippyagent.core.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

class ShizukuSystemApiBridge(
    private val context: Context
) : SystemApiBridge {

    @Volatile
    private var shizukuAlive = false

    init {
        Shizuku.addBinderReceivedListenerSticky { shizukuAlive = true }
        Shizuku.addBinderDeadListener { shizukuAlive = false }
    }

    override suspend fun availablePrivilege(): SystemApiBridge.PrivilegeLevel {
        if (shizukuAlive) {
            val granted = runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            if (granted) return SystemApiBridge.PrivilegeLevel.SHIZUKU
        }
        if (RootProbe.hasRoot()) {
            return SystemApiBridge.PrivilegeLevel.ROOT
        }
        return SystemApiBridge.PrivilegeLevel.NONE
    }

    override suspend fun execute(cmd: String, timeoutMs: Long): Result<String> = withContext(Dispatchers.IO) {
        when (availablePrivilege()) {
            SystemApiBridge.PrivilegeLevel.SHIZUKU -> executeViaShizuku(cmd, timeoutMs)
            SystemApiBridge.PrivilegeLevel.ROOT -> executeViaRoot(cmd, timeoutMs)
            SystemApiBridge.PrivilegeLevel.NONE -> Result.failure(IllegalStateException("No privilege available (Shizuku/Root 均不可用)"))
        }
    }

    private suspend fun executeViaShizuku(cmd: String, timeoutMs: Long): Result<String> {
        return withTimeoutOrNull(timeoutMs) {
            runCatching {
                val binder = Shizuku.getBinder()
                    ?: throw IllegalStateException("Shizuku binder is null")
                val service = IShizukuService.Stub.asInterface(binder)
                val remoteProcess: IRemoteProcess = service.newProcess(
                    arrayOf("sh", "-c", cmd), null, null
                )
                val stdout = readRemoteStream(remoteProcess.inputStream)
                val stderr = readRemoteStream(remoteProcess.errorStream)
                val exitCode = remoteProcess.waitFor()
                if (exitCode == 0) stdout
                else throw IllegalStateException("exit=$exitCode stderr=$stderr")
            }
        } ?: Result.failure(IllegalStateException("Shizuku execute timeout after ${timeoutMs}ms"))
    }

    private fun readRemoteStream(pfd: ParcelFileDescriptor): String {
        return pfd.use { parcelFd ->
            FileInputStream(parcelFd.fileDescriptor).use { fis ->
                BufferedReader(InputStreamReader(fis)).use { it.readText() }
            }
        }
    }

    private suspend fun executeViaRoot(cmd: String, timeoutMs: Long): Result<String> {
        return withTimeoutOrNull(timeoutMs) {
            runCatching {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
                val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
                val exitCode = process.waitFor()
                if (exitCode == 0) stdout
                else throw IllegalStateException("exit=$exitCode stderr=$stderr")
            }
        } ?: Result.failure(IllegalStateException("Root execute timeout after ${timeoutMs}ms"))
    }
}
