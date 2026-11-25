package com.fruitblast.automation

/**
 * Executable, dependency-free policy tests.
 *
 * Run with: ./gradlew verifyPolicy
 */
fun main() {
    val policy = AdDecisionPolicy(targetPackage = "com.fruitblast.game")

    fun decision(
        packageName: String? = "com.fruitblast.game",
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

    println("PASS: 11 ad decision policy assertions")
}
