package com.fruitblast.app.capture

/**
 * Coordinates one capture intent with the in-process Local Binder service.
 *
 * The host (CaptureEntryActivity) must call this owner from one serialized
 * control context. The owner never retains a consent grant, never retries START
 * after an uncertain result, and never treats Binder disconnection as proof
 * that the service stopped.
 */
class CaptureSessionOwner<T : Any> {
    private var service: CaptureServicePort<T>? = null
    private var identity: CaptureSessionIdentity? = null
    private var state = CaptureSessionState.IDLE
    private var startSubmitted = false
    private var reconciliationRequired = false
    private val usedSessionIds = mutableSetOf<Long>()
    private val usedRequestIds = mutableSetOf<String>()

    fun snapshot(): CaptureOwnerSnapshot = CaptureOwnerSnapshot(
        identity = identity,
        state = state,
        serviceConnected = service != null,
        reconciliationRequired = reconciliationRequired,
    )

    fun onServiceConnected(port: CaptureServicePort<T>): CaptureOwnerResult {
        service = port
        val current = identity
        if (current == null || !startSubmitted) return CaptureOwnerResult.Reconciled

        val remote = port.getSessionSnapshot(current)
        if (remote == null || remote.identity != current) {
            state = CaptureSessionState.FAILED
            reconciliationRequired = false
            return CaptureOwnerResult.Rejected
        }

        state = remote.state
        reconciliationRequired = false
        return CaptureOwnerResult.Reconciled
    }

    fun onServiceDisconnected() {
        service = null
        if (startSubmitted) reconciliationRequired = true
    }

    fun beginConsent(next: CaptureSessionIdentity): CaptureOwnerResult {
        if (state != CaptureSessionState.IDLE &&
            state != CaptureSessionState.STOPPED &&
            state != CaptureSessionState.FAILED
        ) return CaptureOwnerResult.Busy

        if (next.sessionId in usedSessionIds || next.requestId in usedRequestIds) {
            return CaptureOwnerResult.Duplicate
        }

        usedSessionIds += next.sessionId
        usedRequestIds += next.requestId
        identity = next
        state = CaptureSessionState.AWAITING_CONSENT
        startSubmitted = false
        reconciliationRequired = false
        return CaptureOwnerResult.Prepared
    }

    fun onConsentDenied(): CaptureOwnerResult {
        if (state != CaptureSessionState.AWAITING_CONSENT || startSubmitted) {
            return CaptureOwnerResult.Rejected
        }
        state = CaptureSessionState.FAILED
        return CaptureOwnerResult.ConsentDenied
    }

    fun submitConsent(grant: CaptureConsentGrant<T>): CaptureOwnerResult {
        val current = identity ?: return CaptureOwnerResult.Rejected
        if (state != CaptureSessionState.AWAITING_CONSENT) return CaptureOwnerResult.Rejected
        if (startSubmitted) return CaptureOwnerResult.Duplicate

        val connectedService = service
        if (connectedService == null) {
            // Drop the grant. It is one-use and must not be queued for reconnect.
            state = CaptureSessionState.FAILED
            return CaptureOwnerResult.ServiceUnavailable
        }

        // Claim locally before crossing Binder so re-entrant/repeated callbacks cannot issue START twice.
        startSubmitted = true
        return when (val result = connectedService.startSession(CaptureStartRequest(current, grant))) {
            CaptureCommandResult.Accepted,
            CaptureCommandResult.AlreadyApplied -> {
                val remote = connectedService.getSessionSnapshot(current)
                if (remote == null || remote.identity != current) {
                    reconciliationRequired = true
                    CaptureOwnerResult.Unknown
                } else {
                    state = remote.state
                    CaptureOwnerResult.Started
                }
            }
            CaptureCommandResult.Unknown -> {
                reconciliationRequired = true
                CaptureOwnerResult.Unknown
            }
            CaptureCommandResult.Busy,
            CaptureCommandResult.StaleSession,
            CaptureCommandResult.InvalidRequest -> {
                state = CaptureSessionState.FAILED
                CaptureOwnerResult.Rejected
            }
            is CaptureCommandResult.Failed -> {
                state = CaptureSessionState.FAILED
                CaptureOwnerResult.Failed(result.reasonCode)
            }
        }
    }

    fun resize(width: Int, height: Int, densityDpi: Int): CaptureOwnerResult {
        val current = identity ?: return CaptureOwnerResult.Rejected
        if (state != CaptureSessionState.CAPTURE_ACTIVE) return CaptureOwnerResult.Rejected
        if (width <= 0 || height <= 0 || densityDpi <= 0) return CaptureOwnerResult.Rejected
        val connectedService = service ?: run {
            reconciliationRequired = true
            return CaptureOwnerResult.Unknown
        }
        return mapCommandResult(connectedService.resize(current, width, height, densityDpi))
    }

    fun stop(): CaptureOwnerResult {
        val current = identity ?: return CaptureOwnerResult.Rejected
        if (state == CaptureSessionState.STOPPED || state == CaptureSessionState.IDLE) {
            return CaptureOwnerResult.Reconciled
        }
        if (state == CaptureSessionState.STOPPING) return CaptureOwnerResult.Duplicate
        val connectedService = service ?: run {
            reconciliationRequired = true
            return CaptureOwnerResult.Unknown
        }
        return when (val result = connectedService.stopSession(current)) {
            CaptureCommandResult.Accepted,
            CaptureCommandResult.AlreadyApplied -> {
                state = CaptureSessionState.STOPPING
                CaptureOwnerResult.Reconciled
            }
            CaptureCommandResult.Unknown -> {
                reconciliationRequired = true
                CaptureOwnerResult.Unknown
            }
            CaptureCommandResult.Busy,
            CaptureCommandResult.StaleSession,
            CaptureCommandResult.InvalidRequest -> CaptureOwnerResult.Rejected
            is CaptureCommandResult.Failed -> CaptureOwnerResult.Failed(result.reasonCode)
        }
    }

    private fun mapCommandResult(result: CaptureCommandResult): CaptureOwnerResult =
        when (result) {
            CaptureCommandResult.Accepted,
            CaptureCommandResult.AlreadyApplied -> CaptureOwnerResult.Reconciled
            CaptureCommandResult.Unknown -> {
                reconciliationRequired = true
                CaptureOwnerResult.Unknown
            }
            CaptureCommandResult.Busy,
            CaptureCommandResult.StaleSession,
            CaptureCommandResult.InvalidRequest -> CaptureOwnerResult.Rejected
            is CaptureCommandResult.Failed -> CaptureOwnerResult.Failed(result.reasonCode)
        }
}
