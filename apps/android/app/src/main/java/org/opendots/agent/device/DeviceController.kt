package org.opendots.agent.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.delay

data class AppTarget(val label: String, val packageName: String)
data class DeviceActionResult(
    val success: Boolean,
    val message: String,
    val verifiedForeground: Boolean = false
)

class DeviceController(private val context: Context) {
    @Suppress("DEPRECATION")
    fun launchableApps(): List<AppTarget> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                AppTarget(
                    label = it.loadLabel(context.packageManager).toString(),
                    packageName = it.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun resolveApp(query: String): List<AppTarget> {
        val q = query.trim().lowercase()
        val apps = launchableApps()
        val exact = apps.filter {
            it.label.lowercase() == q || it.packageName.lowercase() == q
        }
        if (exact.isNotEmpty()) return exact
        return apps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }.take(8)
    }

    suspend fun openApp(target: AppTarget): DeviceActionResult {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(target.packageName)
            ?: return DeviceActionResult(false, "${target.label} cannot be launched.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            delay(650)
            val observed = OpenDotsAccessibilityService.foregroundPackage()
            val verified = observed == target.packageName
            DeviceActionResult(
                success = true,
                message = if (verified) {
                    "${target.label} opened and foreground state was verified."
                } else {
                    "${target.label} launch intent was sent. Enable Accessibility for foreground verification."
                },
                verifiedForeground = verified
            )
        }.getOrElse {
            DeviceActionResult(false, "Could not open ${target.label}: ${it.javaClass.simpleName}")
        }
    }
}
