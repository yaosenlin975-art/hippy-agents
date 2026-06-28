package com.lin.hippyagent.core.trace

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.traceDataStore by preferencesDataStore(name = "trace_settings")

class TraceSettings(private val context: Context) {

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("enabled")
        private val KEY_SENSITIVE_MASKING = booleanPreferencesKey("sensitive_masking")
        private val KEY_RETENTION_DAYS = intPreferencesKey("retention_days")
        private val KEY_FULL_LLM_CONTENT = booleanPreferencesKey("full_llm_content")

        const val RETENTION_1_DAY = 1
        const val RETENTION_7_DAYS = 7
        const val RETENTION_30_DAYS = 30
        const val RETENTION_FOREVER = 0  // 0 表示永久
    }

    val enabled: Flow<Boolean> = context.traceDataStore.data.map { it[KEY_ENABLED] ?: false }
    val sensitiveMasking: Flow<Boolean> = context.traceDataStore.data.map { it[KEY_SENSITIVE_MASKING] ?: true }
    val retentionDays: Flow<Int> = context.traceDataStore.data.map { it[KEY_RETENTION_DAYS] ?: 7 }
    val fullLlmContent: Flow<Boolean> = context.traceDataStore.data.map { it[KEY_FULL_LLM_CONTENT] ?: false }

    suspend fun setEnabled(value: Boolean) {
        context.traceDataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setSensitiveMasking(value: Boolean) {
        context.traceDataStore.edit { it[KEY_SENSITIVE_MASKING] = value }
    }

    suspend fun setRetentionDays(value: Int) {
        context.traceDataStore.edit { it[KEY_RETENTION_DAYS] = value }
    }

    suspend fun setFullLlmContent(value: Boolean) {
        context.traceDataStore.edit { it[KEY_FULL_LLM_CONTENT] = value }
    }
}
