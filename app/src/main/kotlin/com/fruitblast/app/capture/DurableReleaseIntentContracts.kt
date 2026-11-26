package com.fruitblast.app.capture

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Durable state of the release obligation.
 *
 * UNKNOWN is terminal in ordinary transitions. Resolving it requires a separately
 * authorized recovery protocol; a proof cannot bypass this state boundary.
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

/** Durable custody state of the resource; metadata only, never a live handle. */
enum class ReleaseResourceState {
    CALLER_OWNED,
    RUNTIME_OWNED,
    RELEASED,
    UNKNOWN,
}

/**
 * Metadata presented to a release authority for independent verification.
 *
 * This interface is not itself proof of authenticity. The verifier supplied by the
 * trusted composition root must authenticate the authority/evidence pair.
 */
interface ReleaseProof {
    val descriptor: OwnershipTransferDescriptor
    val authorityId: String
    val evidenceId: String
}

/**
 * Opaque proof metadata. Its private constructor prevents ordinary callers from
 * constructing or copying instances through the public API.
 *
 * The internal issuer bridge is not a security boundary: same-module code can call
 * it. The verifier and transition/recovery entry points are module-internal so
 * external Kotlin consumers cannot supply their own verifier. Same-module code
 * remains trusted; this is not a security boundary against hostile module code.
 * The designated capture release-authority adapter is the only approved issuer.
 */
internal class VerifiedReleaseProof private constructor(
    override val descriptor: OwnershipTransferDescriptor,
    override val authorityId: String,
    override val evidenceId: String,
) : ReleaseProof {
    init {
        require(authorityId.isNotBlank()) { "authorityId must not be blank" }
        require(evidenceId.isNotBlank()) { "evidenceId must not be blank" }
    }

    companion object {
        /** Called by the module's release-authority adapter after collecting evidence. */
        internal fun issuedByAuthority(
            descriptor: OwnershipTransferDescriptor,
            authorityId: String,
            evidenceId: String,
        ): VerifiedReleaseProof = VerifiedReleaseProof(descriptor, authorityId, evidenceId)
    }
}

/**
 * Trusted composition-root capability for validating release evidence.
 *
 * The trusted capture release-authority adapter supplies this capability.
 * Implementations must bind authorityId/evidenceId to independently verified,
 * terminal release evidence. A permissive implementation is not an authority.
 * This internal contract does not authenticate same-module implementations.
 */
internal fun interface ReleaseProofVerifier {
    fun verify(proof: ReleaseProof, descriptor: OwnershipTransferDescriptor): Boolean
}

/** A requested state transition with an optimistic revision precondition. */
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
 * Immutable, metadata-only intent for one release obligation.
 *
 * Construction is controlled by createPrepared(), restore(), and transition().
 * There is deliberately no public constructor or data-class copy() method.
 * Persistence and atomic transaction semantics remain the SQLite ledger's concern.
 */
class DurableReleaseIntent private constructor(
    val descriptor: OwnershipTransferDescriptor,
    val schemaVersion: Int,
    val obligationState: ReleaseObligationState,
    val resourceState: ReleaseResourceState,
    val revision: Long,
    val updatedAtMillis: Long,
    val payloadHash: String,
    val payloadFormatVersion: Int,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(revision > 0L) { "revision must be positive" }
        require(updatedAtMillis >= 0L) { "updatedAtMillis must not be negative" }
        require(payloadFormatVersion > 0) { "payloadFormatVersion must be positive" }
        require(payloadHash == DurableReleasePayloadHasher.hash(descriptor, payloadFormatVersion)) {
            "payloadHash must match the canonical descriptor payload"
        }
        require(resourceState == expectedResourceState(obligationState)) {
            "resourceState must match obligationState"
        }
    }

    /**
     * Applies a pure transition. A RELEASED transition requires a verifier supplied
     * by the trusted composition root; verifier exceptions fail closed.
     * A durable ledger must additionally make revision CAS and event writes atomic.
     */
    internal fun transition(
        change: ReleaseIntentTransition,
        proofVerifier: ReleaseProofVerifier? = null,
    ): ReleaseIntentTransitionResult {
        if (change.expectedRevision != revision) {
            return ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.STALE_REVISION)
        }
        if (change.nextState == obligationState) {
            return ReleaseIntentTransitionResult.Idempotent(this)
        }
        if (!obligationState.canTransitionTo(change.nextState)) {
            return ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.INVALID_STATE_TRANSITION)
        }
        if (change.nextState == ReleaseObligationState.RELEASED) {
            val proof = change.releaseProof
                ?: return ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.RELEASE_PROOF_REQUIRED)
            val verifier = proofVerifier
                ?: return ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.RELEASE_PROOF_REQUIRED)
            val verified = try {
                proof.descriptor == descriptor && verifier.verify(proof, descriptor)
            } catch (_: Exception) {
                false
            }
            if (!verified) {
                return ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.RELEASE_PROOF_MISMATCH)
            }
        }

        val nextRevision = try {
            Math.addExact(revision, 1L)
        } catch (_: ArithmeticException) {
            return ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.REVISION_OVERFLOW)
        }
        return ReleaseIntentTransitionResult.Applied(
            DurableReleaseIntent(
                descriptor = descriptor,
                schemaVersion = schemaVersion,
                obligationState = change.nextState,
                resourceState = expectedResourceState(change.nextState),
                revision = nextRevision,
                updatedAtMillis = updatedAtMillis,
                payloadHash = payloadHash,
                payloadFormatVersion = payloadFormatVersion,
            ),
        )
    }

    // Preserve data-class structural behavior except copy(), which was a RELEASED bypass.
    operator fun component1() = descriptor
    operator fun component2() = schemaVersion
    operator fun component3() = obligationState
    operator fun component4() = resourceState
    operator fun component5() = revision
    operator fun component6() = updatedAtMillis
    operator fun component7() = payloadHash
    operator fun component8() = payloadFormatVersion

    override fun equals(other: Any?): Boolean =
        this === other || (other is DurableReleaseIntent &&
            descriptor == other.descriptor &&
            schemaVersion == other.schemaVersion &&
            obligationState == other.obligationState &&
            resourceState == other.resourceState &&
            revision == other.revision &&
            updatedAtMillis == other.updatedAtMillis &&
            payloadHash == other.payloadHash &&
            payloadFormatVersion == other.payloadFormatVersion)

    override fun hashCode(): Int {
        var result = descriptor.hashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + obligationState.hashCode()
        result = 31 * result + resourceState.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + updatedAtMillis.hashCode()
        result = 31 * result + payloadHash.hashCode()
        result = 31 * result + payloadFormatVersion
        return result
    }

    override fun toString(): String =
        "DurableReleaseIntent(descriptor=$descriptor, schemaVersion=$schemaVersion, " +
            "obligationState=$obligationState, resourceState=$resourceState, revision=$revision, " +
            "updatedAtMillis=$updatedAtMillis, payloadHash=$payloadHash, " +
            "payloadFormatVersion=$payloadFormatVersion)"

    companion object {
        /** Creates only a fresh PREPARED obligation. */
        fun createPrepared(
            descriptor: OwnershipTransferDescriptor,
            schemaVersion: Int = 1,
            revision: Long = 1L,
            updatedAtMillis: Long,
            payloadFormatVersion: Int = DurableReleasePayloadHasher.CURRENT_FORMAT_VERSION,
        ): DurableReleaseIntent = DurableReleaseIntent(
            descriptor = descriptor,
            schemaVersion = schemaVersion,
            obligationState = ReleaseObligationState.PREPARED,
            resourceState = ReleaseResourceState.CALLER_OWNED,
            revision = revision,
            updatedAtMillis = updatedAtMillis,
            payloadHash = DurableReleasePayloadHasher.hash(descriptor, payloadFormatVersion),
            payloadFormatVersion = payloadFormatVersion,
        )

        /**
         * Restores metadata after durable-store recovery.
         *
         * RELEASED is accepted only when the trusted verifier validates its persisted
         * authority/evidence metadata. PREPARED, ACCEPTED and UNKNOWN do not need a
         * release proof; UNKNOWN remains quarantined and is not resolved here.
         */
        internal fun restore(
            descriptor: OwnershipTransferDescriptor,
            schemaVersion: Int,
            obligationState: ReleaseObligationState,
            resourceState: ReleaseResourceState,
            revision: Long,
            updatedAtMillis: Long,
            payloadHash: String,
            payloadFormatVersion: Int = DurableReleasePayloadHasher.CURRENT_FORMAT_VERSION,
            releaseProof: ReleaseProof? = null,
            proofVerifier: ReleaseProofVerifier? = null,
        ): DurableReleaseIntent {
            if (obligationState == ReleaseObligationState.RELEASED) {
                require(releaseProof != null) { "RELEASED recovery requires release proof metadata" }
                require(releaseProof.descriptor == descriptor) { "Release proof descriptor mismatch" }
                require(proofVerifier != null) { "RELEASED recovery requires a trusted proof verifier" }
                val verified = try {
                    proofVerifier.verify(releaseProof, descriptor)
                } catch (_: Exception) {
                    false
                }
                require(verified) { "Release proof was not verified by the trusted authority" }
            } else {
                require(releaseProof == null) { "Release proof is only valid for RELEASED recovery" }
            }
            return DurableReleaseIntent(
                descriptor, schemaVersion, obligationState, resourceState, revision,
                updatedAtMillis, payloadHash, payloadFormatVersion,
            )
        }
    }
}

private fun expectedResourceState(state: ReleaseObligationState): ReleaseResourceState = when (state) {
    ReleaseObligationState.PREPARED -> ReleaseResourceState.CALLER_OWNED
    ReleaseObligationState.ACCEPTED -> ReleaseResourceState.RUNTIME_OWNED
    ReleaseObligationState.RELEASED -> ReleaseResourceState.RELEASED
    ReleaseObligationState.UNKNOWN -> ReleaseResourceState.UNKNOWN
}

/**
 * Versioned canonical SHA-256 hash for immutable ownership descriptor metadata.
 *
 * Strings use UTF-8 with a signed 32-bit big-endian byte-length prefix. Integer
 * fields use signed 64-bit big-endian encoding. Enum identifiers use stable names.
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

/** Result of a durable ledger commit attempt. */
enum class DurableLedgerCommitResult {
    COMMITTED,
    NOT_COMMITTED,
    UNKNOWN,
    CONTRACT_VIOLATION,
}

/** Lookup after the durable store has completed the required recovery protocol. */
sealed interface DurableReleaseIntentLookup {
    data class Found(val intent: DurableReleaseIntent) : DurableReleaseIntentLookup
    data object NotFoundAfterRecovery : DurableReleaseIntentLookup
    data object Unknown : DurableReleaseIntentLookup
}

sealed interface DurableLedgerCommitDecision {
    data object Committed : DurableLedgerCommitDecision

    /** Confirms absence of a commit only; it never authorizes resource release. */
    data object NotCommittedConfirmed : DurableLedgerCommitDecision

    /** Preserve custody/quarantine because commit outcome is ambiguous or unproven. */
    data object PreserveCustody : DurableLedgerCommitDecision

    /** Stop retries because the caller violated the durable contract. */
    data object StopRetry : DurableLedgerCommitDecision
}

sealed interface DurableLedgerLookupDecision {
    data class Found(val intent: DurableReleaseIntent) : DurableLedgerLookupDecision
    data object AbsenceConfirmed : DurableLedgerLookupDecision
    data object PreserveCustody : DurableLedgerLookupDecision
}

/**
 * Pure fail-closed interpretation of durable ledger results.
 *
 * rollbackEvidenceConfirmed may be true only after storage recovery independently
 * proves rollback. Neither decision returned here authorizes release/reuse of a
 * runtime resource; that requires the separate release-proof contract.
 */
object DurableReleaseIntentDecisionPolicy {
    fun resolveCommit(
        result: DurableLedgerCommitResult,
        rollbackEvidenceConfirmed: Boolean = false,
    ): DurableLedgerCommitDecision = when (result) {
        DurableLedgerCommitResult.COMMITTED -> DurableLedgerCommitDecision.Committed
        DurableLedgerCommitResult.NOT_COMMITTED ->
            if (rollbackEvidenceConfirmed) DurableLedgerCommitDecision.NotCommittedConfirmed
            else DurableLedgerCommitDecision.PreserveCustody
        DurableLedgerCommitResult.UNKNOWN -> DurableLedgerCommitDecision.PreserveCustody
        DurableLedgerCommitResult.CONTRACT_VIOLATION -> DurableLedgerCommitDecision.StopRetry
    }

    fun resolveLookup(result: DurableReleaseIntentLookup): DurableLedgerLookupDecision = when (result) {
        is DurableReleaseIntentLookup.Found -> DurableLedgerLookupDecision.Found(result.intent)
        DurableReleaseIntentLookup.NotFoundAfterRecovery -> DurableLedgerLookupDecision.AbsenceConfirmed
        DurableReleaseIntentLookup.Unknown -> DurableLedgerLookupDecision.PreserveCustody
    }
}
