package com.fruitblast.automation

enum class ExternalAppKind {
    PLAY_STORE,
    BROWSER,
    OTHER,
}

enum class ExternalAppRecoveryAction {
    IGNORE,
    RETURN_TO_GAME,
    RETURN_TO_GAME_OBSERVE_ONLY,
}

data class ExternalAppRecoveryDecision(
    val action: ExternalAppRecoveryAction,
    val nextRedirectCount: Int,
)

/**
 * Correlates an external app transition only with a recent, unconsumed automation click.
 * The short window is a heuristic: a simultaneous manual app switch cannot be ruled out.
 */
object ExternalAppRecoveryPolicy {
    const val CORRELATION_WINDOW_MILLIS: Long = 3_000L
    const val MAX_REDIRECTS_PER_AD_SESSION: Int = 2

    fun decide(
        kind: ExternalAppKind,
        automationClickAtMillis: Long?,
        nowMillis: Long,
        redirectsInAdSession: Int,
        clickPending: Boolean,
    ): ExternalAppRecoveryDecision {
        val currentCount = redirectsInAdSession.coerceAtLeast(0)
        val clickAt = automationClickAtMillis
        if (
            !clickPending ||
            kind == ExternalAppKind.OTHER ||
            clickAt == null ||
            nowMillis < clickAt ||
            nowMillis - clickAt > CORRELATION_WINDOW_MILLIS
        ) {
            return ExternalAppRecoveryDecision(ExternalAppRecoveryAction.IGNORE, currentCount)
        }

        val nextCount = currentCount + 1
        val action = if (nextCount >= MAX_REDIRECTS_PER_AD_SESSION) {
            ExternalAppRecoveryAction.RETURN_TO_GAME_OBSERVE_ONLY
        } else {
            ExternalAppRecoveryAction.RETURN_TO_GAME
        }
        return ExternalAppRecoveryDecision(action, nextCount)
    }
}

/** Two correlated redirects open the breaker until Game Home is positively confirmed. */
class AdRedirectCircuitBreaker(
    private val maxRedirects: Int = ExternalAppRecoveryPolicy.MAX_REDIRECTS_PER_AD_SESSION,
) {
    var redirectCount: Int = 0
        private set

    val observeOnly: Boolean
        get() = redirectCount >= maxRedirects

    init {
        require(maxRedirects > 0)
    }

    fun recordCorrelatedRedirect(): Boolean {
        if (redirectCount < maxRedirects) redirectCount++
        return observeOnly
    }

    fun onConfirmedGameHome() {
        redirectCount = 0
    }
}
