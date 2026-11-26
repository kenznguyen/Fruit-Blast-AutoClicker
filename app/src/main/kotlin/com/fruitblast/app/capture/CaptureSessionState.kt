package com.fruitblast.app.capture

/**
 * Public lifecycle states for one MediaProjection capture session.
 *
 * State changes are serialized by the capture control executor. This type only
 * defines the legal transition graph; it does not own Android resources.
 */
enum class CaptureSessionState {
    IDLE,
    AWAITING_CONSENT,
    PROJECTION_ACTIVE,
    CAPTURE_ACTIVE,
    STOPPING,
    STOPPED,
    FAILED,
}

/** Pure transition policy, kept Android-free so it can be unit tested. */
object CaptureSessionTransitions {
    private val allowed = mapOf(
        CaptureSessionState.IDLE to setOf(CaptureSessionState.AWAITING_CONSENT),
        CaptureSessionState.AWAITING_CONSENT to setOf(
            CaptureSessionState.PROJECTION_ACTIVE,
            CaptureSessionState.STOPPING,
            CaptureSessionState.FAILED,
        ),
        CaptureSessionState.PROJECTION_ACTIVE to setOf(
            CaptureSessionState.CAPTURE_ACTIVE,
            CaptureSessionState.STOPPING,
            CaptureSessionState.FAILED,
        ),
        CaptureSessionState.CAPTURE_ACTIVE to setOf(
            CaptureSessionState.STOPPING,
            CaptureSessionState.FAILED,
        ),
        CaptureSessionState.STOPPING to setOf(
            CaptureSessionState.STOPPED,
            CaptureSessionState.FAILED,
        ),
        CaptureSessionState.STOPPED to setOf(CaptureSessionState.AWAITING_CONSENT),
        CaptureSessionState.FAILED to setOf(CaptureSessionState.AWAITING_CONSENT),
    )

    fun canTransition(
        from: CaptureSessionState,
        to: CaptureSessionState,
    ): Boolean = to in allowed[from].orEmpty()
}
