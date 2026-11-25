package com.jarvis.assistant.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

class JarvisAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JarvisAccessibilityService connected (canPerformGestures=true)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg != packageName) {
            currentForegroundPackage = pkg
        }
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Looks past J.A.R.V.I.S.'s own translucent voice overlay window to target the real
     * underlying application window (e.g. Spotify, WhatsApp, Settings, Launcher).
     */
    private fun findTargetApplicationRoot(): AccessibilityNodeInfo? {
        val underlying = runCatching {
            windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { w -> w.root?.let { r -> w.layer to r } }
                .filter { (_, r) -> r.packageName?.toString() != packageName }
                .maxByOrNull { (layer, _) -> layer }
                ?.second
        }.getOrNull()
        if (underlying != null) return underlying

        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() != packageName) {
            return active
        }
        return active
    }

    fun captureScreenSnapshot(): ScreenSnapshot? {
        val root = findTargetApplicationRoot() ?: return null
        val dm = resources.displayMetrics
        val pkg = root.packageName?.toString().orEmpty().ifBlank { currentForegroundPackage }
        val isSensitive = ScreenTreeSerializer.isSensitivePackage(pkg)

        val nodes = mutableListOf<UiNodeSnapshot>()
        val counter = intArrayOf(1)

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || nodes.size >= MAX_NODES) return
            if (!node.isVisibleToUser) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val hasBounds = rect.width() > 2 && rect.height() > 2

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val isPassword = node.isPassword

            val isMeaningful = hasBounds && (
                text.isNotBlank() || desc.isNotBlank() || clickable || editable || scrollable
            )

            if (isMeaningful) {
                @Suppress("DEPRECATION")
                val checkedState = if (node.isCheckable) node.isChecked else null
                nodes.add(
                    UiNodeSnapshot(
                        index = counter[0]++,
                        text = if (isSensitive || isPassword) "" else text,
                        contentDescription = if (isSensitive || isPassword) "" else desc,
                        className = node.className?.toString().orEmpty(),
                        viewId = node.viewIdResourceName.orEmpty(),
                        left = rect.left,
                        top = rect.top,
                        right = rect.right,
                        bottom = rect.bottom,
                        clickable = clickable,
                        editable = editable,
                        scrollable = scrollable,
                        checked = checkedState,
                        focused = node.isFocused,
                        isPassword = isPassword
                    )
                )
            }

            for (i in 0 until node.childCount) {
                if (nodes.size >= MAX_NODES) break
                traverse(node.getChild(i), depth + 1)
            }
        }

        traverse(root, 0)
        val snapshot = ScreenSnapshot(
            packageName = pkg,
            screenWidth = dm.widthPixels,
            screenHeight = dm.heightPixels,
            isSensitiveApp = isSensitive,
            nodes = nodes
        )
        lastSnapshot = snapshot
        Log.i(TAG, "captureScreenSnapshot: pkg=$pkg nodes=${nodes.size} sensitive=$isSensitive")
        return snapshot
    }

    suspend fun tapElement(nodeId: Int?, query: String?): Pair<Boolean, String> {
        val snapshot = (if (nodeId != null) lastSnapshot else null) ?: captureScreenSnapshot()
            ?: return false to "Unable to inspect active screen window."

        val target = when {
            nodeId != null -> snapshot.nodes.firstOrNull { it.index == nodeId }
            !query.isNullOrBlank() -> findBestMatchingNode(snapshot.nodes, query)
            else -> null
        } ?: return false to "Element not found on screen (nodeId=$nodeId, query=$query). Try calling read_screen first."

        // Attempt native accessibility click on matching live node first
        val root = findTargetApplicationRoot()
        if (root != null) {
            val liveMatch = findLiveNodeByBounds(root, target.left, target.top, target.right, target.bottom)
            if (liveMatch != null) {
                var clickableNode: AccessibilityNodeInfo? = liveMatch
                var hops = 0
                while (clickableNode != null && !clickableNode.isClickable && hops < 4) {
                    clickableNode = clickableNode.parent
                    hops++
                }
                if (clickableNode != null && clickableNode.isClickable) {
                    if (clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        val msg = "Tapped #${target.index} (${target.primaryLabel.ifBlank { target.viewId }}) via ACTION_CLICK"
                        Log.i(TAG, msg)
                        return true to msg
                    }
                }
            }
        }

        // Brief yield so any transitioning voice overlay has cleared touchable insets
        delay(120L)

        // Fallback to physical touch gesture at element center
        val tapped = dispatchTap(target.centerX.toFloat(), target.centerY.toFloat())
        val msg = if (tapped) {
            "Tapped #${target.index} at (${target.centerX}, ${target.centerY}) via gesture"
        } else {
            "Failed to dispatch tap gesture on #${target.index}."
        }
        Log.i(TAG, "tapElement result ok=$tapped: $msg")
        return tapped to msg
    }

    suspend fun tapNormalizedCoordinates(normX: Float, normY: Float, longPress: Boolean = false): Pair<Boolean, String> {
        delay(120L)
        val dm = resources.displayMetrics
        val x = (normX.coerceIn(0.01f, 0.99f) * dm.widthPixels)
        val y = (normY.coerceIn(0.01f, 0.99f) * dm.heightPixels)
        val ok = dispatchTap(x, y, durationMs = if (longPress) 700L else 60L)
        val kind = if (longPress) "Long-pressed" else "Tapped"
        val msg = if (ok) "$kind (${x.toInt()}, ${y.toInt()})" else "Gesture dispatch failed."
        Log.i(TAG, "tapNormalizedCoordinates ok=$ok: $msg")
        return ok to msg
    }

    suspend fun typeText(nodeId: Int?, text: String, submit: Boolean): Pair<Boolean, String> {
        val root = findTargetApplicationRoot() ?: return false to "No active window to receive text."
        val snapshot = lastSnapshot ?: captureScreenSnapshot()

        var targetNode: AccessibilityNodeInfo? = null
        if (nodeId != null && snapshot != null) {
            val snap = snapshot.nodes.firstOrNull { it.index == nodeId }
            if (snap != null) {
                targetNode = findLiveNodeByBounds(root, snap.left, snap.top, snap.right, snap.bottom)
            }
        }
        if (targetNode == null) {
            targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }
        if (targetNode == null) {
            targetNode = findFirstEditableNode(root)
        }
        if (targetNode == null) {
            return false to "No editable input field found on screen. Tap the text field first or call read_screen."
        }

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!setOk) {
            return false to "Target element rejected ACTION_SET_TEXT."
        }
        if (submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return true to "Entered text (${text.length} chars)${if (submit) " and submitted" else ""}."
    }

    suspend fun scrollScreen(direction: String): Pair<Boolean, String> {
        delay(120L)
        val dir = direction.trim().lowercase()
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        val (x1, y1, x2, y2) = when (dir) {
            "down", "next" -> arrayOf(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.25f)
            "up", "previous" -> arrayOf(w * 0.5f, h * 0.28f, w * 0.5f, h * 0.75f)
            "right" -> arrayOf(w * 0.80f, h * 0.5f, w * 0.20f, h * 0.5f)
            "left" -> arrayOf(w * 0.20f, h * 0.5f, w * 0.80f, h * 0.5f)
            else -> return false to "Invalid scroll direction '$direction'. Use up, down, left, or right."
        }
        val ok = dispatchSwipe(x1, y1, x2, y2, durationMs = 280L)
        Log.i(TAG, "scrollScreen dir=$dir ok=$ok")
        return ok to if (ok) "Scrolled $dir." else "Failed to dispatch scroll gesture."
    }

    fun performGlobalActionByName(action: String): Pair<Boolean, String> {
        val normalized = action.trim().lowercase().replace(" ", "_")
        val code = when (normalized) {
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents", "overview" -> GLOBAL_ACTION_RECENTS
            "notifications", "notification_shade" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "control_center" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock", "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot", "take_screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            "dismiss_notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            } else GLOBAL_ACTION_BACK
            else -> return false to "Unknown global action '$action'."
        }
        val ok = performGlobalAction(code)
        Log.i(TAG, "performGlobalAction action=$normalized ok=$ok")
        return ok to if (ok) "Executed system action: $normalized" else "System rejected global action: $normalized"
    }

    private suspend fun dispatchTap(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGestureAsync(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGestureAsync(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    deferred.complete(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    deferred.complete(false)
                }
            },
            mainHandler
        )
        if (!dispatched) {
            Log.w(TAG, "dispatchGesture returned false immediately")
            return false
        }
        return withTimeoutOrNull(2_500L) { deferred.await() } ?: false
    }

    private fun findBestMatchingNode(nodes: List<UiNodeSnapshot>, query: String): UiNodeSnapshot? {
        val q = query.trim().lowercase()
        return nodes.firstOrNull { it.text.equals(q, ignoreCase = true) || it.contentDescription.equals(q, ignoreCase = true) }
            ?: nodes.firstOrNull { (it.text.contains(q, ignoreCase = true) || it.contentDescription.contains(q, ignoreCase = true)) && it.clickable }
            ?: nodes.firstOrNull { it.text.contains(q, ignoreCase = true) || it.contentDescription.contains(q, ignoreCase = true) || it.viewId.contains(q, ignoreCase = true) }
    }

    private fun findLiveNodeByBounds(
        root: AccessibilityNodeInfo,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val rect = Rect()
        root.getBoundsInScreen(rect)
        if (rect.left == left && rect.top == top && rect.right == right && rect.bottom == bottom) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findLiveNodeByBounds(child, left, top, right, bottom, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (root.isEditable && root.isVisibleToUser) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFirstEditableNode(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    companion object {
        private const val TAG = "JarvisPilot"
        private const val MAX_DEPTH = 18
        private const val MAX_NODES = 120

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        @Volatile
        var currentForegroundPackage: String = ""
            private set

        @Volatile
        var lastSnapshot: ScreenSnapshot? = null
            private set
    }
}
