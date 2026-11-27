package com.fruitblast.automation

internal fun runExternalAppRecoveryPolicyTests() {
    fun decision(
        kind: ExternalAppKind = ExternalAppKind.BROWSER,
        clickAt: Long? = 1_000L,
        now: Long = 2_000L,
        redirects: Int = 0,
        pending: Boolean = true,
    ) = ExternalAppRecoveryPolicy.decide(
        kind = kind,
        automationClickAtMillis = clickAt,
        nowMillis = now,
        redirectsInAdSession = redirects,
        clickPending = pending,
    )

    check(decision().action == ExternalAppRecoveryAction.RETURN_TO_GAME) {
        "A correlated browser transition inside the window must return to Fruit Blast."
    }
    check(decision(kind = ExternalAppKind.PLAY_STORE).action == ExternalAppRecoveryAction.RETURN_TO_GAME) {
        "A correlated Play Store transition must return to Fruit Blast."
    }
    check(decision(pending = false).action == ExternalAppRecoveryAction.IGNORE) {
        "An unrelated manual app switch must not trigger recovery."
    }
    check(decision(kind = ExternalAppKind.OTHER).action == ExternalAppRecoveryAction.IGNORE) {
        "Unknown external apps must not trigger recovery."
    }
    check(decision(now = 4_001L).action == ExternalAppRecoveryAction.IGNORE) {
        "Transitions outside the correlation window must be ignored."
    }
    check(decision(now = 999L).action == ExternalAppRecoveryAction.IGNORE) {
        "A transition preceding the automation click must be ignored."
    }
    check(decision(redirects = 1).action == ExternalAppRecoveryAction.RETURN_TO_GAME_OBSERVE_ONLY) {
        "The second correlated redirect in one ad session must return and pause clicking."
    }
    check(decision(redirects = 1).nextRedirectCount == 2) {
        "The second redirect must advance the per-session counter to two."
    }

    val breaker = AdRedirectCircuitBreaker()
    check(!breaker.recordCorrelatedRedirect()) {
        "The first correlated redirect must not open the circuit breaker."
    }
    check(breaker.recordCorrelatedRedirect()) {
        "The second correlated redirect must open the circuit breaker."
    }
    check(breaker.observeOnly) { "Open circuit breaker must remain observe-only." }
    breaker.onConfirmedGameHome()
    check(breaker.redirectCount == 0 && !breaker.observeOnly) {
        "Only confirmed Game Home may reset the redirect circuit breaker."
    }

    println("PASS: 10 external-app recovery/circuit-breaker assertions")
}
