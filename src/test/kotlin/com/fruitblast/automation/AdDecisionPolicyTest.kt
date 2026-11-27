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
    check(decision(playing = true) == AdAction.WAIT) { "An active ad without a validated skip control must wait." }
    check(decision(close = true, playing = true) == AdAction.CLOSE) {
        "A validated Close control may be selected while the ad is playing."
    }
    check(decision(next = true, playing = true) == AdAction.NEXT) {
        "A validated Next control may be selected while the ad is playing."
    }
    check(decision(continueAd = true, playing = true) == AdAction.CONTINUE) {
        "A validated Continue control may be selected while the ad is playing."
    }
    check(decision(playing = true, completed = true) == AdAction.STOP) {
        "Completion must retain STOP precedence while the ad-playing flag is set."
    }
    check(decision(playing = true, gameHome = true) == AdAction.STOP) {
        "Game home must retain STOP precedence while the ad-playing flag is set."
    }
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


    fun node(
        className: String = "android.view.View",
        id: String? = null,
        text: String? = null,
        description: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        width: Int = 100,
        height: Int = 100,
        packageName: String = FruitBlastConfig.TARGET_PACKAGE,
    ) = AccessibilityNodeSnapshot(
        packageName, className, id, text, description, visible, enabled,
        clickable, width, height,
    )

    val classifier = AdUiClassifier()
    val gameHomeNodes = listOf(
        node(description = "Game view", width = 1080, height = 2272),
        node(className = "android.widget.ImageView", description = "Piggy Bank", clickable = true),
    )
    check(classifier.classify(gameHomeNodes).state == AdUiState.GAME_HOME_CANDIDATE) {
        "Game Home requires both observed Game view and Piggy Bank anchors."
    }

    val playableNodes = listOf(
        node(className = "android.webkit.WebView", width = 1080, height = 2188),
        node(id = "playable", width = 1080, height = 2188),
        node(id = "stage", width = 1078, height = 2188),
        node(id = "banner", width = 1078, height = 209),
    )
    check(classifier.classify(playableNodes).state == AdUiState.PLAYABLE_AD_CANDIDATE) {
        "WebView, playable and stage markers identify a playable-ad candidate."
    }

    val resultNodes = playableNodes + listOf(
        node(id = "overlay", width = 1078, height = 2188),
        node(className = "android.widget.Button", id = "cta", description = "PLAY NOW", clickable = true),
    )
    val result = classifier.classify(resultNodes)
    check(result.state == AdUiState.PLAYABLE_AD_RESULT_CANDIDATE) {
        "Overlay plus the observed PLAY NOW CTA identifies an ad-result candidate."
    }
    check(!result.continueVisible) {
        "PLAY NOW is an advertising CTA, not a Continue signal."
    }

    val controls = playableNodes + listOf(
        node(className = "android.widget.Button", id = "close_button", description = "Close", clickable = true),
        node(className = "android.widget.Button", id = "next", text = "Next", clickable = true),
        node(className = "android.widget.Button", id = "continue", text = "Continue", clickable = true),
    )
    val controlSignals = classifier.classify(controls)
    check(controlSignals.closeVisible && controlSignals.nextVisible && controlSignals.continueVisible) {
        "Explicit visible, enabled, clickable controls are surfaced independently."
    }

    check(classifier.classify(emptyList()).state == AdUiState.UNKNOWN) {
        "An empty accessibility tree must fail closed."
    }
    check(classifier.classify(gameHomeNodes + playableNodes).state == AdUiState.UNKNOWN) {
        "Conflicting Game Home and playable-ad markers must fail closed."
    }
    check(!classifier.classify(playableNodes + node(description = "Close", clickable = true, visible = false)).closeVisible) {
        "Hidden controls must not be reported."
    }
    check(!classifier.classify(playableNodes + node(description = "Close", clickable = false)).closeVisible) {
        "Non-clickable labels must not be reported as actionable controls."
    }
    check(!classifier.classify(playableNodes + node(id = "close", description = "Close", clickable = true, width = 0)).closeVisible) {
        "Nodes without visible bounds must not be reported."
    }
    check(classifier.classify(playableNodes + node(description = "Close", clickable = true, packageName = "com.android.vending")).state == AdUiState.UNKNOWN) {
        "Mixed-package node trees must fail closed."
    }

    runBoundedNodeTraversalTests()

    runAdUiFixtureTests()

    println("PASS: 21 ad policy/foreground and 11 UI classifier assertions")

}
