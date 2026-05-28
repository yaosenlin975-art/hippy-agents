package com.lin.hippyagent.core.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class HippyJobWorker(
    private val queue: HippyJobQueue,
    private val dao: HippyJobDao,
    private val scope: CoroutineScope
) {
    private val handlers = mutableMapOf<String, HippyJobHandler>()
    private val inFlight = mutableMapOf<Long, Job>()
    private var running = false

    var concurrency: Int = 2

    fun register(name: String, handler: HippyJobHandler) {
        handlers[name] = handler
    }

    fun start() {
        if (running) return
        running = true
        scope.launch {
            while (coroutineContext.isActive && running) {
                try {
                    if (inFlight.size < concurrency) {
                        val job = queue.claim("default", handlers.keys.toList())
                        if (job != null) {
                            launchJob(job)
                        } else {
                            delay(2000)
                        }
                    } else {
                        delay(1000)
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Timber.e(e, "HippyJobWorker poll error")
                    delay(5000)
                }
            }
        }
    }

    private fun launchJob(entity: HippyJobEntity) {
        val token = entity.lockToken ?: "missing-token-${entity.id}"
        val jobScope = scope.launch(Dispatchers.Default) {
            try {
                val handler = handlers[entity.name]
                    ?: throw IllegalArgumentException("No handler: ${entity.name}")

                val jobContext = HippyJobContext(
                    id = entity.id,
                    name = entity.name,
                    data = parseDataJson(entity.dataJson),
                    attemptsMade = entity.attemptsMade,
                    updateProgress = { progress ->
                        scope.launch {
                            dao.updateProgress(entity.id, HippyJobJson.mapToJson(progress))
                        }
                    },
                    updateTokens = { input, output ->
                        scope.launch { dao.updateTokens(entity.id, input, output) }
                    }
                )

                val result = handler.execute(jobContext)
                queue.complete(entity.id, token, result)
            } catch (e: CancellationException) {
                queue.fail(entity.id, token, "cancelled: ${e.message}")
            } catch (e: Exception) {
                Timber.e(e, "HippyJob ${entity.id} (${entity.name}) failed")
                queue.fail(entity.id, token, e.message ?: "unknown error")
            } finally {
                inFlight.remove(entity.id)
            }
        }
        inFlight[entity.id] = jobScope
    }

    fun stop() {
        running = false
    }

    private fun parseDataJson(dataJson: String): Map<String, Any> {
        return HippyJobJson.jsonToMap(dataJson)
    }
}
