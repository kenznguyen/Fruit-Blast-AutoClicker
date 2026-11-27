package com.fruitblast.automation

/** A candidate path is meaningful only for the exact Accessibility tree it was read from. */
data class AdControlCandidate(
    val action: AdAction,
    val nodePath: List<Int>,
)

sealed interface AdControlSelection {
    data class Unique(val candidate: AdControlCandidate) : AdControlSelection
    data object None : AdControlSelection
    data object Ambiguous : AdControlSelection
}

/** Pure selector for one action in a positively classified ad tree. */
object AdControlCandidateSelector {
    fun select(
        nodes: List<AccessibilityNodeSnapshot>,
        action: AdAction,
        classifier: AdUiClassifier = AdUiClassifier(),
    ): AdControlSelection {
        if (action !in setOf(AdAction.CLOSE, AdAction.NEXT, AdAction.CONTINUE)) {
            return AdControlSelection.None
        }
        if (!classifier.classify(nodes).playableAdVisible) return AdControlSelection.None

        val candidates = nodes.filter { matches(it, action) }
        return when (candidates.size) {
            0 -> AdControlSelection.None
            1 -> AdControlSelection.Unique(AdControlCandidate(action, candidates.single().nodePath))
            else -> AdControlSelection.Ambiguous
        }
    }

    /** Revalidates a reacquired node without trusting the earlier tree snapshot. */
    fun matches(node: AccessibilityNodeSnapshot, action: AdAction): Boolean {
        if (
            action !in setOf(AdAction.CLOSE, AdAction.NEXT, AdAction.CONTINUE) ||
            node.packageName != FruitBlastConfig.TARGET_PACKAGE ||
            !node.visibleToUser || !node.enabled || !node.clickable ||
            node.boundsWidth <= 0 || node.boundsHeight <= 0
        ) return false

        val labels = listOfNotNull(node.text, node.contentDescription)
            .map { it.trim().lowercase() }
        val id = node.resourceId
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.lowercase()

        return when (action) {
            AdAction.CLOSE ->
                labels.any { it in setOf("close", "dismiss", "dismiss ad", "skip", "skip ad") } ||
                    id in setOf("close", "close_button", "dismiss", "skip")
            AdAction.NEXT -> "next" in labels || id == "next"
            AdAction.CONTINUE -> (
                "continue" in labels || "resume" in labels ||
                    id in setOf("continue", "resume")
                ) && "play now" !in labels && id != "cta"
            else -> false
        }
    }
}
