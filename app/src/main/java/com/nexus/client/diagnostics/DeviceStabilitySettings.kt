package com.nexus.client.diagnostics

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object DeviceStabilitySettings {
    fun ignoresBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBatteryExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return openApplicationDetails(context)
        return openFirst(context,
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            applicationDetailsIntent(context),
        )
    }

    fun openApplicationDetails(context: Context): Boolean = openFirst(context, applicationDetailsIntent(context))

    fun manufacturerGuidance(): String {
        val vendor = Build.MANUFACTURER.orEmpty().lowercase()
        return when {
            vendor.contains("xiaomi") || vendor.contains("redmi") || vendor.contains("poco") ->
                "MIUI/HyperOS: в карточке Ex WG выберите «Экономия энергии» → «Без ограничений» и разрешите автозапуск, если такой пункт есть."
            vendor.contains("huawei") ->
                "EMUI/HarmonyOS: откройте «Батарея» → «Запуск приложений», отключите автоматическое управление для Ex WG и разрешите запуск и работу в фоне."
            vendor.contains("honor") ->
                "MagicOS: откройте «Батарея» → «Запуск приложений» для Ex WG и разрешите автозапуск, косвенный запуск и работу в фоне."
            vendor.contains("oppo") || vendor.contains("realme") || vendor.contains("oneplus") ->
                "ColorOS/realme UI/OxygenOS: в разделе батареи Ex WG разрешите фоновую активность и отключите оптимизацию. Названия пунктов зависят от версии системы."
            vendor.contains("vivo") || vendor.contains("iqoo") ->
                "Funtouch OS/OriginOS: разрешите Ex WG автозапуск и высокий расход энергии в фоне, если система показывает эти пункты."
            vendor.contains("samsung") ->
                "One UI: в разделе «Батарея» приложения Ex WG выберите «Без ограничений» и убедитесь, что приложение не добавлено в список спящих."
            else ->
                "В карточке Ex WG откройте раздел батареи и выберите режим без ограничений. Название пункта зависит от производителя телефона."
        }
    }

    private fun applicationDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    private fun packageUri(context: Context): Uri = Uri.parse("package:${context.packageName}")

    private fun openFirst(context: Context, vararg candidates: Intent): Boolean {
        for (candidate in candidates) {
            val intent = Intent(candidate)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: Throwable) {
                // OEM Settings apps frequently omit standard activities. Try the next public fallback.
            }
        }
        return false
    }
}
