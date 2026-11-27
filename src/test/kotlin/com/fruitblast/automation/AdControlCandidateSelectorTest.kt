package com.fruitblast.automation

internal fun runAdControlCandidateSelectorTests() {
    val target = FruitBlastConfig.TARGET_PACKAGE
    val classifier = AdUiClassifier()

    fun node(
        path: List<Int>,
        id: String? = null,
        text: String? = null,
        description: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        packageName: String = target,
    ) = AccessibilityNodeSnapshot(
        packageName = packageName,
        className = "android.widget.Button",
        resourceId = id,
        text = text,
        contentDescription = description,
        visibleToUser = visible,
        enabled = enabled,
        clickable = clickable,
        boundsWidth = 100,
        boundsHeight = 48,
        nodePath = path,
    )

    val ad = listOf(
        node(listOf(0), id = "playable", clickable = false),
        node(listOf(1), id = "stage", clickable = false),
        node(listOf(2), id = "web", clickable = false).copy(className = "android.webkit.WebView"),
    )

    fun select(nodes: List<AccessibilityNodeSnapshot>, action: AdAction) =
        AdControlCandidateSelector.select(nodes, action, classifier)

    val close = node(listOf(3, 0), id = "close", description = "Close", clickable = true)
    val unique = select(ad + close, AdAction.CLOSE)
    check(unique == AdControlSelection.Unique(AdControlCandidate(AdAction.CLOSE, listOf(3, 0)))) {
        "Exactly one visible, enabled Close candidate must return its stable path."
    }

    check(select(ad, AdAction.CLOSE) == AdControlSelection.None) {
        "No candidate must not produce a dispatch target."
    }

    val duplicate = node(listOf(4, 0), id = "close_button", description = "Dismiss", clickable = true)
    check(select(ad + close + duplicate, AdAction.CLOSE) == AdControlSelection.Ambiguous) {
        "Multiple matching controls must fail closed."
    }

    check(select(ad + close.copy(visibleToUser = false), AdAction.CLOSE) == AdControlSelection.None) {
        "Hidden controls must not be selected."
    }
    check(select(ad + close.copy(enabled = false), AdAction.CLOSE) == AdControlSelection.None) {
        "Disabled controls must not be selected."
    }
    check(select(ad + close.copy(clickable = false), AdAction.CLOSE) == AdControlSelection.None) {
        "Non-clickable controls must not be selected."
    }
    check(select(ad + close.copy(boundsWidth = 0), AdAction.CLOSE) == AdControlSelection.None) {
        "Controls without positive bounds must not be selected."
    }
    check(select(ad + close.copy(packageName = "com.android.vending"), AdAction.CLOSE) == AdControlSelection.None) {
        "Wrong-package controls must not be selected."
    }

    val playNow = node(listOf(5), id = "cta", text = "PLAY NOW", clickable = true)
    check(select(ad + playNow, AdAction.CONTINUE) == AdControlSelection.None) {
        "PLAY NOW must never be selected as Continue."
    }

    val next = node(listOf(6), id = "next", text = "Next", clickable = true)
    check(select(ad + next, AdAction.NEXT) ==
        AdControlSelection.Unique(AdControlCandidate(AdAction.NEXT, listOf(6)))) {
        "Next selection must preserve its path."
    }

    check(select(ad + close, AdAction.STOP) == AdControlSelection.None) {
        "Non-interaction policy actions must never yield a click target."
    }

    println("PASS: 10 safe candidate selection assertions")
}
