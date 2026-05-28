package com.lin.hippyagent.core.plugin

import android.content.Context
import com.lin.hippyagent.core.tools.Tool
import com.lin.hippyagent.core.tools.ToolCall
import com.lin.hippyagent.core.tools.ToolContext
import com.lin.hippyagent.core.tools.ToolDefinition
import com.lin.hippyagent.core.tools.ToolParameter
import com.lin.hippyagent.core.tools.ToolRegistry
import com.lin.hippyagent.core.tools.ToolResult
import com.lin.hippyagent.core.tools.ToolOwnership
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

@Serializable
data class PluginManifest(
    val name: String,
    val version: String = "1.0.0",
    val description: String = "",
    val author: String = "",
    val tools: List<PluginToolDef>
)

@Serializable
data class PluginToolDef(
    val name: String,
    val description: String,
    val parameters: List<PluginParamDef> = emptyList(),
    val script: String = ""
)

@Serializable
data class PluginParamDef(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val required: Boolean = true,
    val default: String? = null
)

class UserPluginTool(
    override val definition: ToolDefinition,
    private val scriptExecutor: PluginScriptExecutor,
    private val script: String
) : Tool() {
    override suspend fun execute(arguments: Map<String, Any>): ToolResult {
        return scriptExecutor.execute(script, arguments, definition.name)
    }

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any>): ToolResult {
        return scriptExecutor.execute(script, args, definition.name)
    }
}

class PluginScriptExecutor(private val context: Context) {
    suspend fun execute(
        script: String,
        arguments: Map<String, Any>,
        toolName: String
    ): ToolResult = withContext(Dispatchers.IO) {
        runCatching {
            val argsJson = com.lin.hippyagent.core.task.HippyJobJson.mapToJson(arguments)
            val scriptFile = File(context.cacheDir, "plugin_${System.currentTimeMillis()}.js")
            scriptFile.writeText(script)

            val process = ProcessBuilder("node", scriptFile.absolutePath)
                .apply {
                    environment()["PLUGIN_ARGS"] = argsJson
                    redirectErrorStream(true)
                }
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            scriptFile.delete()

            if (process.exitValue() == 0) {
                ToolResult(
                    callId = com.lin.hippyagent.core.pool.FastId.next(),
                    success = true,
                    output = output.trim()
                )
            } else {
                ToolResult(
                    callId = com.lin.hippyagent.core.pool.FastId.next(),
                    success = false,
                    error = output.trim()
                )
            }
        }.getOrElse { e ->
            ToolResult(
                callId = com.lin.hippyagent.core.pool.FastId.next(),
                success = false,
                error = "Plugin execution error: ${e.message}"
            )
        }
    }
}

class PluginManager(
    private val context: Context,
    private val toolRegistry: ToolRegistry
) {
    private val _pluginDir by lazy {
        File(context.filesDir, "plugins").apply { mkdirs() }
    }

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val scriptExecutor = PluginScriptExecutor(context)
    private val loadedPlugins = mutableMapOf<String, PluginManifest>()

    fun getPluginDir(): File = _pluginDir

    fun getLoadedPlugins(): Map<String, PluginManifest> = loadedPlugins.toMap()

    fun loadAll() {
        _pluginDir.listFiles { f -> f.name.endsWith(".json") }?.forEach { file ->
            runCatching {
                val manifest = json.decodeFromString<PluginManifest>(file.readText())
                registerPlugin(manifest)
            }.onFailure { e ->
                Timber.e(e, "Failed to load plugin from ${file.name}")
            }
        }
    }

    fun registerPlugin(manifest: PluginManifest) {
        if (loadedPlugins.containsKey(manifest.name)) {
            unregisterPlugin(manifest.name)
        }

        for (toolDef in manifest.tools) {
            val params = toolDef.parameters.associate { param ->
                param.name to ToolParameter(
                    name = param.name,
                    type = param.type,
                    description = param.description,
                    required = param.required,
                    defaultValue = param.default
                )
            }

            val definition = ToolDefinition(
                name = toolDef.name,
                description = toolDef.description,
                parameters = params,
                ownership = ToolOwnership.SHARED
            )

            val tool = UserPluginTool(definition, scriptExecutor, toolDef.script)
            toolRegistry.register(tool)
        }

        loadedPlugins[manifest.name] = manifest
        Timber.i("Plugin registered: ${manifest.name} with ${manifest.tools.size} tools")
    }

    fun unregisterPlugin(name: String) {
        val manifest = loadedPlugins.remove(name) ?: return
        for (toolDef in manifest.tools) {
            toolRegistry.unregister(toolDef.name)
        }
        Timber.i("Plugin unregistered: $name")
    }

    fun savePlugin(manifest: PluginManifest) {
        val file = File(_pluginDir, "${manifest.name}.json")
        file.writeText(json.encodeToString(manifest))
        registerPlugin(manifest)
    }

    fun deletePlugin(name: String) {
        unregisterPlugin(name)
        val file = File(_pluginDir, "$name.json")
        if (file.exists()) file.delete()
    }

    fun createSamplePlugin(): PluginManifest {
        return PluginManifest(
            name = "sample_plugin",
            description = "示例插件",
            author = "User",
            tools = listOf(
                PluginToolDef(
                    name = "hello_world",
                    description = "打印问候语",
                    parameters = listOf(
                        PluginParamDef(name = "name", description = "名字", required = true)
                    ),
                    script = """const args = JSON.parse(process.env.PLUGIN_ARGS || '{}');
                        |console.log('Hello, ' + (args.name || 'World') + '!');""".trimMargin()
                )
            )
        )
    }
}

