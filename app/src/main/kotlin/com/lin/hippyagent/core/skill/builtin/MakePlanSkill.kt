package com.lin.hippyagent.core.skill.builtin

import android.content.Context
import timber.log.Timber
import java.io.File
import java.util.UUID

/**
 * Make Plan - 计划生成
 * 分析任务描述，生成结构化执行计划，支持状态跟踪
 */
class MakePlanSkill(private val context: Context) {
    companion object {
        private val idRegex = Regex(""""id"\s*:\s*"([^"]+)"""")
        private val taskRegex = Regex(""""task"\s*:\s*"([^"]+)"""")
        private val statusRegex = Regex(""""status"\s*:\s*"([^"]+)"""")
        private val createdAtRegex = Regex(""""createdAt"\s*:\s*(\d+)""")
        private val stepsRegex = Regex(""""steps"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    }

    private val plansDir: File get() = File(context.filesDir, "plans")

    data class Plan(
        val id: String,
        val task: String,
        val steps: List<String>,
        val status: String = "pending",
        val createdAt: Long = System.currentTimeMillis()
    )

    fun generatePlan(task: String): Result<Plan> {
        return try {
            val planId = UUID.randomUUID().toString().take(8)
            val steps = analyzeTask(task)
            val plan = Plan(id = planId, task = task, steps = steps)

            // 持久化计划
            plansDir.mkdirs()
            val planFile = File(plansDir, "$planId.json")
            planFile.writeText(serializePlan(plan))

            Timber.d("Generated plan $planId with ${steps.size} steps for: ${task.take(50)}")
            Result.success(plan)
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate plan")
            Result.failure(e)
        }
    }

    fun getPlanStatus(planId: String): Result<String> {
        return try {
            val planFile = File(plansDir, "$planId.json")
            if (!planFile.exists()) {
                return Result.success("计划 '$planId' 不存在")
            }
            val plan = deserializePlan(planFile.readText())
            val sb = StringBuilder()
            sb.appendLine("📋 计划 ${plan.id}")
            sb.appendLine("任务: ${plan.task}")
            sb.appendLine("状态: ${plan.status}")
            sb.appendLine("创建时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(plan.createdAt)}")
            sb.appendLine()
            plan.steps.forEachIndexed { index, step ->
                val check = if (plan.status == "completed") "✅" else "⬜"
                sb.appendLine("$check ${index + 1}. $step")
            }
            Result.success(sb.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun completePlan(planId: String): Result<String> {
        return try {
            val planFile = File(plansDir, "$planId.json")
            if (!planFile.exists()) return Result.failure(IllegalArgumentException("Plan not found: $planId"))
            val plan = deserializePlan(planFile.readText())
            val completed = plan.copy(status = "completed")
            planFile.writeText(serializePlan(completed))
            Result.success("计划 '$planId' 已标记为完成")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun listPlans(): Result<String> {
        return try {
            if (!plansDir.exists()) return Result.success("暂无计划")
            val plans = plansDir.listFiles()?.filter { it.extension == "json" }?.mapNotNull {
                try { deserializePlan(it.readText()) } catch (_: Exception) { null }
            } ?: emptyList()
            if (plans.isEmpty()) return Result.success("暂无计划")
            val sb = StringBuilder("📋 计划列表\n\n")
            plans.forEach { plan ->
                val status = if (plan.status == "completed") "✅" else "⬜"
                sb.appendLine("$status ${plan.id}: ${plan.task.take(40)} (${plan.steps.size} 步)")
            }
            Result.success(sb.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun analyzeTask(task: String): List<String> {
        val steps = mutableListOf<String>()
        val lowerTask = task.lowercase()

        // 基于关键词分析任务类型并生成步骤
        when {
            lowerTask.contains("ci") || lowerTask.contains("cd") || lowerTask.contains("部署") || lowerTask.contains("deploy") -> {
                steps.addAll(listOf(
                    "分析项目结构和构建系统",
                    "配置 CI/CD 流水线文件",
                    "设置构建环境和依赖",
                    "配置测试和质量检查",
                    "配置部署目标和凭证",
                    "测试流水线执行",
                    "文档化部署流程"
                ))
            }
            lowerTask.contains("api") || lowerTask.contains("接口") || lowerTask.contains("endpoint") -> {
                steps.addAll(listOf(
                    "分析需求，定义 API 规范",
                    "设计数据模型和接口契约",
                    "实现路由和控制器",
                    "实现业务逻辑层",
                    "添加认证和授权",
                    "编写单元测试和集成测试",
                    "部署和文档化"
                ))
            }
            lowerTask.contains("ui") || lowerTask.contains("界面") || lowerTask.contains("前端") || lowerTask.contains("frontend") -> {
                steps.addAll(listOf(
                    "分析 UI 需求和设计稿",
                    "设计组件架构",
                    "实现基础布局和导航",
                    "实现核心页面组件",
                    "添加交互和状态管理",
                    "样式优化和响应式适配",
                    "测试和无障碍检查"
                ))
            }
            lowerTask.contains("数据") || lowerTask.contains("data") || lowerTask.contains("分析") || lowerTask.contains("analysis") -> {
                steps.addAll(listOf(
                    "明确分析目标和数据源",
                    "数据收集和清洗",
                    "探索性数据分析",
                    "选择分析方法和模型",
                    "执行分析和验证",
                    "可视化结果",
                    "撰写分析报告"
                ))
            }
            lowerTask.contains("重构") || lowerTask.contains("refactor") || lowerTask.contains("优化") || lowerTask.contains("optimize") -> {
                steps.addAll(listOf(
                    "分析现有代码结构",
                    "识别问题和改进点",
                    "制定重构计划",
                    "编写测试用例（重构前基线）",
                    "逐步实施重构",
                    "运行测试验证",
                    "性能对比和文档更新"
                ))
            }
            lowerTask.contains("测试") || lowerTask.contains("test") -> {
                steps.addAll(listOf(
                    "分析被测功能的需求",
                    "设计测试策略和用例",
                    "搭建测试环境",
                    "编写单元测试",
                    "编写集成测试",
                    "执行测试并分析结果",
                    "修复问题并回归测试"
                ))
            }
            else -> {
                // 通用任务分解
                steps.addAll(listOf(
                    "明确任务目标和约束条件",
                    "调研和收集必要信息",
                    "制定详细执行方案",
                    "分步实施核心功能",
                    "验证和测试结果",
                    "文档化和总结"
                ))
            }
        }
        return steps
    }

    private fun serializePlan(plan: Plan): String {
        return """{"id":"${plan.id}","task":"${plan.task.replace("\"", "\\\"")}","steps":[${plan.steps.map { "\"${it.replace("\"", "\\\"")}\"" }.joinToString(",")}],"status":"${plan.status}","createdAt":${plan.createdAt}}"""
    }

    private fun deserializePlan(json: String): Plan {
        val id = idRegex.find(json)?.groupValues?.get(1) ?: ""
        val task = taskRegex.find(json)?.groupValues?.get(1) ?: ""
        val status = statusRegex.find(json)?.groupValues?.get(1) ?: "pending"
        val createdAt = createdAtRegex.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val steps = stepsRegex.find(json)
            ?.groupValues?.get(1)
            ?.split("\",\"")
            ?.map { it.trim('"', ' ') }
            ?: emptyList()
        return Plan(id = id, task = task, steps = steps, status = status, createdAt = createdAt)
    }
}


