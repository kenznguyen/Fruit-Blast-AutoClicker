package com.fruitblast.app.capture

/**
 * Stable identity for one user-authorized capture intent.
 *
 * Both values are created once by the initiating UI and must remain unchanged
 * when reconciling an uncertain Binder result. A new consent attempt requires
 * a new identity.
 */
data class CaptureSessionIdentity(
    val sessionId: Long,
    val requestId: String,
) {
    init {
        require(sessionId > 0L) { "sessionId must be positive" }
        require(requestId.isNotBlank()) { "requestId must not be blank" }
    }
}

/** Consent payload is generic so the pure session owner can be tested without Android framework objects. */
data class CaptureConsentGrant<T : Any>(
    val resultCode: Int,
    val data: T,
)

data class CaptureStartRequest<T : Any>(
    val identity: CaptureSessionIdentity,
    val consent: CaptureConsentGrant<T>,
)

sealed interface CaptureCommandResult {
    data object Accepted : CaptureCommandResult
    data object AlreadyApplied : CaptureCommandResult
    data object Busy : CaptureCommandResult
    data object StaleSession : CaptureCommandResult
    data object InvalidRequest : CaptureCommandResult
    /** The command may have reached the service; callers must reconcile, never blindly retry. */
    data object Unknown : CaptureCommandResult
    data class Failed(val reasonCode: String) : CaptureCommandResult
}

data class CaptureServiceSnapshot(
    val identity: CaptureSessionIdentity,
    val state: CaptureSessionState,
    val width: Int? = null,
    val height: Int? = null,
    val densityDpi: Int? = null,
)

/**
 * Local-Binder-facing contract. Implementations must atomically claim requestId:
 * a repeated key with the same session intent returns AlreadyApplied, while a
 * key reused for a different intent returns InvalidRequest.
 */
interface CaptureServicePort<T : Any> {
    fun startSession(request: CaptureStartRequest<T>): CaptureCommandResult
    fun resize(
        identity: CaptureSessionIdentity,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): CaptureCommandResult
    fun stopSession(identity: CaptureSessionIdentity): CaptureCommandResult
    fun getSessionSnapshot(identity: CaptureSessionIdentity): CaptureServiceSnapshot?
}

sealed interface CaptureOwnerResult {
    data object Prepared : CaptureOwnerResult
    data object ConsentDenied : CaptureOwnerResult
    data object Started : CaptureOwnerResult
    data object Duplicate : CaptureOwnerResult
    data object Busy : CaptureOwnerResult
    data object ServiceUnavailable : CaptureOwnerResult
    data object Unknown : CaptureOwnerResult
    data object Reconciled : CaptureOwnerResult
    data object Rejected : CaptureOwnerResult
    data class Failed(val reasonCode: String) : CaptureOwnerResult
}

data class CaptureOwnerSnapshot(
    val identity: CaptureSessionIdentity?,
    val state: CaptureSessionState,
    val serviceConnected: Boolean,
    val reconciliationRequired: Boolean,
)
