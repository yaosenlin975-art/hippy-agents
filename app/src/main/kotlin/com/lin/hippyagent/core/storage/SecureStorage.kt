package com.lin.hippyagent.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.io.File

class SecureStorage(
    context: Context
) {
    private val prefs: SharedPreferences

    init {
        prefs = try {
            createEncryptedPrefs(context)
        } catch (e: Exception) {
            Timber.e(e, "SecureStorage: failed to create encrypted prefs, attempting recovery")
            try {
                // Delete corrupted data and retry
                recoverAndRetry(context)
            } catch (retryError: Exception) {
                Timber.e(retryError, "SecureStorage: recovery also failed, using fallback plain prefs")
                // Fallback: non-encrypted prefs to avoid crash (data already lost)
                context.getSharedPreferences("secure_prefs_fallback", Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun recoverAndRetry(context: Context): SharedPreferences {
        // Delete corrupted preferences file
        val prefsFile = File(context.filesDir.parentFile, "shared_prefs/secure_prefs.xml")
        if (prefsFile.exists()) {
            val deleted = prefsFile.delete()
            Timber.w("SecureStorage: deleted corrupted prefs file: $deleted")
        }
        // Also try deleting the backup file
        val backupFile = File(context.filesDir.parentFile, "shared_prefs/secure_prefs.xml.bak")
        if (backupFile.exists()) {
            backupFile.delete()
        }
        // Recreate from scratch
        return createEncryptedPrefs(context)
    }

    fun saveApiKey(providerId: String, apiKey: String) {
        prefs.edit().putString("api_key_$providerId", apiKey).apply()
        Timber.d("API key saved for provider: $providerId")
    }

    fun getApiKey(providerId: String): String? {
        return try {
            prefs.getString("api_key_$providerId", null)
        } catch (e: Exception) {
            Timber.e(e, "SecureStorage: failed to read API key for $providerId")
            null
        }
    }

    fun removeApiKey(providerId: String) {
        prefs.edit().remove("api_key_$providerId").apply()
        Timber.d("API key removed for provider: $providerId")
    }

    fun saveSecret(key: String, value: String) {
        prefs.edit().putString("secret_$key", value).apply()
    }

    fun getSecret(key: String): String? {
        return try {
            prefs.getString("secret_$key", null)
        } catch (e: Exception) {
            Timber.e(e, "SecureStorage: failed to read secret for $key")
            null
        }
    }

    fun removeSecret(key: String) {
        prefs.edit().remove("secret_$key").apply()
    }

    fun saveToken(key: String, token: String) {
        prefs.edit().putString("token_$key", token).apply()
    }

    fun getToken(key: String): String? {
        return try {
            prefs.getString("token_$key", null)
        } catch (e: Exception) {
            Timber.e(e, "SecureStorage: failed to read token for $key")
            null
        }
    }

    fun removeToken(key: String) {
        prefs.edit().remove("token_$key").apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        Timber.w("All secure data cleared")
    }

    fun hasApiKey(providerId: String): Boolean {
        return try {
            prefs.contains("api_key_$providerId")
        } catch (e: Exception) {
            false
        }
    }

    fun listApiKeys(): List<String> {
        return try {
            prefs.all.keys
                .filter { it.startsWith("api_key_") }
                .map { it.removePrefix("api_key_") }
        } catch (e: Exception) {
            Timber.e(e, "SecureStorage: failed to list API keys")
            emptyList()
        }
    }
}

