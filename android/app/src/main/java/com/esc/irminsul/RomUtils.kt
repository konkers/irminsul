package com.esc.irminsul

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

object RomUtils {
    private const val TAG = "RomUtils"

    enum class RomType {
        MIUI, EMUI, HARMONYOS, COLOROS, ORIGINOS, ONEUI, NATIVE, OTHER
    }

    val romType: RomType by lazy { detectRom() }

    val romDisplayName: String by lazy {
        when (romType) {
            RomType.MIUI -> "MIUI"
            RomType.EMUI -> "EMUI"
            RomType.HARMONYOS -> "HarmonyOS"
            RomType.COLOROS -> "ColorOS"
            RomType.ORIGINOS -> "OriginOS"
            RomType.ONEUI -> "OneUI"
            RomType.NATIVE -> "原生 Android"
            RomType.OTHER -> "其他"
        }
    }

    val isChineseRom: Boolean by lazy {
        romType in listOf(RomType.MIUI, RomType.EMUI, RomType.HARMONYOS, RomType.COLOROS, RomType.ORIGINOS)
    }

    private fun detectRom(): RomType {
        return try {
            when {
                getSystemProperty("ro.miui.ui.version.name") != null -> RomType.MIUI
                getSystemProperty("ro.build.version.emui") != null -> {
                    if (getSystemProperty("ro.build.version.harmonyos") != null) RomType.HARMONYOS
                    else RomType.EMUI
                }
                getSystemProperty("ro.build.version.opporom") != null -> RomType.COLOROS
                getSystemProperty("ro.vivo.product.series") != null -> RomType.ORIGINOS
                getSystemProperty("ro.build.version.samsung.extraversion") != null -> RomType.ONEUI
                else -> RomType.NATIVE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect ROM", e)
            RomType.OTHER
        }
    }

    private fun getSystemProperty(key: String): String? {
        return try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, key) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取自启动管理设置页 Intent（国产 ROM 专有）
     * 返回 null 表示当前 ROM 无需或无法跳转
     */
    fun getAutoStartSettingsIntent(context: Context): Intent? {
        val intents = when (romType) {
            RomType.MIUI -> listOf(
                Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            )
            RomType.EMUI, RomType.HARMONYOS -> listOf(
                Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )
            RomType.COLOROS -> listOf(
                Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                Intent().setClassName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            )
            RomType.ORIGINOS -> listOf(
                Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                Intent().setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            )
            else -> return null
        }

        for (intent in intents) {
            if (isActivityAvailable(context, intent)) return intent
        }
        return null
    }

    /**
     * 获取通知管理设置页 Intent（ROM 专有的通知设置）
     */
    fun getNotificationSettingsIntent(context: Context): Intent? {
        // 优先尝试 ROM 专有的通知设置页
        val romSpecific = when (romType) {
            RomType.MIUI -> listOf(
                Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .setData(Uri.parse("package:${context.packageName}")),
                Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.MainActivity")
                    .setData(Uri.parse("package:${context.packageName}"))
            )
            RomType.EMUI, RomType.HARMONYOS -> listOf(
                Intent().setClassName("com.huawei.systemmanager", "com.huawei.notificationmanager.ui.NotificationSettingsActivity")
                    .setData(Uri.parse("package:${context.packageName}")),
                Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.ActivityActivity")
                    .setData(Uri.parse("package:${context.packageName}"))
            )
            RomType.COLOROS -> listOf(
                Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.notification.NotificationActivity")
                    .setData(Uri.parse("package:${context.packageName}")),
                Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.notification.NotificationActivity")
                    .setData(Uri.parse("package:${context.packageName}"))
            )
            RomType.ORIGINOS -> listOf(
                Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.NotificationManagerActivity")
                    .setData(Uri.parse("package:${context.packageName}")),
                Intent().setClassName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.NotificationManagerActivity")
                    .setData(Uri.parse("package:${context.packageName}"))
            )
            else -> emptyList()
        }

        for (intent in romSpecific) {
            if (isActivityAvailable(context, intent)) return intent
        }

        // 回退到 Android 标准通知设置
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }

    private fun isActivityAvailable(context: Context, intent: Intent): Boolean {
        return try {
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 是否需要引导用户开启自启动
     */
    fun needsAutoStartGuide(): Boolean = isChineseRom && romType != RomType.NATIVE

    /**
     * 获取 ROM 特有的权限设置提示文案
     */
    fun getRomPermissionTips(): String {
        return when (romType) {
            RomType.MIUI -> "MIUI 系统需要额外开启「后台弹出界面」和「自启动」权限，否则通知和 VPN 可能无法正常工作"
            RomType.EMUI -> "EMUI 系统需要额外开启「应用启动管理」和「通知权限」，否则 VPN 可能被系统杀死"
            RomType.HARMONYOS -> "HarmonyOS 需要额外开启「应用启动管理」和「通知权限」，否则 VPN 可能被系统杀死"
            RomType.COLOROS -> "ColorOS 需要额外开启「自启动管理」和「通知权限」，否则 VPN 可能被系统杀死"
            RomType.ORIGINOS -> "OriginOS 需要额外开启「后台弹出界面」和「自启动」权限，否则通知和 VPN 可能无法正常工作"
            else -> ""
        }
    }
}
