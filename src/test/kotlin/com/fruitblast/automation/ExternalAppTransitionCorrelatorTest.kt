package com.fruitblast.automation

internal fun runExternalAppTransitionCorrelatorTests() {
    val correlator = ExternalAppTransitionCorrelator()

    correlator.recordAutomationClick(1_000L)
    val unknown = correlator.consumeExternalTransition(
        kind = ExternalAppKind.OTHER,
        nowMillis = 1_500L,
        redirectsInAdSession = 0,
    )
    check(unknown.action == ExternalAppRecoveryAction.IGNORE) {
        "Unknown external destinations must never trigger recovery."
    }
    val laterBrowser = correlator.consumeExternalTransition(
        kind = ExternalAppKind.BROWSER,
        nowMillis = 1_700L,
        redirectsInAdSession = 0,
    )
    check(laterBrowser.action == ExternalAppRecoveryAction.IGNORE) {
        "The first unknown external transition must consume the click correlation token."
    }

    correlator.recordAutomationClick(2_000L)
    val browser = correlator.consumeExternalTransition(
        kind = ExternalAppKind.BROWSER,
        nowMillis = 2_500L,
        redirectsInAdSession = 0,
    )
    check(browser.action == ExternalAppRecoveryAction.RETURN_TO_GAME) {
        "A recent browser transition must correlate to the one pending automation click."
    }
    val duplicate = correlator.consumeExternalTransition(
        kind = ExternalAppKind.PLAY_STORE,
        nowMillis = 2_600L,
        redirectsInAdSession = 1,
    )
    check(duplicate.action == ExternalAppRecoveryAction.IGNORE) {
        "One click token must not correlate to multiple external app transitions."
    }

    correlator.recordAutomationClick(3_000L)
    val expired = correlator.consumeExternalTransition(
        kind = ExternalAppKind.PLAY_STORE,
        nowMillis = 6_001L,
        redirectsInAdSession = 0,
    )
    check(expired.action == ExternalAppRecoveryAction.IGNORE) {
        "An expired click token must not trigger recovery."
    }
    check(correlator.consumeExternalTransition(
        kind = ExternalAppKind.BROWSER,
        nowMillis = 6_100L,
        redirectsInAdSession = 0,
    ).action == ExternalAppRecoveryAction.IGNORE) {
        "An expired token must be consumed and cannot correlate later transitions."
    }

    correlator.recordAutomationClick(7_000L)
    correlator.clear()
    check(correlator.consumeExternalTransition(
        kind = ExternalAppKind.BROWSER,
        nowMillis = 7_100L,
        redirectsInAdSession = 0,
    ).action == ExternalAppRecoveryAction.IGNORE) {
        "Lifecycle invalidation must clear any pending click token."
    }

    println("PASS: 7 single-use external transition correlation assertions")
}
