package com.fruitblast.automation

/**
 * Single-use correlation token for an external transition following an automation click.
 * Every first external-app event consumes the token, even when the destination is unknown.
 */
class ExternalAppTransitionCorrelator {
    private var pendingClickAtMillis: Long? = null

    fun recordAutomationClick(atMillis: Long) {
        pendingClickAtMillis = atMillis
    }

    fun consumeExternalTransition(
        kind: ExternalAppKind,
        nowMillis: Long,
        redirectsInAdSession: Int,
    ): ExternalAppRecoveryDecision {
        val clickAt = pendingClickAtMillis
            ?: return ExternalAppRecoveryDecision(
                ExternalAppRecoveryAction.IGNORE,
                redirectsInAdSession.coerceAtLeast(0),
            )
        pendingClickAtMillis = null
        return ExternalAppRecoveryPolicy.decide(
            kind = kind,
            automationClickAtMillis = clickAt,
            nowMillis = nowMillis,
            redirectsInAdSession = redirectsInAdSession,
            clickPending = true,
        )
    }

    fun clear() {
        pendingClickAtMillis = null
    }
}
