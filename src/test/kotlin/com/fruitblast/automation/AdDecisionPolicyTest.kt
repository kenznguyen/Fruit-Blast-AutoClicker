package com.fruitblast.automation

/**
 * Executable, dependency-free policy tests.
 *
 * Run with: gradle verifyPolicy
 */
fun main() {
    val policy = AdDecisionPolicy(targetPackage = "com.funnyvideo.cgame.fruitblast")

    fun decision(
        packageName: String? = "com.funnyvideo.cgame.fruitblast",
        close: Boolean = false,
        next: Boolean = false,
        continueAd: Boolean = false,
        playing: Boolean = false,
        completed: Boolean = false,
        gameHome: Boolean = false,
    ) = policy.decide(
        AdObservation(
            foregroundPackage = packageName,
            closeVisible = close,
            nextVisible = next,
            continueVisible = continueAd,
            adPlaying = playing,
            adCompleted = completed,
            gameHomeVisible = gameHome,
        )
    )

    check(decision(close = true) == AdAction.CLOSE) { "Close must be selected when available." }
    check(decision(next = true) == AdAction.NEXT) { "Next must be selected when close is unavailable." }
    check(decision(continueAd = true) == AdAction.CONTINUE) { "Continue must be selected for a paused ad." }
    check(decision(playing = true) == AdAction.WAIT) { "An active ad must not receive an input action." }
    check(decision(completed = true) == AdAction.STOP) { "Completed ad must stop automation." }
    check(decision(gameHome = true) == AdAction.STOP) { "Game home must stop automation." }
    check(decision(packageName = "com.android.vending") == AdAction.RECOVER_WRONG_APP) {
        "A foreground app mismatch must request bounded recovery."
    }
    check(decision(packageName = null) == AdAction.WAIT) { "Unknown foreground package must fail closed." }
    check(decision() == AdAction.WAIT) { "No reliable visual state must fail closed." }

    // Safety precedence: completion and wrong-app detection must prevent accidental ad clicks.
    check(decision(close = true, completed = true) == AdAction.STOP) {
        "Completion must take precedence over visible close controls."
    }
    check(decision(packageName = "com.android.vending", close = true) == AdAction.RECOVER_WRONG_APP) {
        "Wrong-app detection must take precedence over visual controls."
    }


    // Foreground validation must use the package ID confirmed by the supplied ADB log.
    val foregroundValidator = ForegroundAppValidator(FruitBlastConfig.TARGET_PACKAGE)
    check(foregroundValidator.classify("com.funnyvideo.cgame.fruitblast") == ForegroundStatus.TARGET) {
        "Fruit Blast package must be accepted as the target."
    }
    check(foregroundValidator.classify("com.android.vending") == ForegroundStatus.OTHER) {
        "Play Store must be rejected as a non-target foreground app."
    }
    check(foregroundValidator.classify("com.android.chrome") == ForegroundStatus.OTHER) {
        "Browser must be rejected as a non-target foreground app."
    }
    check(foregroundValidator.classify(null) == ForegroundStatus.UNKNOWN) {
        "Missing foreground package must remain unknown."
    }
    check(foregroundValidator.classify("") == ForegroundStatus.UNKNOWN) {
        "Blank foreground package must remain unknown."
    }

    println("PASS: 16 ad policy and foreground validation assertions")
}
