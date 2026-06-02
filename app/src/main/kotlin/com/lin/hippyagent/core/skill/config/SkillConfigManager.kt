package com.lin.hippyagent.core.skill.config

import com.lin.hippyagent.core.skill.SkillConfig
import com.lin.hippyagent.core.util.FileUtils
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class SkillConfigManager(
    private val skillsDir: File
) {
    private val configDir = File(skillsDir, "_config").also { it.mkdirs() }
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val secretKey by lazy {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(SECRET_PHRASE, SALT, 10000, 256)
        val secret = factory.generateSecret(spec)
        SecretKeySpec(secret.encoded, "AES")
    }

    fun load(skillId: String): SkillConfig {
        val configFile = File(configDir, "$skillId.json")
        if (!configFile.exists()) return SkillConfig(skillId)
        return runCatching {
            json.decodeFromString<SkillConfig>(configFile.readText())
        }.getOrElse { SkillConfig(skillId) }
    }

    fun save(config: SkillConfig): Result<Unit> = runCatching {
        val configFile = File(configDir, "${config.skillId}.json")
        FileUtils.atomicWrite(configFile, json.encodeToString(SkillConfig.serializer(), config))
    }

    fun getSecret(skillId: String, key: String): String? {
        val config = load(skillId)
        val encrypted = config.secrets[key] ?: return null
        return runCatching { decrypt(encrypted) }.getOrNull()
    }

    fun saveSecret(skillId: String, key: String, value: String): Result<Unit> {
        val encrypted = runCatching { encrypt(value) }.getOrElse { value }
        val currentConfig = load(skillId)
        val updatedConfig = currentConfig.copy(secrets = currentConfig.secrets + (key to encrypted))
        return save(updatedConfig)
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(iv + encrypted)
    }

    private fun decrypt(encoded: String): String {
        val data = java.util.Base64.getDecoder().decode(encoded)
        val iv = data.copyOfRange(0, 16)
        val encrypted = data.copyOfRange(16, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        private val SECRET_PHRASE = "hippy-agent-skill-config".toCharArray()
        private val SALT = byteArrayOf(0x48, 0x69, 0x70, 0x70, 0x79, 0x41, 0x67, 0x65, 0x6E, 0x74, 0x53, 0x61, 0x6C, 0x74, 0x30, 0x31)
    }
}
