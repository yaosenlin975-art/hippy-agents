package com.lin.hippyagent.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ModelClientToolsCacheTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun weatherTool(name: String = "get_weather") = ModelToolDefinition(
        name = name,
        description = "Get \"today's\" weather \\ path",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("city" to mapOf("type" to "string")),
            "required" to listOf("city")
        )
    )

    private fun assertValidToolJson(raw: String, expectedName: String?) {
        val arr = requireNotNull(json.parseToJsonElement(raw).safeJsonArray()) {
            "must be a valid JSON array: $raw"
        }
        assertEquals(1, arr.size)
        val function = requireNotNull(
            arr[0].safeJsonObject()?.get("function")?.safeJsonObject()
        ) { "must have function object: $raw" }
        expectedName?.let { assertEquals(it, function["name"]?.safeJsonPrimitiveContent()) }
        val parameters = function["parameters"]?.safeJsonObject()
        assertEquals("object", parameters?.get("type")?.safeJsonPrimitiveContent())
        assertEquals(
            "city",
            parameters?.get("required")?.safeJsonArray()?.get(0)?.safeJsonPrimitiveContent()
        )
    }

    @Test
    fun `concurrent same-key access is safe and returns the identical cached instance`() {
        val threads = 8
        val iterations = 2000
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        val tasks = (0 until threads).map { _ ->
            Callable {
                startGate.await()
                try {
                    repeat(iterations) {
                        results.add(weatherTool().toToolsJsonArray())
                    }
                } catch (t: Throwable) {
                    failures.add(t)
                }
            }
        }
        tasks.forEach { executor.submit(it) }
        startGate.countDown()
        executor.shutdown()
        assertTrue("tasks must finish within 60s", executor.awaitTermination(60, TimeUnit.SECONDS))

        assertTrue("no exception expected, got: ${failures.joinToString { it.toString() }}", failures.isEmpty())
        assertEquals(threads * iterations, results.size)

        val first = results.poll()
        val allSameInstance = results.all { it === first }
        assertTrue("all threads must observe the identical cached String instance", allSameInstance)

        assertValidToolJson(first, "get_weather")
        assertEquals(
            "Get \"today's\" weather \\ path",
            json.parseToJsonElement(first).safeJsonArray()
                ?.get(0)?.safeJsonObject()?.get("function")?.safeJsonObject()
                ?.get("description")?.safeJsonPrimitiveContent()
        )
    }

    @Test
    fun `concurrent distinct-key insertion is safe and eviction stays bounded`() {
        val threads = 8
        val perThread = 100
        val executor = Executors.newFixedThreadPool(threads)
        val startGate = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<String>()
        val failures = ConcurrentLinkedQueue<Throwable>()

        val tasks = (0 until threads).map { t ->
            Callable {
                startGate.await()
                try {
                    repeat(perThread) { i ->
                        val seed = t * perThread + i
                        results.add(weatherTool(name = "tool_$seed").toToolsJsonArray())
                    }
                } catch (th: Throwable) {
                    failures.add(th)
                }
            }
        }
        tasks.forEach { executor.submit(it) }
        startGate.countDown()
        executor.shutdown()
        assertTrue("tasks must finish within 60s", executor.awaitTermination(60, TimeUnit.SECONDS))

        assertTrue("no exception expected, got: ${failures.joinToString { it.toString() }}", failures.isEmpty())
        assertEquals(threads * perThread, results.size)
        results.forEach { assertValidToolJson(it, null) }

        assertTrue("cache must stay bounded, was ${toolsJsonCache.size}", toolsJsonCache.size <= 64)
    }
}
