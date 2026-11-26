package com.fruitblast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionOwnerTest {
    private class FakeService : CaptureServicePort<String> {
        var startCalls = 0
        var resizeCalls = 0
        var stopCalls = 0
        var startResult: CaptureCommandResult = CaptureCommandResult.Accepted
        var snapshot: CaptureServiceSnapshot? = null
        var lastStart: CaptureStartRequest<String>? = null

        override fun startSession(request: CaptureStartRequest<String>): CaptureCommandResult {
            startCalls++
            lastStart = request
            return startResult
        }

        override fun resize(
            identity: CaptureSessionIdentity,
            width: Int,
            height: Int,
            densityDpi: Int,
        ): CaptureCommandResult {
            resizeCalls++
            return CaptureCommandResult.Accepted
        }

        override fun stopSession(identity: CaptureSessionIdentity): CaptureCommandResult {
            stopCalls++
            return CaptureCommandResult.Accepted
        }

        override fun getSessionSnapshot(identity: CaptureSessionIdentity): CaptureServiceSnapshot? =
            snapshot?.takeIf { it.identity == identity }
    }

    private fun identity(sessionId: Long = 1L, requestId: String = "request-1") =
        CaptureSessionIdentity(sessionId, requestId)

    private fun activeSnapshot(identity: CaptureSessionIdentity) =
        CaptureServiceSnapshot(identity, CaptureSessionState.CAPTURE_ACTIVE, 1080, 2400, 420)

    @Test
    fun rejectsInvalidAndReusedSessionIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptureSessionIdentity(0L, "request-invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureSessionIdentity(1L, " ")
        }

        val owner = CaptureSessionOwner<String>()
        assertEquals(CaptureOwnerResult.Prepared, owner.beginConsent(identity()))
        assertEquals(CaptureOwnerResult.Duplicate, owner.beginConsent(identity()))
        assertEquals(CaptureOwnerResult.Busy, owner.beginConsent(identity(2L, "request-2")))
    }

    @Test
    fun rejectsRequestIdReuseAcrossDifferentSessionIds() {
        val owner = CaptureSessionOwner<String>()
        assertEquals(CaptureOwnerResult.Prepared, owner.beginConsent(identity()))
        owner.onConsentDenied()
        assertEquals(
            CaptureOwnerResult.Duplicate,
            owner.beginConsent(identity(2L, "request-1")),
        )
    }

    @Test
    fun doesNotRetainConsentWhenBinderIsUnavailable() {
        val owner = CaptureSessionOwner<String>()
        owner.beginConsent(identity())

        assertEquals(
            CaptureOwnerResult.ServiceUnavailable,
            owner.submitConsent(CaptureConsentGrant(1, "one-use-consent")),
        )
        val service = FakeService()
        owner.onServiceConnected(service)

        assertEquals(0, service.startCalls)
        assertEquals(CaptureSessionState.FAILED, owner.snapshot().state)
    }

    @Test
    fun submitsStartOnlyOnceForRepeatedConsentCallback() {
        val owner = CaptureSessionOwner<String>()
        val service = FakeService().apply { snapshot = activeSnapshot(identity()) }
        owner.onServiceConnected(service)
        owner.beginConsent(identity())

        assertEquals(
            CaptureOwnerResult.Started,
            owner.submitConsent(CaptureConsentGrant(1, "consent")),
        )
        assertEquals(
            CaptureOwnerResult.Rejected,
            owner.submitConsent(CaptureConsentGrant(1, "consent")),
        )
        assertEquals(1, service.startCalls)
        assertEquals(identity(), service.lastStart?.identity)
    }

    @Test
    fun sameRequestIdentityIsPassedToServiceForIdempotentStart() {
        val owner = CaptureSessionOwner<String>()
        val service = FakeService().apply { snapshot = activeSnapshot(identity()) }
        owner.onServiceConnected(service)
        owner.beginConsent(identity())
        owner.submitConsent(CaptureConsentGrant(1, "consent"))

        assertEquals(identity(), service.lastStart?.identity)
    }

    @Test
    fun reconnectReconcilesUnknownStartWithoutRetryingConsent() {
        val owner = CaptureSessionOwner<String>()
        val service = FakeService().apply {
            startResult = CaptureCommandResult.Unknown
        }
        owner.onServiceConnected(service)
        owner.beginConsent(identity())

        assertEquals(
            CaptureOwnerResult.Unknown,
            owner.submitConsent(CaptureConsentGrant(1, "one-use-consent")),
        )
        owner.onServiceDisconnected()
        service.snapshot = activeSnapshot(identity())

        assertEquals(CaptureOwnerResult.Reconciled, owner.onServiceConnected(service))
        assertEquals(CaptureSessionState.CAPTURE_ACTIVE, owner.snapshot().state)
        assertFalse(owner.snapshot().reconciliationRequired)
        assertEquals(1, service.startCalls)
    }

    @Test
    fun reconnectWithDifferentSessionFailsClosedAndDoesNotRetryStart() {
        val owner = CaptureSessionOwner<String>()
        val service = FakeService().apply { startResult = CaptureCommandResult.Unknown }
        owner.onServiceConnected(service)
        owner.beginConsent(identity())
        owner.submitConsent(CaptureConsentGrant(1, "one-use-consent"))
        owner.onServiceDisconnected()
        service.snapshot = activeSnapshot(identity(99L, "other-request"))

        assertEquals(CaptureOwnerResult.Rejected, owner.onServiceConnected(service))
        assertEquals(CaptureSessionState.FAILED, owner.snapshot().state)
        assertEquals(1, service.startCalls)
    }

    @Test
    fun disconnectedResizeIsUnknownAndReconciledWithoutReplay() {
        val owner = CaptureSessionOwner<String>()
        val service = FakeService().apply { snapshot = activeSnapshot(identity()) }
        owner.onServiceConnected(service)
        owner.beginConsent(identity())
        owner.submitConsent(CaptureConsentGrant(1, "consent"))
        owner.onServiceDisconnected()

        assertEquals(CaptureOwnerResult.Unknown, owner.resize(720, 1600, 420))
        service.snapshot = activeSnapshot(identity())
        assertEquals(CaptureOwnerResult.Reconciled, owner.onServiceConnected(service))
        assertEquals(0, service.resizeCalls)
    }

    @Test
    fun stopIsIdempotentAfterServiceAcceptedIt() {
        val owner = CaptureSessionOwner<String>()
        val service = FakeService().apply { snapshot = activeSnapshot(identity()) }
        owner.onServiceConnected(service)
        owner.beginConsent(identity())
        owner.submitConsent(CaptureConsentGrant(1, "consent"))

        assertEquals(CaptureOwnerResult.Reconciled, owner.stop())
        assertEquals(CaptureOwnerResult.Duplicate, owner.stop())
        assertEquals(1, service.stopCalls)
    }

    @Test
    fun invalidResizeDimensionsAreRejectedBeforeBinderCall() {
        val owner = CaptureSessionOwner<String>()
        val service = FakeService().apply { snapshot = activeSnapshot(identity()) }
        owner.onServiceConnected(service)
        owner.beginConsent(identity())
        owner.submitConsent(CaptureConsentGrant(1, "consent"))

        assertEquals(CaptureOwnerResult.Rejected, owner.resize(0, 1600, 420))
        assertEquals(0, service.resizeCalls)
    }
}
