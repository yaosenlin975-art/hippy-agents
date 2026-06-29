package com.lin.hippyagent.core.util

import okhttp3.Dns
import java.net.InetAddress

/**
 * 可变 Pinned Dns 实现，防 DNS rebinding。
 *
 * [UrlSafetyChecker.check] 校验通过后，调用 [pin] 固定 host → IPs 映射，
 * OkHttp 连接时直接用固定 IP，不再重新 DNS 解析。
 *
 * 线程安全：[pinned] 用 @Volatile 保证可见性；[pin] 用不可变 Map 替换语义，
 * 适合 WebFetchTool 单工具实例内顺序调用，不跨工具共享。
 */
class PinnedDns : Dns {

    @Volatile
    private var pinned: Map<String, List<InetAddress>> = emptyMap()

    fun pin(host: String, ips: List<InetAddress>) {
        pinned = pinned + (host to ips)
    }

    override fun lookup(hostname: String): List<InetAddress> {
        return pinned[hostname] ?: Dns.SYSTEM.lookup(hostname)
    }
}
