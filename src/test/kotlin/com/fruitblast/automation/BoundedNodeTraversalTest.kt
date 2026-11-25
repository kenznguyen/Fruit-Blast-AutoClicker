package com.fruitblast.automation

private data class FakeNode(val name: String, val children: List<FakeNode> = emptyList())

private class FakeNodeAccess(
    private val failSnapshotFor: String? = null,
    private val failChildAtIndex: Int? = null,
) : NodeTraversalAccess<FakeNode, String> {
    val recycled = mutableListOf<String>()
    var childReads = 0

    override fun snapshot(node: FakeNode): String {
        if (node.name == failSnapshotFor) throw IllegalStateException("snapshot failed")
        return node.name
    }

    override fun childCount(node: FakeNode): Int = node.children.size

    override fun childAt(node: FakeNode, index: Int): FakeNode? {
        childReads++
        if (index == failChildAtIndex) throw IllegalStateException("child lookup failed")
        return node.children[index]
    }

    override fun recycle(node: FakeNode) {
        recycled += node.name
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

    println("PASS: 8 bounded traversal/resource ownership assertions")
}
