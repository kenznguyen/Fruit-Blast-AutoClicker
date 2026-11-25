package com.fruitblast.automation

/** Immutable, text-free snapshot of one AccessibilityNodeInfo. */
data class AccessibilityNodeSnapshot(
    val packageName: String?,
    val className: String?,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val visibleToUser: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
    val boundsWidth: Int,
    val boundsHeight: Int,
)

enum class AdUiState {
    GAME_HOME_CANDIDATE,
    PLAYABLE_AD_CANDIDATE,
    PLAYABLE_AD_RESULT_CANDIDATE,
    UNKNOWN,
}

data class AdUiObservation(
    val state: AdUiState = AdUiState.UNKNOWN,
    val playableAdVisible: Boolean = false,
    val closeVisible: Boolean = false,
    val nextVisible: Boolean = false,
    val continueVisible: Boolean = false,
)

/**
 * Classifies positive, visible accessibility evidence only.
 * PLAY NOW is an advertising CTA, never a Continue signal.
 */
class AdUiClassifier(
    private val targetPackage: String = FruitBlastConfig.TARGET_PACKAGE,
) {
    fun classify(nodes: List<AccessibilityNodeSnapshot>): AdUiObservation {
        if (nodes.isEmpty() || nodes.first().packageName != targetPackage) {
            return AdUiObservation()
        }
        if (nodes.any { it.packageName != targetPackage }) return AdUiObservation()

        val visible = nodes.filter {
            it.visibleToUser && it.boundsWidth > 0 && it.boundsHeight > 0
        }
        val webView = visible.any { it.className == "android.webkit.WebView" }
        val playable = visible.any { it.resourceId.tailId() == "playable" }
        val stage = visible.any { it.resourceId.tailId() == "stage" }
        val overlay = visible.any { it.resourceId.tailId() == "overlay" }
        val playNowCta = visible.any {
            it.resourceId.tailId() == "cta" &&
                it.className == "android.widget.Button" &&
                it.enabled && it.clickable && it.hasLabel("play now")
        }

        val isPlayableAd = webView && playable && stage
        val isGameHome = visible.any {
            it.className == "android.view.View" &&
                it.contentDescription.normalized() == "game view"
        } && visible.any {
            it.contentDescription.normalized() == "piggy bank" &&
                it.enabled && it.clickable
        }

        if (isPlayableAd && isGameHome) return AdUiObservation()

        val state = when {
            isPlayableAd && overlay && playNowCta -> AdUiState.PLAYABLE_AD_RESULT_CANDIDATE
            isPlayableAd -> AdUiState.PLAYABLE_AD_CANDIDATE
            isGameHome -> AdUiState.GAME_HOME_CANDIDATE
            else -> AdUiState.UNKNOWN
        }
        val isAdState = state == AdUiState.PLAYABLE_AD_CANDIDATE ||
            state == AdUiState.PLAYABLE_AD_RESULT_CANDIDATE
        if (!isAdState) return AdUiObservation(state = state)

        val actionable = visible.filter { it.enabled && it.clickable }
        return AdUiObservation(
            state = state,
            playableAdVisible = true,
            closeVisible = actionable.any {
                it.hasAnyLabel("close", "dismiss", "dismiss ad", "skip", "skip ad") ||
                    it.resourceId.tailId() in setOf("close", "close_button", "dismiss", "skip")
            },
            nextVisible = actionable.any {
                it.hasLabel("next") || it.resourceId.tailId() == "next"
            },
            continueVisible = actionable.any {
                it.hasAnyLabel("continue", "resume") ||
                    it.resourceId.tailId() in setOf("continue", "resume")
            },
        )
    }

    private fun AccessibilityNodeSnapshot.hasLabel(label: String): Boolean =
        text.normalized() == label || contentDescription.normalized() == label

    private fun AccessibilityNodeSnapshot.hasAnyLabel(vararg labels: String): Boolean =
        labels.any { hasLabel(it) }

    private fun String?.normalized(): String? =
        this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun String?.tailId(): String? =
        this?.substringAfterLast('/')?.substringAfterLast(':')?.lowercase()
}
