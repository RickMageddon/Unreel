package com.unreel.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Watches short-video apps through the accessibility tree:
 *  - Instagram Reels and YouTube Shorts: the tab button is covered with an opaque,
 *    touch-swallowing overlay, and the full-screen viewer is left as soon as it shows up.
 *  - TikTok is all short videos, so it is blocked as a whole by going back to the home screen.
 */
class ReelsBlockerService : AccessibilityService() {

    /** Where an app keeps its short-video feed, and which settings control blocking it. */
    private class ShortsApp(
        val packageName: String,
        /** Text or description of the bottom-bar tab, used when no [tabIds] match. */
        val tabLabel: String,
        val tabIds: List<String>,
        val viewerIds: List<String>,
        /** Tab to switch to when on the shorts tab itself, where "back" might close the app. */
        val homeTabId: String?,
        val hidePref: String,
        val blockPref: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private val overlays = mutableListOf<View>()
    private val overlayBounds = mutableListOf<Rect>()

    /** Colour of each app's bottom bar, read from the screen. */
    private val sampledColors = mutableMapOf<String, Int>()

    private var scanPending = false
    private var lastLeaveAt = 0L

    private val scan = Runnable {
        scanPending = false
        scanActiveWindow()
    }

    override fun onServiceConnected() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() == packageName) return
        // Content-change events arrive in bursts (video playback, scrolling); coalesce them.
        if (!scanPending) {
            scanPending = true
            handler.postDelayed(scan, SCAN_DELAY_MS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(scan)
        removeOverlays()
        super.onDestroy()
    }

    private fun scanActiveWindow() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (pkg in TIKTOK_PACKAGES && Prefs.get(this, Prefs.BLOCK_TIKTOK)) {
            removeOverlays()
            blockTikTok()
            return
        }
        val app = SHORTS_APPS.firstOrNull { it.packageName == pkg }
        if (root == null || app == null) {
            removeOverlays()
            return
        }

        if (Prefs.get(this, app.blockPref) && isViewerOpen(app, root)) {
            leaveViewer(app, root)
        }

        if (Prefs.get(this, app.hidePref)) {
            coverTabs(app, root)
        } else {
            removeOverlays()
        }
    }

    // --- Short-video viewer ------------------------------------------------------------------

    private fun isViewerOpen(app: ShortsApp, root: AccessibilityNodeInfo): Boolean =
        app.viewerIds.any { id -> findVisible(root, id).isNotEmpty() } ||
            findTabs(app, root).any { it.isSelected }

    private fun leaveViewer(app: ShortsApp, root: AccessibilityNodeInfo) {
        if (!cooledDown()) return

        // On the shorts tab itself "back" could close the app, so switch to the home tab instead.
        if (app.homeTabId != null && findTabs(app, root).any { it.isSelected }) {
            val home = findVisible(root, app.homeTabId).firstOrNull()
            if (home != null && home.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // --- TikTok ------------------------------------------------------------------------------

    private fun blockTikTok() {
        if (!cooledDown()) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        Toast.makeText(this, R.string.tiktok_blocked, Toast.LENGTH_SHORT).show()
    }

    // --- Tab button --------------------------------------------------------------------------

    private fun findTabs(app: ShortsApp, root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val byId = app.tabIds.flatMap { findVisible(root, it) }
        if (byId.isNotEmpty()) return byId

        // A node labelled exactly like the tab, sitting in the bottom navigation bar. The label
        // may be on a child of the tab, so cover its clickable parent (but never a large area).
        val screenHeight = resources.displayMetrics.heightPixels
        val bounds = Rect()
        return root.findAccessibilityNodeInfosByText(app.tabLabel)
            .filter { node ->
                node.isVisibleToUser &&
                    (node.text?.toString().equals(app.tabLabel, ignoreCase = true) ||
                        node.contentDescription?.toString().equals(app.tabLabel, ignoreCase = true))
            }
            .map { clickableAncestor(it) }
            .filter { node ->
                node.getBoundsInScreen(bounds)
                bounds.top >= screenHeight * 0.8 && bounds.height() < screenHeight * 0.15
            }
            .distinctBy { node -> Rect().also { node.getBoundsInScreen(it) } }
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        repeat(3) {
            if (current.isClickable) return current
            current = current.parent ?: return node
        }
        return if (current.isClickable) current else node
    }

    private fun coverTabs(app: ShortsApp, root: AccessibilityNodeInfo) {
        val targets = findTabs(app, root)
            .map { node -> Rect().also { node.getBoundsInScreen(it) } }
            .filter { !it.isEmpty }

        while (overlays.size > targets.size) removeOverlayAt(overlays.lastIndex)

        var moved = false
        targets.forEachIndexed { i, bounds ->
            if (i < overlays.size) {
                if (overlayBounds[i] != bounds) {
                    windowManager.updateViewLayout(overlays[i], overlayParams(bounds))
                    overlayBounds[i] = bounds
                    moved = true
                }
            } else {
                moved = true
                val view = View(this).apply {
                    setBackgroundColor(sampledColors[app.packageName] ?: fallbackColor())
                    // Swallow every tap so the tab can't be opened.
                    setOnTouchListener { _, _ -> true }
                }
                windowManager.addView(view, overlayParams(bounds))
                overlays.add(view)
                overlayBounds.add(bounds)
            }
        }

        if (moved && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sampleNavBarColor(app, targets.first())
        }
    }

    private fun overlayParams(bounds: Rect) = WindowManager.LayoutParams(
        bounds.width(),
        bounds.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = bounds.left
        y = bounds.top
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setFitInsetsTypes(0)
        }
    }

    /**
     * Takes a screenshot and reads the bottom bar's colour just beside the tab (outside our
     * overlay), so the cover matches the app exactly in light mode, dark mode or any new theme.
     */
    private fun sampleNavBarColor(app: ShortsApp, tab: Rect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val buffer = screenshot.hardwareBuffer
                val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                if (bitmap == null) return

                val x = if (tab.left >= SAMPLE_OFFSET_PX) tab.left - SAMPLE_OFFSET_PX
                else tab.right + SAMPLE_OFFSET_PX
                val y = tab.centerY()
                if (x in 0 until bitmap.width && y in 0 until bitmap.height) {
                    val color = bitmap.getPixel(x, y) or 0xFF000000.toInt()
                    sampledColors[app.packageName] = color
                    overlays.forEach { it.setBackgroundColor(color) }
                }
                bitmap.recycle()
            }

            override fun onFailure(errorCode: Int) = Unit
        })
    }

    /** Used until a screenshot has been sampled (and always on Android 10 and older). */
    private fun fallbackColor(): Int {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) DARK_BAR_COLOR else Color.WHITE
    }

    private fun removeOverlayAt(index: Int) {
        runCatching { windowManager.removeView(overlays[index]) }
        overlays.removeAt(index)
        overlayBounds.removeAt(index)
    }

    private fun removeOverlays() {
        while (overlays.isNotEmpty()) removeOverlayAt(overlays.lastIndex)
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Rate-limits leaving, so one detection doesn't fire "back" several times in a row. */
    private fun cooledDown(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastLeaveAt < LEAVE_COOLDOWN_MS) return false
        lastLeaveAt = now
        return true
    }

    private fun findVisible(root: AccessibilityNodeInfo, id: String) =
        root.findAccessibilityNodeInfosByViewId(id).filter { it.isVisibleToUser }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Theme may have flipped between light and dark; recreate overlays and sample again.
        sampledColors.clear()
        removeOverlays()
    }

    companion object {
        private const val INSTAGRAM = "com.instagram.android"
        private const val YOUTUBE = "com.google.android.youtube"

        private val SHORTS_APPS = listOf(
            ShortsApp(
                packageName = INSTAGRAM,
                tabLabel = "Reels",
                tabIds = listOf("$INSTAGRAM:id/clips_tab"),
                viewerIds = listOf(
                    "$INSTAGRAM:id/clips_viewer_view_pager",
                    "$INSTAGRAM:id/clips_viewer_container",
                ),
                homeTabId = "$INSTAGRAM:id/feed_tab",
                hidePref = Prefs.HIDE_TAB,
                blockPref = Prefs.BLOCK_VIEWER,
            ),
            ShortsApp(
                packageName = YOUTUBE,
                tabLabel = "Shorts",
                tabIds = emptyList(),
                viewerIds = listOf(
                    "$YOUTUBE:id/reel_recycler",
                    "$YOUTUBE:id/reel_player_page_container",
                    "$YOUTUBE:id/reel_watch_player",
                ),
                // Back from the Shorts tab returns to the previous tab.
                homeTabId = null,
                hidePref = Prefs.BLOCK_SHORTS,
                blockPref = Prefs.BLOCK_SHORTS,
            ),
        )
        private val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically", // TikTok (most countries)
            "com.ss.android.ugc.trill", // TikTok (some Asian markets)
            "com.zhiliaoapp.musically.go", // TikTok Lite
            "com.ss.android.ugc.tiktok.lite", // TikTok Lite (newer builds)
        )

        private const val SCAN_DELAY_MS = 100L
        private const val LEAVE_COOLDOWN_MS = 800L
        private const val SAMPLE_OFFSET_PX = 3
        private val DARK_BAR_COLOR = Color.rgb(0x0C, 0x10, 0x14)
    }
}
