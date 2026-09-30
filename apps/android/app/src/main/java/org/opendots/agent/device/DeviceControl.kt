package org.opendots.agent.device

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

data class AppMatch(val label: String, val packageName: String)

data class ScreenNode(
    val id: String?,
    val role: String,
    val text: String,
    val contentDescription: String,
    val clickable: Boolean,
    val editable: Boolean,
    val enabled: Boolean
)

data class ScreenSnapshot(
    val foregroundApp: String?,
    val nodes: List<ScreenNode>
)

class InstalledAppResolver(private val context: Context) {
    fun resolve(query: String): List<AppMatch> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map {
                AppMatch(
                    label = it.loadLabel(context.packageManager).toString(),
                    packageName = it.activityInfo.packageName
                )
            }
            .distinctBy { it.packageName }

        val normalized = query.trim().lowercase()
        return apps
            .filter {
                it.label.lowercase() == normalized ||
                    it.label.lowercase().contains(normalized) ||
                    normalized.contains(it.label.lowercase())
            }
            .sortedWith(
                compareByDescending<AppMatch> { it.label.equals(query, ignoreCase = true) }
                    .thenBy { it.label.length }
            )
    }

    fun launch(match: AppMatch): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(match.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}

object AutomationController {
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    fun begin() {
        cancelled.set(false)
        running.set(true)
    }

    fun stop() {
        cancelled.set(true)
        running.set(false)
    }

    fun finish() {
        running.set(false)
    }

    fun isCancelled(): Boolean = cancelled.get()
    fun isRunning(): Boolean = running.get()
}

class OpenDotsAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        private var current: OpenDotsAccessibilityService? = null

        fun isActive(): Boolean = current != null
        fun foregroundPackage(): String? = current?.lastPackage
        fun screen(): ScreenSnapshot? = current?.snapshot()
    }

    @Volatile
    private var lastPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { lastPackage = it }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun snapshot(): ScreenSnapshot {
        val nodes = mutableListOf<ScreenNode>()
        val root = rootInActiveWindow
        if (root != null) collect(root, nodes, 0)
        return ScreenSnapshot(lastPackage, nodes)
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<ScreenNode>, depth: Int) {
        if (out.size >= 180 || depth > 20) return
        out += ScreenNode(
            id = node.viewIdResourceName,
            role = node.className?.toString().orEmpty(),
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            clickable = node.isClickable,
            editable = node.isEditable,
            enabled = node.isEnabled
        )
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, out, depth + 1) }
        }
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val candidates = root.findAccessibilityNodeInfosByText(text)
        val node = candidates.firstOrNull { it.isVisibleToUser } ?: return false
        var currentNode: AccessibilityNodeInfo? = node
        while (currentNode != null && !currentNode.isClickable) currentNode = currentNode.parent
        return currentNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    fun typeIntoFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}

fun accessibilitySettingsIntent(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
