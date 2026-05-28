package com.lin.hippyagent.core.security

import android.content.Context
import android.app.ActivityManager
import androidx.biometric.BiometricManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_guard_settings")

class ToolGuardSettings(private val context: Context) {

    val executionLevelFlow: Flow<ToolExecutionLevel> = context.dataStore.data
        .map { preferences ->
            val levelStr = preferences[stringPreferencesKey("execution_level")] ?: "auto"
            ToolExecutionLevel.fromConfig(levelStr)
        }

    suspend fun setExecutionLevel(level: ToolExecutionLevel) {
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey("execution_level")] = level.value
        }
    }

    companion object {
        @Volatile
        private var instance: ToolGuardSettings? = null

        fun getInstance(context: Context): ToolGuardSettings {
            return instance ?: synchronized(this) {
                instance ?: ToolGuardSettings(context.applicationContext).also { instance = it }
            }
        }
    }
}

object ToolExecutionLevelAdvisor {
    fun recommendLevel(context: Context): ToolExecutionLevel {
        val isLowEndDevice = isLowEndDevice(context)
        val hasBiometric = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

        return when {
            hasBiometric && !isLowEndDevice -> ToolExecutionLevel.SMART
            isLowEndDevice -> ToolExecutionLevel.AUTO
            else -> ToolExecutionLevel.STRICT
        }
    }

    private fun isLowEndDevice(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem < 4L * 1024 * 1024 * 1024
    }
}

