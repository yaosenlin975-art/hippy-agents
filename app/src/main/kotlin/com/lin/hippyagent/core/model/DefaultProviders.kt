package com.lin.hippyagent.core.model

/**
 * 默认内置模型提供商列表 — 首次启动时自动添加
 */
val DEFAULT_MODEL_PROVIDERS = listOf(
    ModelProvider(
        id = "minimax-cn",
        name = "MiniMax (China)",
        baseUrl = "https://api.minimaxi.com/anthropic",
        apiKey = "",
        protocol = "anthropic",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "minimax-intl",
        name = "MiniMax (International)",
        baseUrl = "https://api.minimax.io/anthropic",
        apiKey = "",
        protocol = "anthropic",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "openrouter",
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "opencode",
        name = "OpenCode",
        baseUrl = "https://opencode.ai/zen/v1",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = true,
        models = listOf(
            ModelConfig(
                id = "deepseek-v4-flash-free",
                providerId = "opencode",
                name = "deepseek-v4-flash-free",
                displayName = "DeepSeek V4 Flash (Free)",
                enabled = true,
                isDefault = true,
                free = true,
                capabilities = setOf(ModelCapability.STREAMING, ModelCapability.TOOL_CALL)
            )
        )
    ),
    ModelProvider(
        id = "openai",
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "anthropic",
        name = "Anthropic",
        baseUrl = "https://api.anthropic.com",
        apiKey = "",
        protocol = "anthropic",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "google-gemini",
        name = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "deepseek",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "kimi-cn",
        name = "Kimi (China)",
        baseUrl = "https://api.moonshot.cn/v1",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "kimi-intl",
        name = "Kimi (International)",
        baseUrl = "https://api.moonshot.ai/v1",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "zhipu-bigmodel",
        name = "Zhipu (BigModel)",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "zhipu-coding-bigmodel",
        name = "Zhipu Coding Plan (BigModel)",
        baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "zhipu-zai",
        name = "Zhipu (Z.AI)",
        baseUrl = "https://api.z.ai/api/paas/v4",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "zhipu-coding-zai",
        name = "Zhipu Coding Plan (Z.AI)",
        baseUrl = "https://api.z.ai/api/coding/paas/v4",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "siliconflow-cn",
        name = "SiliconFlow (China)",
        baseUrl = "https://api.siliconflow.cn/v1",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "siliconflow-intl",
        name = "SiliconFlow (International)",
        baseUrl = "https://api.siliconflow.com/v1",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "xiaomi-token-plan",
        name = "XiaoMi (Token Plan)",
        baseUrl = "https://token-plan-cn.xiaomimimo.com/v1",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "xiaomi",
        name = "XiaoMi",
        baseUrl = "https://api.xiaomimimo.com/v1",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    ),
    ModelProvider(
        id = "sensenova",
        name = "SenseNova",
        baseUrl = "https://token.sensenova.cn/v1",
        apiKey = "",
        protocol = "openai",
        enabled = true,
        isDefault = false
    )
)

