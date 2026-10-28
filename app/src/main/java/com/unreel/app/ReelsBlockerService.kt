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

/**
 * Watches Instagram's UI through the accessibility tree and
 *  - covers the Reels tab button with an opaque, touch-swallowing overlay, and
 *  - leaves any Reels viewer as soon as it shows up.
 */
class ReelsBlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private val overlays = mutableListOf<View>()
    private val overlayBounds = mutableListOf<Rect>()

    /** Colour of Instagram's bottom bar, read from the screen; null until sampled. */
    private var sampledColor: Int? = null

    private var scanPending = false
    private var lastLeaveAt = 0L

    private val scan = Runnable {
        scanPending = false
        scanInstagram()
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

    private fun scanInstagram() {
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != INSTAGRAM) {
            removeOverlays()
            return
        }

        if (Prefs.get(this, Prefs.BLOCK_VIEWER) && isReelsOpen(root)) {
            leaveReels(root)
        }

        if (Prefs.get(this, Prefs.HIDE_TAB)) {
            coverReelsTabs(root)
        } else {
            removeOverlays()
        }
    }

    // --- Reels viewer ------------------------------------------------------------------------

    private fun isReelsOpen(root: AccessibilityNodeInfo): Boolean =
        REELS_VIEWER_IDS.any { id -> findVisible(root, id).isNotEmpty() } ||
            findReelsTabs(root).any { it.isSelected }

    private fun leaveReels(root: AccessibilityNodeInfo) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLeaveAt < LEAVE_COOLDOWN_MS) return
        lastLeaveAt = now

        // On the Reels tab itself "back" could close Instagram, so switch to the home feed instead.
        if (findReelsTabs(root).any { it.isSelected }) {
            val home = findVisible(root, HOME_TAB_ID).firstOrNull()
            if (home != null && home.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    // --- Reels tab button --------------------------------------------------------------------

    private fun findReelsTabs(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val byId = findVisible(root, REELS_TAB_ID)
        if (byId.isNotEmpty()) return byId

        // Fallback for builds where the id changed: a node described exactly as "Reels"
        // that sits in the bottom navigation area.
        val bottomArea = resources.displayMetrics.heightPixels * 0.8
        val bounds = Rect()
        return root.findAccessibilityNodeInfosByText("Reels").filter { node ->
            node.getBoundsInScreen(bounds)
            node.isVisibleToUser &&
                node.contentDescription?.toString().equals("Reels", ignoreCase = true) &&
                bounds.top >= bottomArea
        }
    }

    private fun coverReelsTabs(root: AccessibilityNodeInfo) {
        val targets = findReelsTabs(root)
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
                    setBackgroundColor(sampledColor ?: fallbackColor())
                    // Swallow every tap so the Reels tab can't be opened.
                    setOnTouchListener { _, _ -> true }
                }
                windowManager.addView(view, overlayParams(bounds))
                overlays.add(view)
                overlayBounds.add(bounds)
            }
        }

        if (moved && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sampleNavBarColor(targets.first())
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
     * Takes a screenshot and reads the bottom bar's colour just beside the Reels tab (outside our
     * overlay), so the cover matches Instagram exactly in light mode, dark mode or any new theme.
     */
    private fun sampleNavBarColor(tab: Rect) {
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
                    applyColor(bitmap.getPixel(x, y) or 0xFF000000.toInt())
                }
                bitmap.recycle()
            }

            override fun onFailure(errorCode: Int) = Unit
        })
    }

    private fun applyColor(color: Int) {
        sampledColor = color
        overlays.forEach { it.setBackgroundColor(color) }
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

    private fun findVisible(root: AccessibilityNodeInfo, id: String) =
        root.findAccessibilityNodeInfosByViewId(id).filter { it.isVisibleToUser }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Theme may have flipped between light and dark; recreate overlays and sample again.
        sampledColor = null
        removeOverlays()
    }

    companion object {
        private const val INSTAGRAM = "com.instagram.android"
        private const val REELS_TAB_ID = "$INSTAGRAM:id/clips_tab"
        private const val HOME_TAB_ID = "$INSTAGRAM:id/feed_tab"
        private val REELS_VIEWER_IDS = listOf(
            "$INSTAGRAM:id/clips_viewer_view_pager",
            "$INSTAGRAM:id/clips_viewer_container",
        )

        private const val SCAN_DELAY_MS = 100L
        private const val LEAVE_COOLDOWN_MS = 800L
        private const val SAMPLE_OFFSET_PX = 3
        private val DARK_BAR_COLOR = Color.rgb(0x0C, 0x10, 0x14)
    }
}
