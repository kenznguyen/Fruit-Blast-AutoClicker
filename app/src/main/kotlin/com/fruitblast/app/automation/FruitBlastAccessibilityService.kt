package com.fruitblast.app.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.fruitblast.automation.AdAction
import com.fruitblast.automation.AdDecisionPolicy
import com.fruitblast.automation.AdObservation
import com.fruitblast.automation.AdUiState
import com.fruitblast.automation.ExternalAppRecoveryAction
import com.fruitblast.automation.AdRedirectCircuitBreaker
import com.fruitblast.automation.ExternalAppTransitionCorrelator
import com.fruitblast.automation.ForegroundAppValidator
import com.fruitblast.automation.ForegroundStatus
import com.fruitblast.automation.FruitBlastConfig

/**
 * Accessibility adapter for bounded ad observation and policy-gated skip dispatch.
 * Every click is resolved from fresh trees; external package events only correlate a pending click.
 */
class FruitBlastAccessibilityService : AccessibilityService() {
    private val foregroundValidator = ForegroundAppValidator()
    private val nodeObserver = AccessibilityNodeObserver()
    private val decisionPolicy = AdDecisionPolicy()
    private val redirectCircuitBreaker = AdRedirectCircuitBreaker()
    private val externalDestinationClassifier by lazy { ExternalDestinationClassifier(packageManager) }
    private val externalTransitionCorrelator = ExternalAppTransitionCorrelator()
    private var awaitingReturnObservation = false
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
                if (eventPackage != FruitBlastConfig.TARGET_PACKAGE) {
                    handleExternalAppTransition(eventPackage)
                    return
                }
                val reobservingReturn = awaitingReturnObservation
                if (reobservingReturn) {
                    Log.i(TAG, "Fruit Blast foreground restored; reobserving fresh Accessibility tree")
                }

                val now = SystemClock.elapsedRealtime()
                if (!reobservingReturn && now - lastScanAtMillis < MIN_EVENT_SCAN_INTERVAL_MILLIS) return
                lastScanAtMillis = now

                val scan = nodeObserver.scanAndRelease(rootInActiveWindow)
                val observation = scan.observation
                val activePackage = activeRootPackageNow()
                if (reobservingReturn && scan.complete && activePackage == FruitBlastConfig.TARGET_PACKAGE) {
                    awaitingReturnObservation = false
                    Log.i(TAG, "Fresh target Accessibility tree reacquired after external-app return")
                }
                if (observation.state == AdUiState.GAME_HOME_CANDIDATE) {
                    clickDispatcher.resetForConfirmedGameHome()
                    redirectCircuitBreaker.onConfirmedGameHome()
                    externalTransitionCorrelator.clear()
                    awaitingReturnObservation = false
                    lastScanAtMillis = 0L
                }

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
                val dispatched = if (!redirectCircuitBreaker.observeOnly) {
                    when (action) {
                        AdAction.CLOSE, AdAction.NEXT, AdAction.CONTINUE -> clickDispatcher.dispatch(action)
                        else -> false
                    }
                } else false
                if (dispatched) externalTransitionCorrelator.recordAutomationClick(SystemClock.elapsedRealtime())

                Log.i(
                    TAG,
                    "eventType=${event.eventType}, eventPackage=$eventPackage, " +
                        "activePackage=$activePackage, uiState=${observation.state}, " +
                        "action=$action, dispatched=$dispatched, observeOnly=${redirectCircuitBreaker.observeOnly}, " +
                        "redirects=${redirectCircuitBreaker.redirectCount}, complete=${scan.complete}",
                )
            }
        }
    }

    private fun handleExternalAppTransition(packageName: String?) {
        val now = SystemClock.elapsedRealtime()
        val kind = externalDestinationClassifier.classify(packageName)
        val decision = externalTransitionCorrelator.consumeExternalTransition(
            kind = kind,
            nowMillis = now,
            redirectsInAdSession = redirectCircuitBreaker.redirectCount,
        )
        if (decision.action == ExternalAppRecoveryAction.IGNORE) return

        // The correlator consumes the click token for every first external transition,
        // including unknown destinations; duplicate events cannot reuse it.
        val observeOnly = redirectCircuitBreaker.recordCorrelatedRedirect()
        clickDispatcher.resetAfterCorrelatedRedirect()
        awaitingReturnObservation = true
        val launchIntent = packageManager.getLaunchIntentForPackage(FruitBlastConfig.TARGET_PACKAGE)
        if (launchIntent == null) {
            awaitingReturnObservation = false
            Log.w(TAG, "Correlated external redirect but Fruit Blast launch intent is unavailable")
            return
        }

        // Let Android resolve the existing task; never force-stop or clear application state.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
            Log.i(TAG, "Recovered correlated ${kind.name} transition; redirects=${redirectCircuitBreaker.redirectCount}, " +
                "observeOnly=$observeOnly; waiting for fresh target-window observation")
        } catch (error: RuntimeException) {
            awaitingReturnObservation = false
            Log.w(TAG, "Unable to return to Fruit Blast after correlated external transition", error)
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
        externalTransitionCorrelator.clear()
        awaitingReturnObservation = false
    }

    override fun onDestroy() {
        lastWindowEventPackage = null
        lastScanAtMillis = 0L
        clickDispatcher.resetForConfirmedGameHome()
        externalTransitionCorrelator.clear()
        awaitingReturnObservation = false
        super.onDestroy()
    }

    private companion object {
        const val TAG = "FruitBlastA11y"
        const val MIN_EVENT_SCAN_INTERVAL_MILLIS = 100L
    }
}
