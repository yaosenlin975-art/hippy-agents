package com.lin.hippyagent.core.pool

import java.util.concurrent.atomic.AtomicLong

object FastId {
    // 用当前时间戳的低 32 位初始化，避免进程重启后计数器归零导致 ID 冲突
    private val counter = AtomicLong(System.currentTimeMillis())
    private const val NODE_ID: Long = 1

    fun next(): String {
        val id = counter.incrementAndGet()
        val timeLow = (id and 0xFFFFFFFFL)
        return "%08x%08x".format(NODE_ID, timeLow)
    }

    fun nextShort(): String {
        return next().take(8)
    }
}

