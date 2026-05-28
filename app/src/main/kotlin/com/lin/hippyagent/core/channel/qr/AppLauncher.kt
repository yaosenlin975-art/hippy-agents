package com.lin.hippyagent.core.channel.qr

import android.content.Context
import android.content.Intent
import android.net.Uri

data class LaunchTarget(
    val channelId: String,
    val displayName: String,
    val scanScheme: String,
    val fallbackScheme: String,
    val packageName: String
)

object AppLauncher {

    private val TARGETS = mapOf(
        "weixin" to LaunchTarget(
            channelId = "weixin",
            displayName = "微信",
            scanScheme = "weixin://dl/scan",
            fallbackScheme = "weixin://",
            packageName = "com.tencent.mm"
        ),
        "feishu" to LaunchTarget(
            channelId = "feishu",
            displayName = "飞书",
            scanScheme = "lark://client/scan",
            fallbackScheme = "lark://",
            packageName = "com.ss.android.lark"
        ),
        "dingtalk" to LaunchTarget(
            channelId = "dingtalk",
            displayName = "钉钉",
            scanScheme = "dingtalk://dingtalkclient/action/scan",
            fallbackScheme = "dingtalk://",
            packageName = "com.alibaba.android.rimet"
        ),
        "wechat" to LaunchTarget(
            channelId = "wechat",
            displayName = "企业微信",
            scanScheme = "wxwork://",
            fallbackScheme = "wxwork://",
            packageName = "com.tencent.wework"
        )
    )

    fun getTarget(channelId: String): LaunchTarget? = TARGETS[channelId]

    fun launchScan(context: Context, channelId: String): Boolean {
        val target = TARGETS[channelId] ?: return false
        val scanIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target.scanScheme))
        if (scanIntent.resolveActivity(context.packageManager) != null) {
            return try {
                context.startActivity(scanIntent)
                true
            } catch (_: Exception) {
                launchFallback(context, target)
            }
        }
        return launchFallback(context, target)
    }

    private fun launchFallback(context: Context, target: LaunchTarget): Boolean {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target.fallbackScheme))
        if (fallbackIntent.resolveActivity(context.packageManager) != null) {
            return try {
                context.startActivity(fallbackIntent)
                true
            } catch (_: Exception) {
                launchByPackageName(context, target)
            }
        }
        return launchByPackageName(context, target)
    }

    private fun launchByPackageName(context: Context, target: LaunchTarget): Boolean {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(target.packageName)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    fun isAppInstalled(context: Context, channelId: String): Boolean {
        val target = TARGETS[channelId] ?: return false
        return try {
            context.packageManager.getPackageInfo(target.packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
