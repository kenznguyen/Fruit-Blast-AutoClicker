package com.fruitblast.app.capture

import java.util.IdentityHashMap

/**
 * Session Manager-owned registry that atomically accepts and retains resource custody.
 *
 * A successful accept is the commit point: after ACCEPTED is returned, the caller
 * must not release, recycle, or reuse the resource. Rejected resources remain
 * caller-owned. This registry deliberately exposes no resource accessor.
 *
 * This first implementation has no removal/release API; lifecycle and release
 * retry orchestration are handled by the subsequent Session Manager tasks.
 */
class SessionQuarantineRegistry<T : Any> : ReleaseOwnershipSink<T> {
    private val lock = Any()
    private val transfers = linkedMapOf<String, TransferRecord<T>>()
    private val resources = IdentityHashMap<T, String>()
    private val resourceIds = mutableMapOf<Long, String>()
    private val sessionGenerations = linkedSetOf<Long>()
    private var quarantinedBytes = 0L

    override fun acceptOwnership(
        descriptor: OwnershipTransferDescriptor,
        resource: T,
    ): OwnershipTransferResult = synchronized(lock) {
        val existing = transfers[descriptor.transferId]
        if (existing != null) {
            return@synchronized if (
                existing.descriptor == descriptor && existing.resource === resource
            ) {
                OwnershipTransferResult.ALREADY_ACCEPTED
            } else {
                OwnershipTransferResult.CONTRACT_VIOLATION
            }
        }

        val resourceTransfer = resourceIds[descriptor.resourceId]
        if (resourceTransfer != null) {
            return@synchronized OwnershipTransferResult.CONTRACT_VIOLATION
        }

        if (resources.containsKey(resource)) {
            return@synchronized OwnershipTransferResult.REJECTED_NOT_ACCEPTED
        }

        if (transfers.size >= MAX_PENDING_RELEASES) {
            return@synchronized OwnershipTransferResult.REJECTED_NOT_ACCEPTED
        }

        if (descriptor.sessionGeneration !in sessionGenerations &&
            sessionGenerations.size >= MAX_QUARANTINED_SESSIONS
        ) {
            return@synchronized OwnershipTransferResult.REJECTED_NOT_ACCEPTED
        }

        if (descriptor.estimatedBytes > MAX_QUARANTINED_BYTES - quarantinedBytes) {
            return@synchronized OwnershipTransferResult.REJECTED_NOT_ACCEPTED
        }

        // Commit all custody and accounting indexes together while holding the lock.
        transfers[descriptor.transferId] = TransferRecord(descriptor, resource)
        resources[resource] = descriptor.transferId
        resourceIds[descriptor.resourceId] = descriptor.transferId
        sessionGenerations += descriptor.sessionGeneration
        quarantinedBytes += descriptor.estimatedBytes
        OwnershipTransferResult.ACCEPTED
    }

    override fun queryTransfer(transferId: String): OwnershipTransferStatus =
        synchronized(lock) {
            if (transfers.containsKey(transferId)) {
                OwnershipTransferStatus.ACCEPTED
            } else {
                // acceptOwnership is serialized with this query, so no commit can
                // still be in flight when an absent transfer is reported.
                OwnershipTransferStatus.NOT_COMMITTED
            }
        }

    private data class TransferRecord<T : Any>(
        val descriptor: OwnershipTransferDescriptor,
        val resource: T,
    )

    private companion object {
        const val MAX_QUARANTINED_BYTES = 32L * 1024L * 1024L
        const val MAX_QUARANTINED_SESSIONS = 2
        const val MAX_PENDING_RELEASES = 8
    }
}
