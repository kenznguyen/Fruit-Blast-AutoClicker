package com.fruitblast.app.automation

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.fruitblast.automation.ForegroundAppValidator
import com.fruitblast.automation.ForegroundStatus
import com.fruitblast.automation.FruitBlastConfig

/**
 * Minimal Android adapter for foreground-window observation.
 *
 * Window events are retained for diagnostics only. The safety gate always reads
 * rootInActiveWindow at the point of use and never falls back to a potentially
 * stale event package. No gestures, recovery actions, or capture are performed here.
 */
class FruitBlastAccessibilityService : AccessibilityService() {
    private val foregroundValidator = ForegroundAppValidator()

    @Volatile
    private var lastWindowEventPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val packageName = event.packageName?.toString()
                lastWindowEventPackage = packageName

                if (packageName == FruitBlastConfig.TARGET_PACKAGE) {
                    Log.i(TAG, "eventType=${event.eventType}, targetPackage=$packageName")
                }
            }
        }
    }

    /**
     * Returns true only when Android currently exposes an active root window
     * whose package exactly matches FruitBlastConfig.TARGET_PACKAGE.
     * A missing active window is UNKNOWN and therefore denied.
     *
     * Call immediately before any future input dispatch. This check is not an
     * atomic lock against the operating system changing foreground windows.
     */
    fun isTargetForegroundNow(): Boolean {
        val activePackage = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (_: RuntimeException) {
            null
        }
        return foregroundValidator.classify(activePackage) == ForegroundStatus.TARGET
    }

    /** Last package reported by a configured window event; diagnostic only. */
    fun lastObservedWindowEventPackage(): String? = lastWindowEventPackage

    override fun onInterrupt() {
        lastWindowEventPackage = null
    }

    override fun onDestroy() {
        lastWindowEventPackage = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FruitBlastA11y"
    }
}
