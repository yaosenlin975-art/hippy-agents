package com.lin.hippyagent.core.agent.collaboration

import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelCallResponse
import com.lin.hippyagent.core.model.ModelClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * LLMSpeakerSelector 结构化并发测试：CancellationException 必须向上传播，
 * 不能被 catch (e: Exception) 吞掉（WS-28）。
 */
class LLMSpeakerSelectorTest {

    private fun selectorWith(client: ModelClient): LLMSpeakerSelector = LLMSpeakerSelector(
        modelClients = mapOf("selector-model" to client),
        descriptionProvider = AgentDescriptionProvider(customDescriptions = mapOf("a" to "A")),
        defaultModelId = "selector-model",
        timeoutMs = 5000
    )

    private fun selectorConfig(useLLMToTerminate: Boolean = false): GroupChatConfig = GroupChatConfig(
        groupId = "g1",
        groupName = "测试群",
        agentIds = listOf("a", "b"),
        llmSelectorModel = "selector-model",
        useLLMToTerminate = useLLMToTerminate
    )

    private val chatState = GroupChatState(groupId = "g1", messages = listOf(GroupChatMessage("a", "你好", round = 1)))

    private class FakeModelClient(
        private val onChat: suspend () -> ModelCallResponse = { error("not expected") }
    ) : ModelClient {
        override suspend fun chatCompletion(request: ModelCallRequest): ModelCallResponse = onChat()
        override suspend fun chatCompletionStream(request: ModelCallRequest): Flow<Nothing> = emptyFlow()
        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)
        override suspend fun listModels(): List<String> = emptyList()
    }

    @Test
    fun selectNextSpeaker_genericException_returnsError() {
        val selector = selectorWith(FakeModelClient(onChat = { throw IllegalStateException("boom") }))
        val result = runBlocking { selector.selectNextSpeaker(selectorConfig(), chatState) }
        assertTrue("应回退为 Error 而非抛异常", result is SelectorResult.Error)
    }

    @Test
    fun selectNextSpeaker_clientThrowsCancellationException_propagates() {
        val selector = selectorWith(FakeModelClient(onChat = { throw CancellationException("client cancelled") }))
        try {
            runBlocking { selector.selectNextSpeaker(selectorConfig(), chatState) }
            fail("CancellationException 应向上传播，而非被吞掉")
        } catch (e: CancellationException) {
            assertEquals("client cancelled", e.message)
        }
    }

    @Test
    fun selectNextSpeaker_parentCancelledMidCall_cancellationPropagates() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val selector = selectorWith(
            FakeModelClient(
                onChat = {
                    started.complete(Unit)
                    suspendCancellableCoroutine<ModelCallResponse> { }
                }
            )
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        var outcome: Any? = null
        val job = scope.launch {
            try {
                outcome = selector.selectNextSpeaker(selectorConfig(), chatState)
            } catch (e: CancellationException) {
                outcome = e
            }
        }
        started.await()
        job.cancel()
        assertTrue("父协程取消后应抛出 CancellationException，实际得到: $outcome", outcome is CancellationException)
        assertTrue(job.isCancelled)
    }

    @Test
    fun shouldTerminate_genericException_returnsFalse() {
        val selector = selectorWith(FakeModelClient(onChat = { throw IllegalStateException("boom") }))
        val result = runBlocking { selector.shouldTerminate(selectorConfig(useLLMToTerminate = true), chatState) }
        assertFalse(result)
    }

    @Test
    fun shouldTerminate_cancellationPropagates() {
        val selector = selectorWith(FakeModelClient(onChat = { throw CancellationException("client cancelled") }))
        try {
            runBlocking { selector.shouldTerminate(selectorConfig(useLLMToTerminate = true), chatState) }
            fail("CancellationException 应向上传播，而非被吞掉")
        } catch (e: CancellationException) {
            assertEquals("client cancelled", e.message)
        }
    }
}
