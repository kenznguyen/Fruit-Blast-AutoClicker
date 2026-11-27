package com.fruitblast.app.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.fruitblast.automation.AdAction
import com.fruitblast.automation.AdControlCandidate
import com.fruitblast.automation.AdControlCandidateSelector
import com.fruitblast.automation.AdControlSelection
import com.fruitblast.automation.AccessibilityNodeSnapshot
import com.fruitblast.automation.FruitBlastConfig

/**
 * Dispatches only a unique candidate confirmed in two independent, bounded fresh trees.
 * No AccessibilityNodeInfo is retained between scans or events.
 */
internal class AccessibilityNodeClickDispatcher(
    private val observer: AccessibilityNodeObserver,
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val isTargetForegroundNow: () -> Boolean,
) {
    private var lastDispatchedAction: AdAction? = null

    fun resetForConfirmedGameHome() {
        lastDispatchedAction = null
    }

    /** Allows the same ad control to be retried after a correlated external redirect. */
    fun resetAfterCorrelatedRedirect() {
        lastDispatchedAction = null
    }

    fun dispatch(action: AdAction): Boolean {
        if (!isTargetForegroundNow()) return false

        val firstScan = observer.scanAndRelease(rootProvider())
        if (!firstScan.complete) return false
        val first = AdControlCandidateSelector.select(firstScan.snapshots, action)
        val candidate = (first as? AdControlSelection.Unique)?.candidate ?: return false
        if (action == lastDispatchedAction) return false

        val secondScan = observer.scanAndRelease(rootProvider())
        if (!secondScan.complete) return false
        val second = AdControlCandidateSelector.select(secondScan.snapshots, action)
        if (second != first) return false

        var current: AccessibilityNodeInfo? = rootProvider() ?: return false
        try {
            if (current?.packageName?.toString() != FruitBlastConfig.TARGET_PACKAGE) return false

            for (index in candidate.nodePath) {
                val parent = current ?: return false
                val child = parent.getChild(index)
                current = child
                parent.recycle()
                if (child == null) return false
            }

            val target = current ?: return false
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            val snapshot = AccessibilityNodeSnapshot(
                packageName = target.packageName?.toString(),
                className = target.className?.toString(),
                resourceId = target.viewIdResourceName,
                text = target.text?.toString(),
                contentDescription = target.contentDescription?.toString(),
                visibleToUser = target.isVisibleToUser,
                enabled = target.isEnabled,
                clickable = target.isClickable,
                boundsWidth = bounds.width(),
                boundsHeight = bounds.height(),
                nodePath = candidate.nodePath,
            )
            if (!AdControlCandidateSelector.matches(snapshot, action)) return false
            if (!isTargetForegroundNow()) return false

            val dispatched = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (dispatched) lastDispatchedAction = action
            return dispatched
        } catch (_: RuntimeException) {
            return false
        } finally {
            try {
                current?.recycle()
            } catch (_: RuntimeException) {
                // Best-effort release; the action result remains fail-closed.
            }
        }
    }
}
