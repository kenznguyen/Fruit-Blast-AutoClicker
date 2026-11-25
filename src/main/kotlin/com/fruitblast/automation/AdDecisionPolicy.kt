package com.fruitblast.automation

/**
 * One immutable observation from a single captured frame.
 *
 * Visual flags must be produced by a detector that has already validated the frame.
 * A null foreground package means the foreground application is not known.
 */
data class AdObservation(
    val foregroundPackage: String?,
    val closeVisible: Boolean = false,
    val nextVisible: Boolean = false,
    val continueVisible: Boolean = false,
    val adPlaying: Boolean = false,
    val adCompleted: Boolean = false,
    val gameHomeVisible: Boolean = false,
)

enum class AdAction {
    CLOSE,
    NEXT,
    CONTINUE,
    RECOVER_WRONG_APP,
    WAIT,
    STOP,
}

/**
 * Pure fail-closed decision policy. It never performs Android input itself.
 *
 * Safety precedence:
 * 1. Unknown foreground -> WAIT.
 * 2. Wrong foreground app -> RECOVER_WRONG_APP.
 * 3. Completion or game home -> STOP.
 * 4. While an ad is playing -> WAIT (never emit an input action).
 * 5. Close, then Next, then Continue.
 * 6. Otherwise WAIT.
 */
class AdDecisionPolicy(
    private val targetPackage: String = FruitBlastConfig.TARGET_PACKAGE,
) {
    private val foregroundValidator = ForegroundAppValidator(targetPackage)

    fun decide(observation: AdObservation): AdAction {
        when (foregroundValidator.classify(observation.foregroundPackage)) {
            ForegroundStatus.UNKNOWN -> return AdAction.WAIT
            ForegroundStatus.OTHER -> return AdAction.RECOVER_WRONG_APP
            ForegroundStatus.TARGET -> Unit
        }

        if (observation.gameHomeVisible || observation.adCompleted) {
            return AdAction.STOP
        }

        if (observation.adPlaying) {
            return AdAction.WAIT
        }

        return when {
            observation.closeVisible -> AdAction.CLOSE
            observation.nextVisible -> AdAction.NEXT
            observation.continueVisible -> AdAction.CONTINUE
            else -> AdAction.WAIT
        }
    }
}
