package com.fruitblast.app.capture

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Durable state of the release obligation.
 *
 * UNKNOWN is deliberately terminal in this ordinary transition contract.
 * Resolving it requires a separately authorized recovery protocol.
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
 * Metadata-only proof issued by an authoritative release verifier.
 *
 * Construction is internal so external callers cannot manufacture proof.
 * This contract does not define or grant a release authority; an authorized
 * implementation must issue this value only after verifying terminal evidence.
 */
sealed interface ReleaseProof {
    val descriptor: OwnershipTransferDescriptor
    val authorityId: String
    val evidenceId: String
}

data class VerifiedReleaseProof internal constructor(
    override val descriptor: OwnershipTransferDescriptor,
    override val authorityId: String,
    override val evidenceId: String,
) : ReleaseProof {
    init {
        require(authorityId.isNotBlank()) { "authorityId must not be blank" }
        require(evidenceId.isNotBlank()) { "evidenceId must not be blank" }
    }
}

/** A requested state transition with optimistic revision precondition. */
data class ReleaseIntentTransition(
    val expectedRevision: Long,
    val nextState: ReleaseObligationState,
    val releaseProof: ReleaseProof? = null,
)

enum class ReleaseIntentTransitionRejection {
    STALE_REVISION,
    INVALID_STATE_TRANSITION,
    RELEASE_PROOF_REQUIRED,
    RELEASE_PROOF_MISMATCH,
    REVISION_OVERFLOW,
}

sealed interface ReleaseIntentTransitionResult {
    data class Applied(val intent: DurableReleaseIntent) : ReleaseIntentTransitionResult
    data class Idempotent(val intent: DurableReleaseIntent) : ReleaseIntentTransitionResult
    data class Rejected(val reason: ReleaseIntentTransitionRejection) : ReleaseIntentTransitionResult
}

/**
 * Immutable, serializable intent metadata for one release obligation.
 *
 * This type intentionally contains no resource reference, native handle,
 * file descriptor, Binder, MediaProjection, VirtualDisplay or ImageReader.
 * Persistence and transaction behavior belong to the SQLite ledger phase.
 */
data class DurableReleaseIntent(
    val descriptor: OwnershipTransferDescriptor,
    val schemaVersion: Int,
    val obligationState: ReleaseObligationState,
    val resourceState: ReleaseResourceState,
    val revision: Long,
    val updatedAtMillis: Long,
    val payloadHash: String,
    val payloadFormatVersion: Int = DurableReleasePayloadHasher.CURRENT_FORMAT_VERSION,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(revision > 0L) { "revision must be positive" }
        require(updatedAtMillis >= 0L) { "updatedAtMillis must not be negative" }
        require(payloadFormatVersion > 0) { "payloadFormatVersion must be positive" }
        require(payloadHash == DurableReleasePayloadHasher.hash(descriptor, payloadFormatVersion)) {
            "payloadHash must match the canonical descriptor payload"
        }

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

    /**
     * Applies a pure contract transition. A durable ledger must additionally
     * perform the expectedRevision compare-and-set atomically with event writes.
     */
    fun transition(change: ReleaseIntentTransition): ReleaseIntentTransitionResult {
        if (change.expectedRevision != revision) {
            return ReleaseIntentTransitionResult.Rejected(
                ReleaseIntentTransitionRejection.STALE_REVISION,
            )
        }
        if (change.nextState == obligationState) {
            return ReleaseIntentTransitionResult.Idempotent(this)
        }
        if (!obligationState.canTransitionTo(change.nextState)) {
            return ReleaseIntentTransitionResult.Rejected(
                ReleaseIntentTransitionRejection.INVALID_STATE_TRANSITION,
            )
        }
        if (change.nextState == ReleaseObligationState.RELEASED) {
            val proof = change.releaseProof
                ?: return ReleaseIntentTransitionResult.Rejected(
                    ReleaseIntentTransitionRejection.RELEASE_PROOF_REQUIRED,
                )
            if (proof.descriptor != descriptor ||
                proof.authorityId.isBlank() ||
                proof.evidenceId.isBlank()
            ) {
                return ReleaseIntentTransitionResult.Rejected(
                    ReleaseIntentTransitionRejection.RELEASE_PROOF_MISMATCH,
                )
            }
        }

        val nextRevision = try {
            Math.addExact(revision, 1L)
        } catch (_: ArithmeticException) {
            return ReleaseIntentTransitionResult.Rejected(
                ReleaseIntentTransitionRejection.REVISION_OVERFLOW,
            )
        }
        val nextResourceState = when (change.nextState) {
            ReleaseObligationState.PREPARED -> ReleaseResourceState.CALLER_OWNED
            ReleaseObligationState.ACCEPTED -> ReleaseResourceState.RUNTIME_OWNED
            ReleaseObligationState.RELEASED -> ReleaseResourceState.RELEASED
            ReleaseObligationState.UNKNOWN -> ReleaseResourceState.UNKNOWN
        }
        return ReleaseIntentTransitionResult.Applied(
            copy(
                obligationState = change.nextState,
                resourceState = nextResourceState,
                revision = nextRevision,
            ),
        )
    }
}

/**
 * Versioned canonical SHA-256 hash for immutable ownership descriptor metadata.
 *
 * Every string is UTF-8 with a signed 32-bit big-endian byte-length prefix.
 * Integer fields use signed 64-bit big-endian encoding. Enum identifiers use
 * their stable names, never ordinal values. Mutable state and the hash itself
 * are deliberately excluded.
 */
object DurableReleasePayloadHasher {
    const val CURRENT_FORMAT_VERSION: Int = 1
    private const val DOMAIN = "FruitBlast.DurableReleaseIntent"

    fun hash(
        descriptor: OwnershipTransferDescriptor,
        formatVersion: Int = CURRENT_FORMAT_VERSION,
    ): String {
        require(formatVersion > 0) { "formatVersion must be positive" }
        val canonical = ByteArrayOutputStream()
        DataOutputStream(canonical).use { output ->
            output.writeInt(formatVersion)
            output.writeCanonicalString(DOMAIN)
            output.writeCanonicalString(descriptor.transferId)
            output.writeLong(descriptor.sessionGeneration)
            output.writeLong(descriptor.resourceId)
            output.writeLong(descriptor.releaseId)
            output.writeCanonicalString(descriptor.reason.name)
            output.writeLong(descriptor.estimatedBytes)
            output.writeLong(descriptor.createdAtMillis)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return "sha256:" + digest.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun DataOutputStream.writeCanonicalString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
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
