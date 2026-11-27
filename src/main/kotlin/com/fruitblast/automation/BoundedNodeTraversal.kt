package com.fruitblast.automation

import java.util.ArrayDeque

/** Adapter that keeps platform node ownership behind the traversal boundary. */
interface NodeTraversalAccess<N, S> {
    fun snapshot(node: N): S
    fun snapshot(node: N, path: List<Int>): S = snapshot(node)
    fun childCount(node: N): Int
    fun childAt(node: N, index: Int): N?
    fun recycle(node: N)
}

data class BoundedNodeTraversalResult<S>(
    val snapshots: List<S>,
    val complete: Boolean,
)

/**
 * Synchronous breadth-first traversal with hard discovered-node and elapsed-time budgets.
 * Every acquired node is recycled exactly once on success or best-effort on failure.
 */
object BoundedNodeTraversal {
    fun <N, S> scan(
        root: N?,
        maxNodes: Int,
        maxDepth: Int,
        maxDurationNanos: Long,
        nowNanos: () -> Long,
        access: NodeTraversalAccess<N, S>,
    ): BoundedNodeTraversalResult<S> {
        require(maxNodes > 0)
        require(maxDepth >= 0)
        require(maxDurationNanos > 0)
        if (root == null) return BoundedNodeTraversalResult(emptyList(), complete = true)

        val pending = ArrayDeque<Triple<N, Int, List<Int>>>()
        val snapshots = ArrayList<S>(minOf(maxNodes, 64))
        val startedAt = nowNanos()
        var discoveredNodes = 1
        var complete = true
        pending.addLast(Triple(root, 0, emptyList()))

        fun timeBudgetExceeded(): Boolean = nowNanos() - startedAt >= maxDurationNanos

        try {
            while (pending.isNotEmpty()) {
                if (timeBudgetExceeded()) {
                    complete = false
                    break
                }

                val (node, depth, path) = pending.removeFirst()
                try {
                    if (timeBudgetExceeded()) {
                        complete = false
                        break
                    }
                    snapshots += access.snapshot(node, path)
                    if (timeBudgetExceeded()) {
                        complete = false
                        break
                    }

                    val childCount = access.childCount(node)
                    if (timeBudgetExceeded()) {
                        complete = false
                        break
                    }
                    if (depth >= maxDepth && childCount > 0) {
                        complete = false
                        break
                    }

                    for (index in 0 until childCount) {
                        if (timeBudgetExceeded() || discoveredNodes >= maxNodes) {
                            complete = false
                            break
                        }
                        val child = access.childAt(node, index)
                        if (child != null) {
                            if (timeBudgetExceeded()) {
                                try {
                                    access.recycle(child)
                                } catch (_: RuntimeException) {
                                    // Continue releasing already-owned nodes below.
                                }
                                complete = false
                                break
                            }
                            discoveredNodes++
                            pending.addLast(Triple(child, depth + 1, path + index))
                        }
                    }
                    if (!complete) break
                } catch (_: RuntimeException) {
                    complete = false
                    break
                } finally {
                    try {
                        access.recycle(node)
                    } catch (_: RuntimeException) {
                        complete = false
                    }
                }
                if (!complete) break
            }
        } catch (_: RuntimeException) {
            complete = false
        } finally {
            while (pending.isNotEmpty()) {
                try {
                    access.recycle(pending.removeFirst().first)
                } catch (_: RuntimeException) {
                    complete = false
                }
            }
        }

        return BoundedNodeTraversalResult(
            snapshots = if (complete) snapshots else emptyList(),
            complete = complete,
        )
    }
}
