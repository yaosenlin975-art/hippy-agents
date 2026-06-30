package com.lin.hippyagent.core.privilege

/**
 * 系统级 API 统一桥接层。
 *
 * 三级降级：Shizuku 优先（非 root 设备可用）→ Root 回退（极客用户）→ 失败返回。
 */
interface SystemApiBridge {

    suspend fun execute(cmd: String, timeoutMs: Long = 15_000L): Result<String>

    suspend fun availablePrivilege(): PrivilegeLevel

    enum class PrivilegeLevel { SHIZUKU, ROOT, NONE }
}
