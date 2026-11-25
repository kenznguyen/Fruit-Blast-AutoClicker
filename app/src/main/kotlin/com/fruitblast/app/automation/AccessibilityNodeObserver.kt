package com.fruitblast.app.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.fruitblast.automation.AccessibilityNodeSnapshot
import com.fruitblast.automation.AdUiClassifier
import com.fruitblast.automation.AdUiObservation
import java.util.ArrayDeque

/**
 * Bounded synchronous read-only scan. The supplied root and child nodes are
 * recycled after their primitive properties are copied; no node escapes.
 */
internal class AccessibilityNodeObserver(
    private val maxNodes: Int = 256,
    private val maxDepth: Int = 32,
) {
    private val classifier = AdUiClassifier()

    init {
        require(maxNodes > 0)
        require(maxDepth >= 0)
    }

    fun observeAndRelease(root: AccessibilityNodeInfo?): AdUiObservation {
        if (root == null) return AdUiObservation()

        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val snapshots = ArrayList<AccessibilityNodeSnapshot>()
        pending.addLast(root to 0)
        var failed = false
        var truncated = false

        try {
            while (pending.isNotEmpty()) {
                if (snapshots.size >= maxNodes) {
                    truncated = true
                    break
                }

                val (node, depth) = pending.removeFirst()
                try {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    snapshots += AccessibilityNodeSnapshot(
                        packageName = node.packageName?.toString(),
                        className = node.className?.toString(),
                        resourceId = node.viewIdResourceName,
                        text = node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        visibleToUser = node.isVisibleToUser,
                        enabled = node.isEnabled,
                        clickable = node.isClickable,
                        boundsWidth = bounds.width(),
                        boundsHeight = bounds.height(),
                    )

                    val childCount = node.childCount
                    if (depth >= maxDepth && childCount > 0) {
                        truncated = true
                    } else {
                        for (index in 0 until childCount) {
                            node.getChild(index)?.let { child ->
                                pending.addLast(child to (depth + 1))
                            }
                        }
                    }
                } catch (_: RuntimeException) {
                    failed = true
                    break
                } finally {
                    node.recycle()
                }
            }
        } catch (_: RuntimeException) {
            failed = true
        } finally {
            while (pending.isNotEmpty()) {
                try {
                    pending.removeFirst().first.recycle()
                } catch (_: RuntimeException) {
                    // Best-effort release after an incomplete scan.
                }
            }
        }

        if (failed || truncated) return AdUiObservation()
        return classifier.classify(snapshots)
    }
}
