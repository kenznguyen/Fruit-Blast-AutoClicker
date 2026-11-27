package com.fruitblast.app.automation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.fruitblast.automation.AccessibilityNodeSnapshot
import com.fruitblast.automation.AdUiClassifier
import com.fruitblast.automation.AdUiObservation
import com.fruitblast.automation.BoundedNodeTraversal
import com.fruitblast.automation.NodeTraversalAccess

internal data class AdUiScanResult(
    val observation: AdUiObservation,
    val snapshots: List<AccessibilityNodeSnapshot>,
    val complete: Boolean,
)

/**
 * Bounded synchronous read-only scan. Platform nodes remain owned by this adapter and
 * are recycled by the traversal before immutable snapshots reach policy or dispatch.
 */
internal class AccessibilityNodeObserver(
    private val maxNodes: Int = 256,
    private val maxDepth: Int = 32,
    private val maxDurationNanos: Long = DEFAULT_MAX_DURATION_NANOS,
) {
    private val classifier = AdUiClassifier()

    init {
        require(maxNodes > 0)
        require(maxDepth >= 0)
        require(maxDurationNanos > 0)
    }

    fun observeAndRelease(root: AccessibilityNodeInfo?): AdUiObservation =
        scanAndRelease(root).observation

    fun scanAndRelease(root: AccessibilityNodeInfo?): AdUiScanResult {
        val access = object : NodeTraversalAccess<AccessibilityNodeInfo, AccessibilityNodeSnapshot> {
            override fun snapshot(node: AccessibilityNodeInfo): AccessibilityNodeSnapshot {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                return AccessibilityNodeSnapshot(
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
            }

            override fun snapshot(
                node: AccessibilityNodeInfo,
                path: List<Int>,
            ): AccessibilityNodeSnapshot = snapshot(node).copy(nodePath = path)

            override fun childCount(node: AccessibilityNodeInfo): Int = node.childCount

            override fun childAt(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? =
                node.getChild(index)

            override fun recycle(node: AccessibilityNodeInfo) = node.recycle()
        }

        val result = BoundedNodeTraversal.scan(
            root = root,
            maxNodes = maxNodes,
            maxDepth = maxDepth,
            maxDurationNanos = maxDurationNanos,
            nowNanos = System::nanoTime,
            access = access,
        )
        if (!result.complete) {
            return AdUiScanResult(AdUiObservation(), emptyList(), complete = false)
        }
        return AdUiScanResult(
            observation = classifier.classify(result.snapshots),
            snapshots = result.snapshots,
            complete = true,
        )
    }

    private companion object {
        const val DEFAULT_MAX_DURATION_NANOS = 16_000_000L
    }
}
