package com.fruitblast.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionStateTest {
    @Test
    fun permitsTheExpectedConsentAndCaptureLifecycle() {
        assertTrue(CaptureSessionTransitions.canTransition(CaptureSessionState.IDLE, CaptureSessionState.AWAITING_CONSENT))
        assertTrue(CaptureSessionTransitions.canTransition(CaptureSessionState.AWAITING_CONSENT, CaptureSessionState.PROJECTION_ACTIVE))
        assertTrue(CaptureSessionTransitions.canTransition(CaptureSessionState.PROJECTION_ACTIVE, CaptureSessionState.CAPTURE_ACTIVE))
        assertTrue(CaptureSessionTransitions.canTransition(CaptureSessionState.CAPTURE_ACTIVE, CaptureSessionState.STOPPING))
        assertTrue(CaptureSessionTransitions.canTransition(CaptureSessionState.STOPPING, CaptureSessionState.STOPPED))
    }

    @Test
    fun rejectsSkippingConsentAndProjectionSetup() {
        assertFalse(CaptureSessionTransitions.canTransition(CaptureSessionState.IDLE, CaptureSessionState.CAPTURE_ACTIVE))
        assertFalse(CaptureSessionTransitions.canTransition(CaptureSessionState.AWAITING_CONSENT, CaptureSessionState.CAPTURE_ACTIVE))
    }

    @Test
    fun terminalStatesCannotReturnToAnActiveProjection() {
        assertFalse(CaptureSessionTransitions.canTransition(CaptureSessionState.STOPPED, CaptureSessionState.CAPTURE_ACTIVE))
        assertFalse(CaptureSessionTransitions.canTransition(CaptureSessionState.FAILED, CaptureSessionState.PROJECTION_ACTIVE))
    }

    @Test
    fun stoppedOrFailedSessionRequiresFreshConsentForAnotherSession() {
        assertTrue(CaptureSessionTransitions.canTransition(CaptureSessionState.STOPPED, CaptureSessionState.AWAITING_CONSENT))
        assertTrue(CaptureSessionTransitions.canTransition(CaptureSessionState.FAILED, CaptureSessionState.AWAITING_CONSENT))
    }
}
