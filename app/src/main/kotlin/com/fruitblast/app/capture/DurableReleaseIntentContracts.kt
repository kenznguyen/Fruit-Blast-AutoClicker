package com.fruitblast.app.capture

/**
 * Durable state of the release obligation.
 *
 * UNKNOWN is deliberately terminal in this contract: resolution requires a
 * separately authorized recovery decision, never an optimistic retry.
 */
enum class ReleaseObligationState {
    PREPARED,
    ACCEPTED,
    RELEASED,
    UNKNOWN;

    fun canTransitionTo(next: ReleaseObligationState): Boolean = when (this) {
        PREPARED -> next == ACCEPTED || next == RELEASED || next == UNKNOWN
        ACCEPTED -> next == RELEASED || next == UNKNOWN
        RELEASED, UNKNOWN -> false
    }
}

/** Durable custody state of the resource; this is metadata, never a live handle. */
enum class ReleaseResourceState {
    CALLER_OWNED,
    RUNTIME_OWNED,
    RELEASED,
    UNKNOWN,
}

/**
 * Immutable, serializable intent metadata for one release obligation.
 *
 * This type intentionally contains no resource reference, native handle,
 * file descriptor, Binder, MediaProjection, VirtualDisplay or ImageReader.
 * It defines the contract only; persistence and transaction behavior belong
 * to the SQLite ledger implementation phase.
 */
data class DurableReleaseIntent(
    val descriptor: OwnershipTransferDescriptor,
    val schemaVersion: Int,
    val obligationState: ReleaseObligationState,
    val resourceState: ReleaseResourceState,
    val revision: Long,
    val updatedAtMillis: Long,
    val payloadHash: String,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(revision > 0L) { "revision must be positive" }
        require(updatedAtMillis >= 0L) { "updatedAtMillis must not be negative" }
        require(payloadHash.isNotBlank()) { "payloadHash must not be blank" }

        val expectedResourceState = when (obligationState) {
            ReleaseObligationState.PREPARED -> ReleaseResourceState.CALLER_OWNED
            ReleaseObligationState.ACCEPTED -> ReleaseResourceState.RUNTIME_OWNED
            ReleaseObligationState.RELEASED -> ReleaseResourceState.RELEASED
            ReleaseObligationState.UNKNOWN -> ReleaseResourceState.UNKNOWN
        }
        require(resourceState == expectedResourceState) {
            "resourceState must match obligationState"
        }
    }
}

/**
 * Result of a durable ledger commit attempt.
 *
 * UNKNOWN means the commit outcome cannot be proven. It must never be treated
 * as NOT_COMMITTED or used to authorize resource release/reuse.
 */
enum class DurableLedgerCommitResult {
    COMMITTED,
    NOT_COMMITTED,
    UNKNOWN,
    CONTRACT_VIOLATION,
}

/**
 * Lookup result after consulting and recovering the durable store.
 *
 * NotFoundAfterRecovery is stronger than an in-memory miss: the store has
 * completed recovery and proves no commit for the transfer is pending/possible.
 * Unknown preserves ambiguity and requires custody to remain quarantined.
 */
sealed interface DurableReleaseIntentLookup {
    data class Found(val intent: DurableReleaseIntent) : DurableReleaseIntentLookup

    data object NotFoundAfterRecovery : DurableReleaseIntentLookup

    data object Unknown : DurableReleaseIntentLookup
}
