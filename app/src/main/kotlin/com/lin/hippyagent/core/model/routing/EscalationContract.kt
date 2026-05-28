package com.lin.hippyagent.core.model.routing

object EscalationContract {
    private const val FLASH_CONTRACT = """
当你判断当前任务明显超出你的能力范围时（如：复杂的跨文件架构重构、微妙的并发/安全/正确性问题、你无法自信解决的场景），你可以在回复的第一行输出升级标记：

<<<NEEDS_PRO>>> 或 <<<NEEDS_PRO: 一句话原因>>>

输出升级标记后，系统会自动将你切换到更强大的模型来处理。请仅在确实需要时使用此标记。
"""

    private const val PRO_CONTRACT = """
你是当前可用的最强模型，升级标记对你无效。直接给出最佳答案即可。
"""

    fun getContract(isHeavyModel: Boolean): String {
        return if (isHeavyModel) PRO_CONTRACT.trimIndent() else FLASH_CONTRACT.trimIndent()
    }
}
