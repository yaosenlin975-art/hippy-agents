package com.lin.hippyagent.core.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

/**
 * URL SSRF 防护检查器。
 *
 * 双重检查：
 * 1. DNS 解析前拦截字面量私网 IP + 域名黑名单（云元数据/mDNS .local/.internal）
 * 2. DNS 解析后校验防 DNS rebinding
 *
 * fail-closed：任何异常一律拒绝。
 *
 * 设计依据：spec T3-1，参考 openclaw src/infra/net/ssrf.ts
 */
object UrlSafetyChecker {

    enum class RejectReason {
        INVALID_URL,
        NON_HTTP_SCHEME,
        BLOCKED_HOSTNAME,
        BLOCKED_RESOLVED_IP,
        DNS_RESOLUTION_FAILED
    }

    data class CheckResult(
        val allowed: Boolean,
        val rejectReason: RejectReason? = null,
        val resolvedIps: List<InetAddress> = emptyList(),
        val message: String? = null
    )

    suspend fun check(url: String): CheckResult = withContext(Dispatchers.IO) {
        try {
            val parsed = URL(url)
            if (parsed.protocol !in setOf("http", "https")) {
                return@withContext CheckResult(false, RejectReason.NON_HTTP_SCHEME,
                    message = "非 http/https scheme: ${parsed.protocol}")
            }
            val host = parsed.host ?: return@withContext CheckResult(false,
                RejectReason.INVALID_URL, message = "URL 无 host")

            if (isBlockedHostname(host)) {
                return@withContext CheckResult(false, RejectReason.BLOCKED_HOSTNAME,
                    message = "拦截域名: $host")
            }

            val allIps = try {
                InetAddress.getAllByName(host).toList()
            } catch (e: Exception) {
                return@withContext CheckResult(false, RejectReason.DNS_RESOLUTION_FAILED,
                    message = "DNS 解析失败: ${e.message}")
            }

            for (ip in allIps) {
                if (isBlockedIp(ip)) {
                    return@withContext CheckResult(false, RejectReason.BLOCKED_RESOLVED_IP,
                        resolvedIps = allIps,
                        message = "拦截 IP ${ip.hostAddress} (${classifyBlockedIp(ip)})")
                }
            }

            CheckResult(true, resolvedIps = allIps)
        } catch (e: Exception) {
            Timber.w(e, "UrlSafetyChecker 异常，fail-closed 拒绝: $url")
            CheckResult(false, RejectReason.INVALID_URL, message = "解析异常: ${e.message}")
        }
    }

    private val BLOCKED_HOSTNAMES: Set<String> = setOf(
        "metadata.google.internal",
        "metadata",
        "metadata.aws.internal",
        "metadata.azure.com",
        "169.254.169.254",
        "metadata.tencentyun.com"
    )

    private fun isBlockedHostname(host: String): Boolean {
        val lower = host.lowercase().trim()
        return BLOCKED_HOSTNAMES.contains(lower) ||
            lower.endsWith(".internal") ||
            lower.endsWith(".local")
    }

    private fun isBlockedIp(ip: InetAddress): Boolean {
        return ip.isLoopbackAddress ||
            ip.isSiteLocalAddress ||
            ip.isLinkLocalAddress ||
            ip.isMulticastAddress ||
            ip.isAnyLocalAddress ||
            isCgnatAddress(ip)
    }

    private fun isCgnatAddress(ip: InetAddress): Boolean {
        if (ip is Inet4Address) {
            val addr = ip.address
            return (addr[0].toInt() and 0xFF) == 100 &&
                (addr[1].toInt() and 0xFF) in 64..127
        }
        return false
    }

    private fun classifyBlockedIp(ip: InetAddress): String = when {
        ip.isLoopbackAddress -> "loopback"
        ip.isSiteLocalAddress -> "site-local"
        ip.isLinkLocalAddress -> "link-local"
        ip.isMulticastAddress -> "multicast"
        ip.isAnyLocalAddress -> "any-local"
        isCgnatAddress(ip) -> "cgnat"
        else -> "unknown"
    }
}
