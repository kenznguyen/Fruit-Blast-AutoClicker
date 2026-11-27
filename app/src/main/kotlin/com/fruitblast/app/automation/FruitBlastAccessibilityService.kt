package com.fruitblast.app.automation

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.fruitblast.automation.AdAction
import com.fruitblast.automation.AdDecisionPolicy
import com.fruitblast.automation.AdObservation
import com.fruitblast.automation.AdUiState
import com.fruitblast.automation.ForegroundAppValidator
import com.fruitblast.automation.ForegroundStatus
import com.fruitblast.automation.FruitBlastConfig

/**
 * Accessibility adapter for bounded ad observation and policy-gated skip dispatch.
 * Every click is resolved from fresh trees; event package data is diagnostic only.
 */
class FruitBlastAccessibilityService : AccessibilityService() {
    private val foregroundValidator = ForegroundAppValidator()
    private val nodeObserver = AccessibilityNodeObserver()
    private val decisionPolicy = AdDecisionPolicy()
    private val clickDispatcher by lazy {
        AccessibilityNodeClickDispatcher(
            observer = nodeObserver,
            rootProvider = { rootInActiveWindow },
            isTargetForegroundNow = ::isTargetForegroundNow,
        )
    }

    @Volatile
    private var lastWindowEventPackage: String? = null
    private var lastScanAtMillis = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val eventPackage = event.packageName?.toString()
                lastWindowEventPackage = eventPackage
                if (eventPackage != FruitBlastConfig.TARGET_PACKAGE) return

                val now = SystemClock.elapsedRealtime()
                if (now - lastScanAtMillis < MIN_EVENT_SCAN_INTERVAL_MILLIS) return
                lastScanAtMillis = now

                val scan = nodeObserver.scanAndRelease(rootInActiveWindow)
                val observation = scan.observation
                if (observation.state == AdUiState.GAME_HOME_CANDIDATE) {
                    clickDispatcher.resetForConfirmedGameHome()
                }

                val activePackage = activeRootPackageNow()
                val action = decisionPolicy.decide(
                    AdObservation(
                        foregroundPackage = activePackage,
                        closeVisible = observation.closeVisible,
                        nextVisible = observation.nextVisible,
                        continueVisible = observation.continueVisible,
                        adPlaying = observation.playableAdVisible,
                        gameHomeVisible = observation.state == AdUiState.GAME_HOME_CANDIDATE,
                    ),
                )
                val dispatched = when (action) {
                    AdAction.CLOSE, AdAction.NEXT, AdAction.CONTINUE ->
                        clickDispatcher.dispatch(action)
                    else -> false
                }

                Log.i(
                    TAG,
                    "eventType=${event.eventType}, eventPackage=$eventPackage, " +
                        "activePackage=$activePackage, uiState=${observation.state}, " +
                        "action=$action, dispatched=$dispatched, complete=${scan.complete}",
                )
            }
        }
    }

    private fun activeRootPackageNow(): String? {
        val root = try {
            rootInActiveWindow
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return try {
            root.packageName?.toString()
        } catch (_: RuntimeException) {
            null
        } finally {
            try {
                root.recycle()
            } catch (_: RuntimeException) {
                // Best-effort release.
            }
        }
    }

    /** Must be called immediately before ACTION_CLICK; UNKNOWN and OTHER are denied. */
    fun isTargetForegroundNow(): Boolean =
        foregroundValidator.classify(activeRootPackageNow()) == ForegroundStatus.TARGET

    /** Last package reported by a configured window event; diagnostic only. */
    fun lastObservedWindowEventPackage(): String? = lastWindowEventPackage

    override fun onInterrupt() {
        lastWindowEventPackage = null
        lastScanAtMillis = 0L
        clickDispatcher.resetForConfirmedGameHome()
    }

    override fun onDestroy() {
        lastWindowEventPackage = null
        lastScanAtMillis = 0L
        clickDispatcher.resetForConfirmedGameHome()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FruitBlastA11y"
        const val MIN_EVENT_SCAN_INTERVAL_MILLIS = 150L
    }
}
