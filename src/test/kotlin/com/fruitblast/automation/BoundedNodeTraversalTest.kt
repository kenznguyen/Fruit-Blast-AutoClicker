package com.fruitblast.automation

private data class FakeNode(val name: String, val children: List<FakeNode> = emptyList())

private class FakeNodeAccess(
    private val failSnapshotFor: String? = null,
    private val failChildAtIndex: Int? = null,
    private val onSnapshot: (FakeNode) -> Unit = {},
    private val onChildCount: (FakeNode) -> Unit = {},
    private val onChildAt: (FakeNode, Int) -> Unit = { _, _ -> },
    private val failRecycleFor: Set<String> = emptySet(),
) : NodeTraversalAccess<FakeNode, String> {
    val recycled = mutableListOf<String>()
    var childReads = 0
    var childCountReads = 0

    override fun snapshot(node: FakeNode): String {
        onSnapshot(node)
        if (node.name == failSnapshotFor) throw IllegalStateException("snapshot failed")
        return node.name
    }

    override fun childCount(node: FakeNode): Int {
        childCountReads++
        onChildCount(node)
        return node.children.size
    }

    override fun childAt(node: FakeNode, index: Int): FakeNode? {
        childReads++
        onChildAt(node, index)
        if (index == failChildAtIndex) throw IllegalStateException("child lookup failed")
        return node.children[index]
    }

    override fun recycle(node: FakeNode) {
        recycled += node.name
        if (node.name in failRecycleFor) throw IllegalStateException("recycle failed")
    }
}

internal fun runBoundedNodeTraversalTests() {
    fun scan(
        root: FakeNode?,
        access: FakeNodeAccess,
        maxNodes: Int = 16,
        maxDepth: Int = 8,
        budget: Long = 1_000_000,
        clock: () -> Long = { 0L },
    ) = BoundedNodeTraversal.scan(root, maxNodes, maxDepth, budget, clock, access)

    run {
        val access = FakeNodeAccess()
        val result = scan(FakeNode("root", listOf(FakeNode("leaf"))), access)
        check(result.complete && result.snapshots == listOf("root", "leaf"))
        check(access.recycled.toSet() == setOf("root", "leaf") && access.recycled.size == 2)
    }

    run {
        val children = (0 until 100).map { FakeNode("child-$it") }
        val access = FakeNodeAccess()
        val result = scan(FakeNode("root", children), access, maxNodes = 5)
        check(!result.complete && result.snapshots.isEmpty())
        check(access.childReads == 4) { "Traversal must stop acquiring children at maxNodes." }
        check(access.recycled.size == 5 && access.recycled.toSet().size == 5)
    }

    run {
        val access = FakeNodeAccess()
        val result = scan(FakeNode("root", listOf(FakeNode("child"))), access, maxDepth = 0)
        check(!result.complete && result.snapshots.isEmpty())
        check(access.recycled == listOf("root"))
        check(access.childReads == 0)
    }

    run {
        val access = FakeNodeAccess()
        var ticks = 0L
        val result = scan(
            FakeNode("root", listOf(FakeNode("child"))), access,
            budget = 4,
            clock = { ++ticks },
        )
        check(!result.complete && result.snapshots.isEmpty())
        check(access.recycled.toSet() == setOf("root"))
    }

    run {
        val access = FakeNodeAccess(failChildAtIndex = 1)
        val root = FakeNode("root", listOf(FakeNode("queued"), FakeNode("throws")))
        val result = scan(root, access)
        check(!result.complete && result.snapshots.isEmpty())
        check(access.recycled.toSet() == setOf("root", "queued"))
        check(access.recycled.size == 2)
    }

    run {
        val access = FakeNodeAccess(failSnapshotFor = "bad")
        val root = FakeNode("root", listOf(FakeNode("bad"), FakeNode("pending")))
        val result = scan(root, access)
        check(!result.complete && result.snapshots.isEmpty())
        check(access.recycled.toSet() == setOf("root", "bad", "pending"))
        check(access.recycled.size == 3)
    }

    run {
        val access = FakeNodeAccess()
        val result = scan(FakeNode("leaf"), access, maxNodes = 1, maxDepth = 0)
        check(result.complete && result.snapshots == listOf("leaf"))
        check(access.recycled == listOf("leaf"))
    }

    run {
        val access = FakeNodeAccess()
        val result = scan(null, access)
        check(result.complete && result.snapshots.isEmpty() && access.recycled.isEmpty())
    }

    // F06-B-01: expiry immediately after snapshot prevents child enumeration.
    run {
        var now = 0L
        val access = FakeNodeAccess(onSnapshot = { now = 10L })
        val result = scan(
            FakeNode("root", listOf(FakeNode("child"))), access,
            budget = 10L,
            clock = { now },
        )
        check(!result.complete && result.snapshots.isEmpty())
        check(access.childCountReads == 0)
        check(access.recycled == listOf("root"))
    }

    // F06-B-02: expiry immediately after childCount prevents childAt.
    run {
        var now = 0L
        val access = FakeNodeAccess(onChildCount = { now = 10L })
        val result = scan(
            FakeNode("root", listOf(FakeNode("child"))), access,
            budget = 10L,
            clock = { now },
        )
        check(!result.complete && result.snapshots.isEmpty())
        check(access.childCountReads == 1 && access.childReads == 0)
        check(access.recycled == listOf("root"))
    }

    // F06-B-03: a child acquired as the budget expires is recycled immediately.
    run {
        var now = 0L
        val access = FakeNodeAccess(onChildAt = { _, _ -> now = 10L })
        val result = scan(
            FakeNode("root", listOf(FakeNode("late-child"))), access,
            budget = 10L,
            clock = { now },
        )
        check(!result.complete && result.snapshots.isEmpty())
        check(access.recycled.toSet() == setOf("root", "late-child"))
        check(access.recycled.size == 2)
    }

    // F06-B-04: all nodes already pending are released when the next loop sees expiry.
    run {
        var clockCalls = 0
        val access = FakeNodeAccess()
        val result = scan(
            FakeNode("root", listOf(FakeNode("pending-1"), FakeNode("pending-2"))),
            access,
            budget = 10L,
            clock = {
                clockCalls++
                if (clockCalls >= 10) 10L else 0L
            },
        )
        check(!result.complete && result.snapshots.isEmpty())
        check(access.recycled.toSet() == setOf("root", "pending-1", "pending-2"))
        check(access.recycled.size == 3)
    }

    // F06-B-05: one recycle failure must not prevent best-effort cleanup of later nodes.
    run {
        val access = FakeNodeAccess(
            failSnapshotFor = "bad",
            failRecycleFor = setOf("pending-1"),
        )
        val root = FakeNode(
            "root",
            listOf(FakeNode("bad"), FakeNode("pending-1"), FakeNode("pending-2")),
        )
        val result = scan(root, access)
        check(!result.complete && result.snapshots.isEmpty())
        check(access.recycled.toSet() == setOf("root", "bad", "pending-1", "pending-2"))
        check(access.recycled.size == 4)
    }

    // A clock value below the limit remains within budget; equality and greater-than expire.
    run {
        val belowAccess = FakeNodeAccess()
        val below = scan(FakeNode("leaf"), belowAccess, budget = 10L, clock = { 9L })
        check(below.complete && below.snapshots == listOf("leaf"))

        for (expiredAt in listOf(10L, 11L)) {
            var clockCalls = 0
            val access = FakeNodeAccess()
            val result = scan(
                FakeNode("leaf"),
                access,
                budget = 10L,
                clock = {
                    clockCalls++
                    if (clockCalls == 1) 0L else expiredAt
                },
            )
            check(!result.complete && result.snapshots.isEmpty())
            check(access.recycled == listOf("leaf"))
        }
    }

    println("PASS: 14 bounded traversal/resource ownership assertions")
}
