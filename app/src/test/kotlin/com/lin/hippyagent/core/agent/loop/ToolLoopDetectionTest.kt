package com.lin.hippyagent.core.agent.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolLoopDetectionTest {

    // ═══════════ generic repeat ═══════════

    @Test
    fun genericRepeat_belowThreshold_noWarning() {
        val detector = ToolLoopDetection()
        repeat(9) {
            val r = detector.checkAndRecord("read_file", """{"path":"/a"}""", "ok")
            assertEquals(ToolLoopDetection.LoopLevel.NONE, r.level)
        }
    }

    @Test
    fun genericRepeat_atThreshold_emitsWarn() {
        val detector = ToolLoopDetection()
        var result: ToolLoopDetection.DetectionResult? = null
        repeat(10) {
            result = detector.checkAndRecord("read_file", """{"path":"/a"}""", "ok")
        }
        assertEquals(ToolLoopDetection.LoopLevel.WARN, result?.level)
        assertEquals(ToolLoopDetection.LoopPattern.GENERIC_REPEAT, result?.pattern)
    }

    @Test
    fun genericRepeat_sameSignature_deduplicated() {
        val detector = ToolLoopDetection()
        repeat(10) { detector.checkAndRecord("read_file", """{"path":"/a"}""", "ok") }
        // 第 11 次调用同签名 → 不再重复上报
        val result = detector.checkAndRecord("read_file", """{"path":"/a"}""", "ok")
        assertEquals(ToolLoopDetection.LoopLevel.NONE, result.level)
    }

    @Test
    fun genericRepeat_paramChange_resetsCount() {
        val detector = ToolLoopDetection()
        repeat(10) { detector.checkAndRecord("read_file", """{"path":"/a"}""", "ok") }
        // 换参数 → 不再触发
        val result = detector.checkAndRecord("read_file", """{"path":"/b"}""", "ok")
        assertEquals(ToolLoopDetection.LoopLevel.NONE, result.level)
    }

    // ═══════════ circuit breaker ═══════════

    @Test
    fun circuitBreaker_sameToolParamsResult30Times_critical() {
        val detector = ToolLoopDetection()
        var result: ToolLoopDetection.DetectionResult? = null
        repeat(30) {
            result = detector.checkAndRecord("execute_shell", """{"cmd":"ls"}""", "output")
        }
        assertEquals(ToolLoopDetection.LoopLevel.CRITICAL, result?.level)
        assertEquals(ToolLoopDetection.LoopPattern.GLOBAL_CIRCUIT_BREAKER, result?.pattern)
    }

    @Test
    fun circuitBreaker_resultChange_resetsCount() {
        val detector = ToolLoopDetection()
        var critical: ToolLoopDetection.DetectionResult? = null
        // 29 次相同
        repeat(29) { detector.checkAndRecord("execute_shell", """{"cmd":"ls"}""", "same") }
        // 结果变化 → 计数器打散
        detector.checkAndRecord("execute_shell", """{"cmd":"ls"}""", "different")
        repeat(29) {
            critical = detector.checkAndRecord("execute_shell", """{"cmd":"ls"}""", "same")
        }
        assertNotEquals(ToolLoopDetection.LoopPattern.GLOBAL_CIRCUIT_BREAKER, critical?.pattern)
    }

    // ═══════════ known poll tools ═══════════

    @Test
    fun pollTool_noProgress_emitsWarnThenCritical() {
        val detector = ToolLoopDetection()
        detector.registerPollTool("check_status")

        var warn: ToolLoopDetection.DetectionResult? = null
        repeat(10) {
            warn = detector.checkAndRecord("check_status", """{"job":"j1"}""", "running")
        }
        assertEquals(ToolLoopDetection.LoopLevel.WARN, warn?.level)
        assertEquals(ToolLoopDetection.LoopPattern.KNOWN_POLL_NO_PROGRESS, warn?.pattern)

        var critical: ToolLoopDetection.DetectionResult? = null
        repeat(10) {
            critical = detector.checkAndRecord("check_status", """{"job":"j1"}""", "running")
        }
        assertEquals(ToolLoopDetection.LoopLevel.CRITICAL, critical?.level)
    }

    @Test
    fun pollTool_withProgress_noWarning() {
        val detector = ToolLoopDetection()
        detector.registerPollTool("check_status")
        var result: ToolLoopDetection.DetectionResult? = null
        repeat(10) { i ->
            result = detector.checkAndRecord("check_status", """{"job":"j1"}""", "progress $i")
        }
        assertEquals(ToolLoopDetection.LoopLevel.NONE, result?.level)
    }

    @Test
    fun pollTool_progressThenNoProgress_restartsWindow() {
        val detector = ToolLoopDetection()
        detector.registerPollTool("check_status")
        repeat(9) {
            detector.checkAndRecord("check_status", """{"job":"j1"}""", "progress $it")
        }
        var result: ToolLoopDetection.DetectionResult? = null
        repeat(10) { i ->
            val r = detector.checkAndRecord("check_status", """{"job":"j1"}""", "stuck")
            if (i == 9) result = r
        }
        assertEquals(ToolLoopDetection.LoopLevel.WARN, result?.level)
    }

    @Test
    fun defaultPollTools_includeCommonPollers() {
        assertTrue("check_status" in ToolLoopDetection.DEFAULT_POLL_TOOLS)
        assertTrue("wait" in ToolLoopDetection.DEFAULT_POLL_TOOLS)
    }

    // ═══════════ ping pong ═══════════

    @Test
    fun pingPong_alternating_emitsWarnAtThreshold() {
        val detector = ToolLoopDetection()
        var result: ToolLoopDetection.DetectionResult? = null
        repeat(10) { i ->
            val tool = if (i % 2 == 0) "tool_a" else "tool_b"
            result = detector.checkAndRecord(tool, """{"x":1}""", "done")
        }
        assertEquals(ToolLoopDetection.LoopLevel.WARN, result?.level)
        assertEquals(ToolLoopDetection.LoopPattern.PING_PONG, result?.pattern)
    }

    @Test
    fun pingPong_alternating_emitsCriticalAtHardThreshold() {
        val detector = ToolLoopDetection()
        var result: ToolLoopDetection.DetectionResult? = null
        repeat(20) { i ->
            val tool = if (i % 2 == 0) "tool_a" else "tool_b"
            result = detector.checkAndRecord(tool, """{"x":1}""", "done")
        }
        assertEquals(ToolLoopDetection.LoopLevel.CRITICAL, result?.level)
        assertEquals(ToolLoopDetection.LoopPattern.PING_PONG, result?.pattern)
    }

    @Test
    fun pingPong_threeTools_noDetection() {
        val detector = ToolLoopDetection()
        var result: ToolLoopDetection.DetectionResult? = null
        repeat(12) { i ->
            val tool = when (i % 3) {
                0 -> "tool_a"
                1 -> "tool_b"
                else -> "tool_c"
            }
            result = detector.checkAndRecord(tool, """{"x":1}""", "done")
        }
        assertNotEquals(ToolLoopDetection.LoopPattern.PING_PONG, result?.pattern)
    }

    // ═══════════ window size ═══════════

    @Test
    fun windowSize_evictsOldRecords() {
        val detector = ToolLoopDetection(windowSize = 5, genericRepeatThreshold = 3)
        var result: ToolLoopDetection.DetectionResult? = null
        // 6 条记录: 前 3 条相同, 后 3 条相同但不同参数 → 窗口只保留后 3 条, 不触发
        repeat(3) { detector.checkAndRecord("read_file", """{"path":"/a"}""", "ok") }
        repeat(3) { i ->
            result = detector.checkAndRecord("read_file", """{"path":"/b$i"}""", "ok")
        }
        assertEquals(ToolLoopDetection.LoopLevel.NONE, result?.level)
    }

    @Test
    fun signature_containsToolNameAndParams() {
        val detector = ToolLoopDetection()
        var result: ToolLoopDetection.DetectionResult? = null
        repeat(10) {
            result = detector.checkAndRecord("read_file", """{"path":"/a"}""", "ok")
        }
        assertTrue(result?.signature?.startsWith("REP:read_file:") == true)
    }
}
