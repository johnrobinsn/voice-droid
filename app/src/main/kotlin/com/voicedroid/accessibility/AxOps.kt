package com.voicedroid.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Accessibility primitives. All operations require [VoiceDroidAccessibilityService.instance]
 * to be non-null (i.e., the service must be enabled in system settings and connected).
 */
object AxOps {

    private const val TAG = "AxOps"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val service: AccessibilityService?
        get() = VoiceDroidAccessibilityService.instance

    fun isReady(): Boolean = service != null

    // ---------------------------------------------------------------- find

    /**
     * DFS the active window roots for a node whose text, content-description, or
     * view-id contains [query] (case-insensitive). Returns the best match or null.
     * Prefers clickable nodes and text/desc matches over viewId-only matches.
     * Searches the active app window first to avoid matching system UI (status bar).
     */
    fun findByText(query: String): AccessibilityNodeInfo? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        val svc = service ?: return null
        // Search active app window first
        svc.rootInActiveWindow?.let { root ->
            val hit = findBest(root, needle)
            if (hit != null) return hit
        }
        // Fall back to other windows (e.g. dialogs, popups)
        for (root in windowRoots()) {
            val hit = findBest(root, needle)
            if (hit != null) return hit
        }
        return null
    }

    private fun findBest(root: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectMatches(root, needle, candidates)
        if (candidates.isEmpty()) return null
        // Prefer: clickable text/desc match > any text/desc match > viewId-only match
        return candidates.sortedWith(compareByDescending<AccessibilityNodeInfo> {
            matchesTextOrDesc(it, needle)
        }.thenByDescending {
            it.isClickable
        }.thenBy {
            val r = Rect(); it.getBoundsInScreen(r); r.width().toLong() * r.height()
        }).first()
    }

    private fun collectMatches(
        node: AccessibilityNodeInfo?,
        needle: String,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node == null) return
        if (matches(node, needle)) out.add(node)
        for (i in 0 until node.childCount) collectMatches(node.getChild(i), needle, out)
    }

    private fun matchesTextOrDesc(node: AccessibilityNodeInfo, needle: String): Boolean {
        val text = node.text?.toString()?.lowercase()
        if (text != null && text.contains(needle)) return true
        val desc = node.contentDescription?.toString()?.lowercase()
        if (desc != null && desc.contains(needle)) return true
        return false
    }

    private fun matches(node: AccessibilityNodeInfo, needle: String): Boolean {
        val text = node.text?.toString()?.lowercase()
        if (text != null && text.contains(needle)) return true
        val desc = node.contentDescription?.toString()?.lowercase()
        if (desc != null && desc.contains(needle)) return true
        val id = node.viewIdResourceName?.lowercase()
        if (id != null && id.contains(needle)) return true
        return false
    }

    private fun dfs(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = dfs(child, predicate)
            if (hit != null) return hit
        }
        return null
    }

    private fun windowRoots(): List<AccessibilityNodeInfo> {
        val svc = service ?: return emptyList()
        val out = ArrayList<AccessibilityNodeInfo>(4)
        svc.windows?.forEach { w -> w.root?.let { out += it } }
        if (out.isEmpty()) svc.rootInActiveWindow?.let { out += it }
        return out
    }

    // ---------------------------------------------------------------- actions

    fun click(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        Log.i(TAG, "click node=${node.className} text=${node.text} desc=${node.contentDescription} id=${node.viewIdResourceName} clickable=${node.isClickable} bounds=$rect")
        if (rect.isEmpty) {
            var cursor: AccessibilityNodeInfo? = node.parent
            while (cursor != null) {
                cursor.getBoundsInScreen(rect)
                if (!rect.isEmpty) break
                cursor = cursor.parent
            }
        }
        if (rect.isEmpty) return false
        return tapXY(rect.exactCenterX(), rect.exactCenterY())
    }

    suspend fun tapText(query: String): Boolean {
        val node = findByText(query) ?: return false
        return try { click(node) } finally { /* node not recycled (deprecated in API 33+) */ }
    }

    fun tapXY(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        val svc = service ?: return false
        Log.i(TAG, "tapXY($x, $y)")
        TapIndicator.show(svc, x, y)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = svc.dispatchGesture(gesture, null, mainHandler)
        Log.i(TAG, "dispatchGesture=$ok")
        return ok
    }

    /**
     * Suspending swipe across the active window. Resolves true on completion or false on
     * cancellation/failure. [direction] is one of up/down/left/right.
     */
    suspend fun scroll(direction: String, distanceFraction: Float = 0.6f, durationMs: Long = 250L): Boolean {
        val svc = service ?: return false
        val bounds = Rect().also { svc.rootInActiveWindow?.getBoundsInScreen(it) }
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val dx = bounds.width() * distanceFraction / 2f
        val dy = bounds.height() * distanceFraction / 2f
        // A "scroll down" gesture is a finger swipe UP (content moves down).
        val (x1, y1, x2, y2) = when (direction.lowercase()) {
            "down" -> floatArrayOf(cx, cy + dy, cx, cy - dy)
            "up" -> floatArrayOf(cx, cy - dy, cx, cy + dy)
            "left" -> floatArrayOf(cx - dx, cy, cx + dx, cy)
            "right" -> floatArrayOf(cx + dx, cy, cx - dx, cy)
            else -> return false
        }.let { d -> SwipeCoords(d[0], d[1], d[2], d[3]) }
            .let { c -> arrayOf(c.x1, c.y1, c.x2, c.y2) }
        return swipe(x1, y1, x2, y2, durationMs)
    }

    private data class SwipeCoords(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 250L): Boolean {
        val svc = service ?: return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return suspendCancellableCoroutine { cont ->
            val ok = svc.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { cont.resume(true) }
                    override fun onCancelled(g: GestureDescription?) { cont.resume(false) }
                },
                mainHandler,
            )
            if (!ok) cont.resume(false)
        }
    }

    /** Set text on the currently focused editable node. Returns false if there is no focused field. */
    fun typeText(text: String): Boolean {
        val focused = findFocusedEditable() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        for (root in windowRoots()) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focused != null && focused.isEditable) return focused
        }
        return null
    }

    // ---------------------------------------------------------------- global actions

    fun back(): Boolean = global(AccessibilityService.GLOBAL_ACTION_BACK)
    fun home(): Boolean = global(AccessibilityService.GLOBAL_ACTION_HOME)
    fun recents(): Boolean = global(AccessibilityService.GLOBAL_ACTION_RECENTS)
    fun notifications(): Boolean = global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)

    private fun global(action: Int): Boolean {
        val svc = service ?: return false
        return svc.performGlobalAction(action)
    }

    // ---------------------------------------------------------------- read

    /** Flat newline-joined visible text from the active window(s). */
    fun readScreen(): String {
        val sb = StringBuilder()
        for (root in windowRoots()) collectText(root, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            sb.append(text).append('\n')
        } else {
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty()) sb.append(desc).append('\n')
        }
        for (i in 0 until node.childCount) collectText(node.getChild(i), sb)
    }

    /** JSON-ish tree dump for debugging. Not a strict JSON encoder — small + readable. */
    fun dumpTree(maxDepth: Int = 20): String {
        val sb = StringBuilder()
        for (root in windowRoots()) {
            sb.append("=== window ===\n")
            dumpNode(root, 0, maxDepth, sb)
        }
        return sb.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, depth: Int, maxDepth: Int, sb: StringBuilder) {
        if (node == null || depth > maxDepth) return
        val indent = "  ".repeat(depth)
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val id = node.viewIdResourceName?.substringAfterLast('/')
        val flags = buildList {
            if (node.isClickable) add("C")
            if (node.isFocusable) add("F")
            if (node.isEditable) add("E")
            if (node.isScrollable) add("S")
        }.joinToString("")
        sb.append(indent).append(cls)
        if (!id.isNullOrEmpty()) sb.append(" #").append(id)
        if (flags.isNotEmpty()) sb.append(" [").append(flags).append("]")
        if (!text.isNullOrEmpty()) sb.append(" \"").append(text.take(60)).append('"')
        if (!desc.isNullOrEmpty() && desc != text) sb.append(" (").append(desc.take(60)).append(')')
        sb.append(" @").append(rect.left).append(',').append(rect.top)
            .append('-').append(rect.right).append(',').append(rect.bottom)
            .append('\n')
        for (i in 0 until node.childCount) dumpNode(node.getChild(i), depth + 1, maxDepth, sb)
    }

    // ---------------------------------------------------------------- screenshot

    /** Screenshot bytes plus the image's pixel dimensions (which equal the screen's, no scaling). */
    data class Screenshot(val jpeg: ByteArray, val width: Int, val height: Int)

    suspend fun takeScreenshot(): Screenshot? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val svc = service ?: return null
        return suspendCancellableCoroutine { cont ->
            val executor = Executor { mainHandler.post(it) }
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hwBitmap = Bitmap.wrapHardwareBuffer(
                            result.hardwareBuffer, result.colorSpace
                        )
                        result.hardwareBuffer.close()
                        if (hwBitmap == null) { cont.resume(null); return }
                        val bitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap.recycle()
                        // No scaling — model needs tap coordinates in screen pixel space.
                        // Quality 90: q70 was smearing 30-40px UI icons (the heart is
                        // exactly that size). Sideband WS carries the larger payload fine.
                        val w = bitmap.width
                        val h = bitmap.height
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        bitmap.recycle()
                        val bytes = out.toByteArray()
                        Log.i(TAG, "screenshot: ${bytes.size} bytes JPEG, ${w}x${h}")
                        cont.resume(Screenshot(bytes, w, h))
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "screenshot failed: errorCode=$errorCode")
                        cont.resume(null)
                    }
                },
            )
        }
    }

    // ---------------------------------------------------------------- misc

    fun activePackage(): String? = service?.rootInActiveWindow?.packageName?.toString()

    fun logEvent(msg: String) = Log.i(TAG, msg)
}
