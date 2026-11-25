package com.fruitblast.automation

/**
 * Application identity verified from the Fruit Blast device ADB log.
 */
object FruitBlastConfig {
    const val TARGET_PACKAGE: String = "com.funnyvideo.cgame.fruitblast"
}

enum class ForegroundStatus {
    TARGET,
    OTHER,
    UNKNOWN,
}

/**
 * Classifies the currently observed foreground package.
 *
 * A missing or blank package is UNKNOWN, never TARGET. Callers must fail closed
 * for UNKNOWN and must re-check the package immediately before dispatching input.
 */
class ForegroundAppValidator(
    private val targetPackage: String = FruitBlastConfig.TARGET_PACKAGE,
) {
    init {
        require(targetPackage.isNotBlank()) { "targetPackage must not be blank" }
    }

    fun classify(foregroundPackage: String?): ForegroundStatus {
        if (foregroundPackage.isNullOrBlank()) return ForegroundStatus.UNKNOWN
        return if (foregroundPackage == targetPackage) {
            ForegroundStatus.TARGET
        } else {
            ForegroundStatus.OTHER
        }
    }
}
