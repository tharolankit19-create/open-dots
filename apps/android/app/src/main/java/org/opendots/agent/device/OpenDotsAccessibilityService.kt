package org.opendots.agent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class OpenDotsAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var active: OpenDotsAccessibilityService? = null
        @Volatile private var lastForegroundPackage: String? = null

        fun isEnabled(): Boolean = active != null
        fun foregroundPackage(): String? = lastForegroundPackage
        fun instance(): OpenDotsAccessibilityService? = active
    }

    override fun onServiceConnected() {
        active = this
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { lastForegroundPackage = it }
    }

    override fun onInterrupt() = Unit

    fun screenSnapshot(maxNodes: Int = 200): JSONObject {
        val root = rootInActiveWindow
        val nodes = JSONArray()
        if (root != null) collect(root, nodes, maxNodes)
        return JSONObject()
            .put("foreground_app", lastForegroundPackage ?: JSONObject.NULL)
            .put("nodes", nodes)
    }

    private fun collect(node: AccessibilityNodeInfo, out: JSONArray, max: Int) {
        if (out.length() >= max) return
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        out.put(
            JSONObject()
                .put("class", node.className?.toString())
                .put("text", node.text?.toString())
                .put("content_description", node.contentDescription?.toString())
                .put("view_id", node.viewIdResourceName)
                .put("clickable", node.isClickable)
                .put("editable", node.isEditable)
                .put("enabled", node.isEnabled)
                .put("scrollable", node.isScrollable)
                .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
        )
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collect(child, out, max)
                child.recycle()
            }
            if (out.length() >= max) break
        }
    }

    fun tap(x: Float, y: Float, done: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
        }, null)
    }

    fun typeIntoFocused(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollForward(): Boolean {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                val found = find(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        return find(rootInActiveWindow)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
