package com.fruitblast.app.capture

/**
 * Explains why a resource must remain quarantined instead of being released or reused.
 */
enum class QuarantineReason {
    RELEASE_FAILED,
    RELEASE_UNKNOWN,
    EXECUTOR_REJECTED,
    OWNERSHIP_TRANSFER_UNKNOWN,
    OWNERSHIP_CONTRACT_VIOLATION,
}

/**
 * Immutable identity and audit metadata for a single ownership-transfer attempt.
 */
data class OwnershipTransferDescriptor(
    val transferId: String,
    val sessionGeneration: Long,
    val resourceId: Long,
    val releaseId: Long,
    val reason: QuarantineReason,
    val estimatedBytes: Long,
    val createdAtMillis: Long,
) {
    init {
        require(transferId.isNotBlank()) { "transferId must not be blank" }
        require(sessionGeneration > 0L) { "sessionGeneration must be positive" }
        require(resourceId > 0L) { "resourceId must be positive" }
        require(releaseId > 0L) { "releaseId must be positive" }
        require(estimatedBytes >= 0L) { "estimatedBytes must not be negative" }
        require(createdAtMillis >= 0L) { "createdAtMillis must not be negative" }
    }
}

enum class OwnershipTransferResult {
    ACCEPTED,
    ALREADY_ACCEPTED,
    REJECTED_NOT_ACCEPTED,
    UNKNOWN,
}

enum class OwnershipTransferStatus {
    ACCEPTED,
    NOT_COMMITTED,
    UNKNOWN,
}

/**
 * Atomically transfers custody of a resource to the quarantine registry.
 *
 * Before commit, the registry may validate and retain a reference but must not
 * release, reuse, or otherwise operate the resource. Repeated submissions with
 * the same transfer ID and descriptor must be idempotent. Reusing an ID with a
 * different descriptor is a contract violation.
 */
interface ReleaseOwnershipSink<T : Any> {
    fun acceptOwnership(
        descriptor: OwnershipTransferDescriptor,
        resource: T,
    ): OwnershipTransferResult

    /**
     * Queries the commit state for a transfer.
     *
     * NOT_COMMITTED guarantees that no commit is in flight and that no earlier
     * submission for this transfer ID can complete later. UNKNOWN requires the
     * caller to keep the resource frozen and retain custody.
     */
    fun queryTransfer(transferId: String): OwnershipTransferStatus
}
/** Stable resource metadata used to construct an ownership-transfer descriptor. */
data class FrameResourceMetadata(
    val resourceId: Long,
    val estimatedBytes: Long,
)

/** Supplies immutable metadata once for each release obligation. */
fun interface FrameResourceMetadataProvider<T : Any> {
    fun describe(resource: T): FrameResourceMetadata
}
