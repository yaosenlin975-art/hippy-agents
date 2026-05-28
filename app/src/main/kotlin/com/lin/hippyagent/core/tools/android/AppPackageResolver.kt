package com.lin.hippyagent.core.tools.android

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

@Serializable
data class AppPackagesConfig(
    val appPackages: Map<String, String> = emptyMap()
)

object AppPackageResolver {

    private val json = Json { ignoreUnknownKeys = true }

    private var aliasMap: Map<String, String> = emptyMap()
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        try {
            val configText = context.assets.open("app_packages.json")
                .bufferedReader().use { it.readText() }
            val config = json.decodeFromString<AppPackagesConfig>(configText)
            aliasMap = config.appPackages
            Timber.i("AppPackageResolver: loaded ${aliasMap.size} app aliases")
        } catch (e: Exception) {
            Timber.w(e, "Failed to load app_packages.json")
            aliasMap = emptyMap()
        }
        initialized = true
    }

    fun resolvePackageName(input: String, packageManager: PackageManager): String? {
        if (input.contains(".")) return input

        val exactMatch = aliasMap[input]
        if (exactMatch != null) return exactMatch

        val lowerInput = input.lowercase()
        for ((alias, pkg) in aliasMap) {
            if (alias.lowercase() == lowerInput) return pkg
        }

        for ((alias, pkg) in aliasMap) {
            if (alias.lowercase().contains(lowerInput) || lowerInput.contains(alias.lowercase())) {
                return pkg
            }
        }

        return try {
            val apps = packageManager.getInstalledApplications(0)
            for (app in apps) {
                val label = app.loadLabel(packageManager).toString()
                if (label.equals(input, ignoreCase = true)) {
                    return app.packageName
                }
            }
            for (app in apps) {
                val label = app.loadLabel(packageManager).toString()
                if (label.contains(input, ignoreCase = true)) {
                    return app.packageName
                }
            }
            null
        } catch (e: Exception) {
            Timber.w(e, "Failed to resolve package name for: $input")
            null
        }
    }

    fun getAliasMap(): Map<String, String> = aliasMap.toMap()
}
