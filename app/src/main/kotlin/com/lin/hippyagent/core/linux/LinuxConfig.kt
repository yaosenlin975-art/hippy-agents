package com.lin.hippyagent.core.linux

import android.content.Context
import timber.log.Timber
import java.io.File
import java.util.Properties

/**
 * ALinux 配置文件解析器
 * 解析 alinux.conf 配置文件
 */
class LinuxConfig(
    private val context: Context
) {
    private val properties = Properties()
    private var isLoaded = false

    companion object {
        // 配置文件路径
        private const val CONFIG_FILE = "etc/alinux.conf"

        // 配置键
        const val KEY_CONTAINER_ID = "container.id"
        const val KEY_CONTAINER_HOSTNAME = "container.hostname"
        const val KEY_CONTAINER_USER = "container.user"
        const val KEY_CONTAINER_WORKDIR = "container.workdir"
        const val KEY_SSH_PORT = "ssh.port"
        const val KEY_SSH_PASSWORD = "ssh.password"
        const val KEY_IMAGE_URL = "image.url"
        const val KEY_IMAGE_CHECKSUM = "image.checksum"
        const val KEY_PROXY_HTTP = "proxy.http"
        const val KEY_PROXY_HTTPS = "proxy.https"
        const val KEY_DNS_SERVERS = "dns.servers"
        const val KEY_LOCALE = "locale"
        const val KEY_TIMEZONE = "timezone"

        // 默认值
        const val DEFAULT_CONTAINER_ID = "ubuntu"
        const val DEFAULT_CONTAINER_HOSTNAME = "ubuntu-android"
        const val DEFAULT_CONTAINER_USER = "root"
        const val DEFAULT_CONTAINER_WORKDIR = "/root"
        const val DEFAULT_SSH_PORT = "2224"
        const val DEFAULT_SSH_PASSWORD = "root"
        const val DEFAULT_LOCALE = "C.UTF-8"
        const val DEFAULT_TIMEZONE = "UTC"
        const val DEFAULT_DNS_SERVERS = "8.8.8.8,8.8.4.4"
    }

    /**
     * 加载配置文件
     */
    fun load(): Result<Unit> = runCatching {
        val configFile = File(context.rootfsDir, CONFIG_FILE)
        if (!configFile.exists()) {
            Timber.w("Config file not found: ${configFile.absolutePath}")
            loadDefaults()
            return@runCatching
        }

        configFile.inputStream().use { input ->
            properties.load(input)
        }
        isLoaded = true
        Timber.d("Config loaded from ${configFile.absolutePath}")
    }

    /**
     * 加载默认配置
     */
    private fun loadDefaults() {
        properties.setProperty(KEY_CONTAINER_ID, DEFAULT_CONTAINER_ID)
        properties.setProperty(KEY_CONTAINER_HOSTNAME, DEFAULT_CONTAINER_HOSTNAME)
        properties.setProperty(KEY_CONTAINER_USER, DEFAULT_CONTAINER_USER)
        properties.setProperty(KEY_CONTAINER_WORKDIR, DEFAULT_CONTAINER_WORKDIR)
        properties.setProperty(KEY_SSH_PORT, DEFAULT_SSH_PORT)
        properties.setProperty(KEY_SSH_PASSWORD, DEFAULT_SSH_PASSWORD)
        properties.setProperty(KEY_LOCALE, DEFAULT_LOCALE)
        properties.setProperty(KEY_TIMEZONE, DEFAULT_TIMEZONE)
        properties.setProperty(KEY_DNS_SERVERS, DEFAULT_DNS_SERVERS)
        isLoaded = true
    }

    /**
     * 保存配置文件
     */
    fun save(): Result<Unit> = runCatching {
        val configFile = File(context.rootfsDir, CONFIG_FILE)
        configFile.parentFile?.mkdirs()

        configFile.outputStream().use { output ->
            properties.store(output, "ALinux Configuration")
        }
        Timber.d("Config saved to ${configFile.absolutePath}")
    }

    /**
     * 获取配置值
     */
    fun get(key: String, defaultValue: String = ""): String {
        return properties.getProperty(key, defaultValue)
    }

    /**
     * 设置配置值
     */
    fun set(key: String, value: String) {
        properties.setProperty(key, value)
    }

    /**
     * 获取容器 ID
     */
    fun getContainerId(): String = get(KEY_CONTAINER_ID, DEFAULT_CONTAINER_ID)

    /**
     * 获取容器主机名
     */
    fun getContainerHostname(): String = get(KEY_CONTAINER_HOSTNAME, DEFAULT_CONTAINER_HOSTNAME)

    /**
     * 获取容器用户
     */
    fun getContainerUser(): String = get(KEY_CONTAINER_USER, DEFAULT_CONTAINER_USER)

    /**
     * 获取容器工作目录
     */
    fun getContainerWorkdir(): String = get(KEY_CONTAINER_WORKDIR, DEFAULT_CONTAINER_WORKDIR)

    /**
     * 获取 SSH 端口
     */
    fun getSshPort(): Int = get(KEY_SSH_PORT, DEFAULT_SSH_PORT).toIntOrNull() ?: 2224

    /**
     * 获取 SSH 密码
     */
    fun getSshPassword(): String = get(KEY_SSH_PASSWORD, DEFAULT_SSH_PASSWORD)

    /**
     * 获取镜像 URL
     */
    fun getImageUrl(): String = get(KEY_IMAGE_URL, "")

    /**
     * 获取镜像校验和
     */
    fun getImageChecksum(): String = get(KEY_IMAGE_CHECKSUM, "")

    /**
     * 获取 HTTP 代理
     */
    fun getProxyHttp(): String = get(KEY_PROXY_HTTP, "")

    /**
     * 获取 HTTPS 代理
     */
    fun getProxyHttps(): String = get(KEY_PROXY_HTTPS, "")

    /**
     * 获取 DNS 服务器列表
     */
    fun getDnsServers(): List<String> {
        val dnsString = get(KEY_DNS_SERVERS, DEFAULT_DNS_SERVERS)
        return dnsString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * 获取区域设置
     */
    fun getLocale(): String = get(KEY_LOCALE, DEFAULT_LOCALE)

    /**
     * 获取时区
     */
    fun getTimezone(): String = get(KEY_TIMEZONE, DEFAULT_TIMEZONE)

    /**
     * 转换为 ContainerConfig
     */
    fun toContainerConfig(): ContainerConfig {
        return ContainerConfig(
            cmd = listOf("/bin/bash"),
            user = getContainerUser(),
            hostname = getContainerHostname(),
            workingDir = getContainerWorkdir(),
            env = buildMap {
                put("LANG", getLocale())
                put("TZ", getTimezone())
                put("SSH_PORT", getSshPort().toString())
                put("SSH_PASSWORD", getSshPassword())
            }
        )
    }

    /**
     * 获取所有配置
     */
    fun getAll(): Map<String, String> {
        return properties.stringPropertyNames().associateWith { key ->
            properties.getProperty(key, "")
        }
    }
}

