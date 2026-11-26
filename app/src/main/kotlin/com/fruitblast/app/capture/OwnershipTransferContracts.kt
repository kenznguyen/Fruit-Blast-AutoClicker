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
 * Receives custody of a resource after the transfer has been atomically committed.
 */
interface ReleaseOwnershipSink<T : Any> {
    fun acceptOwnership(
        descriptor: OwnershipTransferDescriptor,
        resource: T,
    ): OwnershipTransferResult

    fun queryTransfer(transferId: String): OwnershipTransferStatus
}
