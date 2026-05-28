package com.lin.hippyagent.core.accessibility

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.lin.hippyagent.core.model.ModelClient
import com.lin.hippyagent.core.model.ModelCallRequest
import com.lin.hippyagent.core.model.ModelMessage
import timber.log.Timber

@Serializable
data class AutomateRequest(
    val task: String,
    val app: String? = null,
    val maxSteps: Int = 15,
    val confirmDangerous: Boolean = true
)

@Serializable
data class StepRecord(
    val step: Int,
    val observation: String,
    val action: String,
    val target: String? = null,
    val value: String? = null,
    val result: String
)

@Serializable
data class AutomateResult(
    val success: Boolean,
    val task: String,
    val steps: Int = 0,
    val stepHistory: List<StepRecord> = emptyList(),
    val error: String? = null
)

enum class ActionType {
    OBSERVE, TAP, TYPE, SCROLL, PRESS_BACK, PRESS_HOME, TASK_COMPLETE, TASK_FAILED
}

data class NextAction(
    val type: ActionType,
    val target: String? = null,
    val parameters: Map<String, String> = emptyMap()
)

class PhoneAutomator(
    private val controller: AccessibilityController,
    modelClient: ModelClient,
    modelName: String,
    private val vlmProvider: VlmProvider? = null,
    private val screenshotCapturer: ScreenshotCapturer? = null
) {

    companion object {
        private const val MAX_CONSECUTIVE_FAILURES = 3
        private const val VERIFY_DELAY_MS = 800L
        private const val RETRY_DELAY_MS = 500L
        private const val LAUNCH_SETTLE_MS = 1500L
    }

    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val refManager = RefManager()
    private val incrementalSensor = IncrementalSensor()
    private val adUiGuard = AdUiGuard()
    private val dualTrackEngine = DualTrackDecisionEngine(vlmProvider, screenshotCapturer)
    var modelClient: ModelClient = modelClient
        private set
    var modelName: String = modelName

    fun configure(modelClient: ModelClient, modelName: String) {
        this.modelClient = modelClient
        this.modelName = modelName
    }

    suspend fun execute(request: AutomateRequest): AutomateResult {
        if (!controller.isServiceRunning()) {
            return AutomateResult(false, request.task, error = "AccessibilityService not running")
        }

        val stepHistory = mutableListOf<StepRecord>()
        var consecutiveFailures = 0
        var lastObservation = ""

        if (request.app != null) {
            val launchResult = controller.interact(
                InteractRequest(action = "launch_app", value = request.app)
            )
            if (!launchResult.success) {
                return AutomateResult(false, request.task, error = "Failed to launch app: ${request.app}")
            }
            delay(LAUNCH_SETTLE_MS)
        }

        for (step in 1..request.maxSteps) {
            val observeResult = controller.observe(
                ObserveRequest(mode = "hybrid", depth = 5, filter = "interactive")
            )

            if (observeResult.error != null) {
                stepHistory.add(StepRecord(step, "Observe failed: ${observeResult.error}", "none", result = "failed"))
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return AutomateResult(false, request.task, step, stepHistory, "Consecutive observe failures")
                }
                continue
            }

            val observation = buildObservationSummary(observeResult)

            if (incrementalSensor.shouldSkipReasoning(observation)) {
                Timber.d("Step %d: UI unchanged, skipping reasoning", step)
                stepHistory.add(StepRecord(step, observation, "observe_skip", result = "skipped_unchanged"))
                continue
            }

            val nodes = observeResult.nodeTree?.let { flattenSerializedNodes(listOf(it)) } ?: emptyList()
            val packageName = observeResult.window ?: ""
            val refNodes = refManager.updateRefs(nodes)

            val nextAction = decideNextAction(
                task = request.task,
                observation = observation,
                refNodes = refNodes,
                stepHistory = stepHistory,
                step = step
            )

            if (nextAction.type == ActionType.TASK_COMPLETE) {
                stepHistory.add(StepRecord(step, observation, "task_complete", result = "completed"))
                return AutomateResult(true, request.task, step, stepHistory)
            }

            if (nextAction.type == ActionType.TASK_FAILED) {
                val reason = nextAction.parameters["reason"] ?: "LLM declared task failed"
                stepHistory.add(StepRecord(step, observation, "task_failed", result = "failed: $reason"))
                return AutomateResult(false, request.task, step, stepHistory, reason)
            }

            if (nextAction.type == ActionType.OBSERVE) {
                stepHistory.add(StepRecord(step, observation, "observe", result = "observed"))
                consecutiveFailures = 0
                continue
            }

            android.widget.Toast.makeText(
                controller.getContext(),
                "正在执行：${nextAction.type.name}" + (nextAction.target?.let { " → $it" } ?: ""),
                android.widget.Toast.LENGTH_SHORT
            ).show()

            val interactResult = performAction(nextAction, nodes, packageName)

            val record = StepRecord(
                step = step,
                observation = observation,
                action = nextAction.type.name,
                target = nextAction.target,
                value = nextAction.parameters["value"],
                result = if (interactResult.success) "success" else "failed: ${interactResult.error}"
            )
            stepHistory.add(record)

            if (interactResult.success) {
                consecutiveFailures = 0
                refManager.invalidateSnapshotAfterMutation()
            } else {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    return AutomateResult(false, request.task, step, stepHistory, "Consecutive action failures ($MAX_CONSECUTIVE_FAILURES)")
                }
            }

            delay(VERIFY_DELAY_MS)

            val verifyResult = controller.observe(
                ObserveRequest(mode = "hybrid", depth = 5, filter = "interactive")
            )
            if (verifyResult.error == null) {
                val verifyObservation = buildObservationSummary(verifyResult)
                val uiChanged = incrementalSensor.checkUiChange(verifyObservation, lastObservation)

                if (!uiChanged && interactResult.success) {
                    Timber.d("Step %d: UI unchanged after action, retrying once", step)
                    delay(RETRY_DELAY_MS)
                }

                val verifyNodes = verifyResult.nodeTree?.let { flattenSerializedNodes(listOf(it)) } ?: emptyList()
                val dismissTargets = adUiGuard.tryAutoDismissObstruction(verifyNodes, dualTrackEngine)
                for (dismiss in dismissTargets) {
                    Timber.d("Auto-dismissing ad/popup at (%.0f, %.0f)", dismiss.x, dismiss.y)
                    controller.interact(
                        InteractRequest(
                            action = "click",
                            target = "[${dismiss.x.toInt()},${dismiss.y.toInt()}][${(dismiss.x + 1).toInt()},${(dismiss.y + 1).toInt()}]"
                        )
                    )
                    delay(300)
                }
            }

            lastObservation = observation
        }

        return AutomateResult(false, request.task, request.maxSteps, stepHistory, "Exceeded max steps (${request.maxSteps})")
    }

    private suspend fun performAction(
        action: NextAction,
        nodes: List<SerializedNode>,
        packageName: String
    ): InteractResult {
        return when (action.type) {
            ActionType.TAP -> {
                val target = action.target ?: return InteractResult(false, error = "target required for TAP")
                val tapTarget = resolveTapTarget(target, nodes, packageName)
                if (tapTarget != null) {
                    controller.interact(
                        InteractRequest(
                            action = "click",
                            target = "[${tapTarget.x.toInt()},${tapTarget.y.toInt()}][${(tapTarget.x + 1).toInt()},${(tapTarget.y + 1).toInt()}]"
                        )
                    )
                } else {
                    controller.interact(InteractRequest(action = "click", target = target))
                }
            }
            ActionType.TYPE -> {
                val target = action.target ?: return InteractResult(false, error = "target required for TYPE")
                val value = action.parameters["value"] ?: return InteractResult(false, error = "value required for TYPE")
                controller.interact(InteractRequest(action = "input_text", target = target, value = value))
            }
            ActionType.SCROLL -> {
                val direction = action.parameters["direction"] ?: "down"
                controller.interact(InteractRequest(action = "scroll", value = direction))
            }
            ActionType.PRESS_BACK -> {
                controller.interact(InteractRequest(action = "press_back"))
            }
            ActionType.PRESS_HOME -> {
                controller.interact(InteractRequest(action = "press_home"))
            }
            else -> InteractResult(false, error = "Unsupported action type: ${action.type}")
        }
    }

    private suspend fun resolveTapTarget(
        target: String,
        nodes: List<SerializedNode>,
        packageName: String
    ): DualTrackResult? {
        val refTarget = refManager.resolveRefForTap(target, nodes)
        if (refTarget != null) {
            return DualTrackResult(refTarget.x, refTarget.y, 0.9f, "ref", refTarget.nodeRef)
        }

        return try {
            dualTrackEngine.decide(
                targetDescription = target,
                nodes = nodes,
                packageName = packageName,
                screenSignature = packageName
            )
        } catch (e: Exception) {
            Timber.w(e, "DualTrack decide failed for target: %s", target)
            null
        }
    }

    private suspend fun decideNextAction(
        task: String,
        observation: String,
        refNodes: List<RefNode>,
        stepHistory: List<StepRecord>,
        step: Int
    ): NextAction {
        val refList = refNodes.joinToString("\n") { ref ->
            val text = ref.text ?: ""
            val bounds = "[${ref.bounds.left},${ref.bounds.top}][${ref.bounds.right},${ref.bounds.bottom}]"
            "${ref.ref}: ${ref.className ?: "View"} text=\"$text\" clickable=${ref.clickable} scrollable=${ref.scrollable} bounds=$bounds"
        }

        val historySummary = stepHistory.takeLast(5).joinToString("\n") { record ->
            "Step ${record.step}: action=${record.action} target=${record.target} result=${record.result}"
        }

        val availableActions = """
Available actions:
- OBSERVE: Just observe the screen, take no action
- TAP: Click on an element. Parameters: target (ref ID like "e1" or text description)
- TYPE: Type text into an element. Parameters: target (ref ID), value (text to type)
- SCROLL: Scroll the screen. Parameters: direction (up/down/left/right)
- PRESS_BACK: Press the back button
- PRESS_HOME: Press the home button
- task_complete: The task has been completed successfully
- task_failed: The task cannot be completed. Parameters: reason
""".trimIndent()

        val prompt = """You are a phone automation agent. Your task: $task

Current screen observation:
$observation

Available element refs:
$refList

Step history (recent):
$historySummary

$availableActions

Respond with EXACTLY one line in this format:
ACTION|target|key1=val1,key2=val2

Examples:
TAP|e3|
TYPE|e5|value=hello world
SCROLL||direction=down
PRESS_BACK||
PRESS_HOME||
OBSERVE||
task_complete||
task_failed||reason=could not find the button

What is your next action?"""

        return try {
            val request = ModelCallRequest(
                model = modelName,
                messages = listOf(
                    ModelMessage(role = "system", content = "You are a phone automation agent. Respond with exactly one action line."),
                    ModelMessage(role = "user", content = prompt)
                ),
                temperature = 0.1f,
                maxTokens = 128
            )

            val response = modelClient.chatCompletion(request)
            val content = response.choices.firstOrNull()?.message?.content?.trim() ?: ""

            Timber.d("Step %d: LLM decision: %s", step, content)
            parseAction(content)
        } catch (e: Exception) {
            Timber.e(e, "LLM decision failed at step %d", step)
            NextAction(ActionType.OBSERVE)
        }
    }

    private fun parseAction(raw: String): NextAction {
        val line = raw.lines().firstOrNull { it.isNotBlank() } ?: return NextAction(ActionType.OBSERVE)

        val lowerLine = line.lowercase().trim()
        if (lowerLine == "task_complete") return NextAction(ActionType.TASK_COMPLETE)
        if (lowerLine.startsWith("task_failed")) {
            val reason = extractParamValue(line, "reason") ?: "task failed"
            return NextAction(ActionType.TASK_FAILED, parameters = mapOf("reason" to reason))
        }

        val parts = line.split("|")
        if (parts.isEmpty()) return NextAction(ActionType.OBSERVE)

        val actionStr = parts[0].trim().uppercase()
        val type = try {
            ActionType.valueOf(actionStr)
        } catch (_: Exception) {
            when {
                actionStr == "CLICK" -> ActionType.TAP
                actionStr == "INPUT" -> ActionType.TYPE
                else -> return NextAction(ActionType.OBSERVE)
            }
        }

        val target = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        val parameters = parts.getOrNull(2)?.trim()?.let { parseKeyValuePairs(it) } ?: emptyMap()

        return NextAction(type, target, parameters)
    }

    private fun parseKeyValuePairs(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (pair in raw.split(",")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                result[kv[0].trim()] = kv[1].trim()
            }
        }
        return result
    }

    private fun extractParamValue(raw: String, key: String): String? {
        val regex = Regex("""${key}=(.+?)(?:,|$)""", RegexOption.IGNORE_CASE)
        return regex.find(raw)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun buildObservationSummary(result: ObserveResult): String {
        val sb = StringBuilder()
        sb.append("Window: ${result.window ?: "unknown"}\n")
        sb.append("Screen: ${result.screenSize?.width}x${result.screenSize?.height}\n")
        sb.append("Nodes: ${result.nodeCount}, Interactive: ${result.interactiveCount}\n")
        result.screenshotAnalysis?.let { analysis ->
            sb.append("VLM: app=${analysis.currentApp}, page=${analysis.currentPage}\n")
            sb.append("Summary: ${analysis.screenSummary}\n")
        }
        return sb.toString()
    }

    private fun flattenSerializedNodes(nodes: List<SerializedNode>): List<SerializedNode> {
        val result = mutableListOf<SerializedNode>()
        for (node in nodes) {
            flattenRecursive(node, result)
        }
        return result
    }

    private fun flattenRecursive(node: SerializedNode, acc: MutableList<SerializedNode>) {
        acc.add(node)
        node.children?.forEach { flattenRecursive(it, acc) }
    }
}
