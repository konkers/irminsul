package com.esc.irminsul

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object PermissionHelper {

    /**
     * @param requiredGranted 必须权限是否全部通过（通知+悬浮通知+VPN）
     * @param recommendedGranted 建议权限是否全部通过（电池优化+自启动）
     */
    data class PermissionState(
        val notificationGranted: Boolean,
        val headsUpEnabled: Boolean,
        val vpnPermissionGranted: Boolean,
        val batteryOptimizationExempt: Boolean,
        val needsAutoStart: Boolean
    ) {
        val allRequiredGranted: Boolean
            get() = notificationGranted && headsUpEnabled && vpnPermissionGranted
        val allRecommendedGranted: Boolean
            get() = batteryOptimizationExempt && !needsAutoStart
        val allGranted: Boolean
            get() = allRequiredGranted && allRecommendedGranted
    }

    fun checkPermissions(context: Context): PermissionState {
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val headsUpEnabled = isHeadsUpEnabled(context)
        val vpnGranted = isVpnPermissionGranted(context)
        val batteryExempt = isBatteryOptimizationExempt(context)
        val needsAutoStart = RomUtils.needsAutoStartGuide() &&
            RomUtils.getAutoStartSettingsIntent(context) != null

        return PermissionState(
            notificationGranted, headsUpEnabled, vpnGranted,
            batteryExempt, needsAutoStart
        )
    }

    /**
     * 检查悬浮通知（Heads-up）是否开启
     * Android 8+: 检查通知渠道 importance 是否 >= HIGH
     * Android 8-: 检查全局 heads_up_notifications_enabled 设置
     */
    fun isHeadsUpEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return true
            val channel = manager.getNotificationChannel(CaptureService.NOTIFICATION_CHANNEL_COMPLETE_ID)
            // 渠道不存在 = 还没创建，创建后默认 HIGH，视为通过
            channel == null || channel.importance >= NotificationManager.IMPORTANCE_HIGH
        } else {
            try {
                Settings.Secure.getInt(context.contentResolver, "heads_up_notifications_enabled", 1) == 1
            } catch (e: Exception) {
                true
            }
        }
    }

    fun isVpnPermissionGranted(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }

    fun isBatteryOptimizationExempt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun getVpnPermissionIntent(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    fun getBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
    }

    fun getNotificationSettingsIntent(context: Context): Intent {
        return RomUtils.getNotificationSettingsIntent(context)
        ?: Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    fun getChannelSettingsIntent(context: Context, channelId: String): Intent {
        return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
    }

    fun getAutoStartSettingsIntent(context: Context): Intent? {
        return RomUtils.getAutoStartSettingsIntent(context)
    }

    /**
     * 应用详情设置页（兜底方案，所有权限都能从这里找到）
     */
    fun getAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
    }

    /**
     * 通知权限设置页（标准 Android，可能被 ROM 重定向）
     */
    fun getAppNotificationSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
}
