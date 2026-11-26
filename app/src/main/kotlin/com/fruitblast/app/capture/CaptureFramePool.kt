package com.fruitblast.app.capture

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class CaptureFramePool<T : Any>(
    private val releaseHandler: FrameReleaseHandler<T>,
    private val sessionGeneration: Long = 0L,
    private val ownershipSink: ReleaseOwnershipSink<T>? = null,
    private val metadataProvider: FrameResourceMetadataProvider<T>? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : Closeable {
    init {
        require((ownershipSink == null) == (metadataProvider == null)) {
            "ownershipSink and metadataProvider must be provided together"
        }
        if (ownershipSink != null) {
            require(sessionGeneration > 0L) {
                "sessionGeneration must be positive when ownership transfer is enabled"
            }
        }
    }

    constructor(releaseFrame: (T) -> Unit) : this(
        releaseHandler = object : FrameReleaseHandler<T> {
            override fun dispatch(
                request: FrameReleaseRequest<T>,
                completion: (FrameReleaseCompletion) -> Unit,
            ): ReleaseDispatchResult {
                releaseFrame(request.resource)
                completion(FrameReleaseCompletion.RELEASED)
                return ReleaseDispatchResult.ACCEPTED
            }
        },
    )

    private val lock = Any()
    private var frame: T? = null
    private var leaseOutstanding = false
    private var closed = false
    private var nextLeaseId = 0L
    private var nextReleaseId = 0L
    private val releases = linkedMapOf<Long, ReleaseRecord<T>>()
    private var completedReleaseCount = 0
    private val drainObservers = mutableListOf<(CaptureDrainResult) -> Unit>()

    fun offerLatest(value: T): FramePublishResult {
        var displaced: T? = null
        var rejected: T? = null
        val result = synchronized(lock) {
            when {
                closed -> {
                    rejected = value
                    FramePublishResult.CLOSED
                }
                leaseOutstanding -> {
                    rejected = value
                    FramePublishResult.BACKPRESSURE
                }
                else -> {
                    displaced = frame
                    frame = value
                    if (displaced == null) FramePublishResult.STORED
                    else FramePublishResult.REPLACED
                }
            }
        }
        displaced?.let { dispatchRelease(it, FrameReleaseReason.REPLACED) }
        rejected?.let {
            dispatchRelease(
                it,
                if (result == FramePublishResult.CLOSED) FrameReleaseReason.CLOSED
                else FrameReleaseReason.BACKPRESSURE,
            )
        }
        return result
    }

    fun acquireLatest(): FrameLease<T>? {
        val acquired = synchronized(lock) {
            if (closed || leaseOutstanding) return@synchronized null
            val value = frame ?: return@synchronized null
            leaseOutstanding = true
            nextLeaseId += 1
            Triple(value, nextLeaseId, FrameLeaseIdentity(sessionGeneration, nextLeaseId))
        } ?: return null
        return FrameLeaseImpl(acquired.first, acquired.third) {
            releaseLeasedFrame(acquired.second)
        }
    }

    /** Call after the coordinator has stopped ingestion for this generation. */
    fun beginDrain() {
        val pending = synchronized(lock) {
            if (closed) return@synchronized null
            closed = true
            if (leaseOutstanding) null else frame.also { frame = null }
        }
        pending?.let { dispatchRelease(it, FrameReleaseReason.DRAIN) }
        notifyDrainObserversIfComplete()
    }

    override fun close() = beginDrain()

    /**
     * Retries only explicitly unaccepted release-handler obligations. The owner
     * schedules calls with the approved backoff; this pool creates no executor.
     */
    fun retryPendingReleases() {
        val retryIds = synchronized(lock) {
            releases.values
                .filter { it.releaseState == ReleaseState.PENDING && it.retryCount < MAX_RETRIES }
                .map { it.request.releaseId }
        }
        retryIds.forEach(::dispatchRetry)

        val exhausted = synchronized(lock) {
            releases.values
                .filter { it.releaseState == ReleaseState.PENDING && it.retryCount >= MAX_RETRIES }
                .onEach {
                    it.releaseState = ReleaseState.UNKNOWN
                    it.executionQuiescent = true
                }
                .toList()
        }
        exhausted.forEach { attemptOwnershipTransfer(it) }
        notifyDrainObserversIfComplete()
    }

    /** Retries registry transfers explicitly confirmed as not committed. */
    fun retryRejectedOwnershipTransfers() {
        val retryRecords = synchronized(lock) {
            releases.values
                .filter { it.ownershipState == OwnershipState.PENDING }
                .onEach { it.ownershipState = OwnershipState.IN_FLIGHT }
                .toList()
        }
        retryRecords.forEach { submitOwnershipTransfer(it, alreadyInFlight = true) }
        notifyDrainObserversIfComplete()
    }

    /** Reconciles ambiguous registry outcomes before any retry is attempted. */
    fun reconcileOwnershipTransfers() {
        val unresolved = synchronized(lock) {
            releases.values
                .filter { it.ownershipState == OwnershipState.UNKNOWN }
                .onEach { it.ownershipState = OwnershipState.RECONCILING }
                .toList()
        }
        unresolved.forEach { record ->
            val descriptor = record.transferDescriptor ?: return@forEach
            val status = try {
                ownershipSink?.queryTransfer(descriptor.transferId)
                    ?: return@forEach
            } catch (_: Throwable) {
                synchronized(lock) {
                    if (releases[record.request.releaseId] === record &&
                        record.ownershipState == OwnershipState.RECONCILING
                    ) {
                        record.ownershipState = OwnershipState.UNKNOWN
                    }
                }
                return@forEach
            }

            when (status) {
                OwnershipTransferStatus.ACCEPTED -> completeOwnershipTransfer(record)
                OwnershipTransferStatus.NOT_COMMITTED -> {
                    val shouldRetry = synchronized(lock) {
                        if (releases[record.request.releaseId] !== record ||
                            record.ownershipState != OwnershipState.RECONCILING
                        ) {
                            false
                        } else {
                            record.ownershipState = OwnershipState.IN_FLIGHT
                            true
                        }
                    }
                    if (shouldRetry) submitOwnershipTransfer(record, alreadyInFlight = true)
                }
                OwnershipTransferStatus.UNKNOWN -> synchronized(lock) {
                    if (releases[record.request.releaseId] === record &&
                        record.ownershipState == OwnershipState.RECONCILING
                    ) {
                        record.ownershipState = OwnershipState.UNKNOWN
                    }
                }
            }
        }
        notifyDrainObserversIfComplete()
    }

    fun snapshot(): CaptureDrainSnapshot = synchronized(lock) {
        CaptureDrainSnapshot(
            ingestionStopped = closed,
            slotEmpty = frame == null,
            outstandingLeaseCount = if (leaseOutstanding) 1 else 0,
            pendingReleaseCount = releases.values.count { it.releaseState == ReleaseState.PENDING },
            activeReleaseCount = releases.values.count {
                it.releaseState == ReleaseState.ACTIVE ||
                    it.releaseState == ReleaseState.DISPATCHING
            },
            unresolvedReleaseCount = releases.values.count {
                it.releaseState == ReleaseState.FAILED || it.releaseState == ReleaseState.UNKNOWN
            },
            releaseFailureCount = releases.values.count { it.releaseState == ReleaseState.FAILED },
            releaseUnknownCount = releases.values.count { it.releaseState == ReleaseState.UNKNOWN },
            completedReleaseCount = completedReleaseCount,
            pendingOwnershipTransferCount = releases.values.count {
                it.ownershipState == OwnershipState.PENDING
            },
            inFlightOwnershipTransferCount = releases.values.count {
                it.ownershipState == OwnershipState.IN_FLIGHT ||
                    it.ownershipState == OwnershipState.RECONCILING
            },
            unresolvedOwnershipTransferCount = releases.values.count {
                it.ownershipState == OwnershipState.UNKNOWN
            },
        )
    }

    fun observeDrain(callback: (CaptureDrainResult) -> Unit) {
        val immediate = synchronized(lock) {
            if (isDrainedLocked()) CaptureDrainResult.DRAINED
            else {
                drainObservers += callback
                null
            }
        }
        if (immediate != null) callback(immediate)
    }

    private fun releaseLeasedFrame(leaseId: Long) {
        val value = synchronized(lock) {
            if (!leaseOutstanding || nextLeaseId != leaseId) return
            leaseOutstanding = false
            frame.also { frame = null }
        }
        value?.let { dispatchRelease(it, FrameReleaseReason.LEASE_CLOSED) }
        notifyDrainObserversIfComplete()
    }

    private fun dispatchRelease(value: T, reason: FrameReleaseReason) {
        val record = synchronized(lock) {
            check(nextReleaseId < Long.MAX_VALUE) { "releaseId space exhausted" }
            nextReleaseId += 1
            ReleaseRecord(
                request = FrameReleaseRequest(sessionGeneration, nextReleaseId, value, reason),
                releaseState = ReleaseState.DISPATCHING,
            ).also { releases[nextReleaseId] = it }
        }
        dispatch(record)
    }

    private fun dispatchRetry(releaseId: Long) {
        val record = synchronized(lock) {
            releases[releaseId]
                ?.takeIf { it.releaseState == ReleaseState.PENDING }
                ?.also {
                    it.retryCount += 1
                    it.releaseState = ReleaseState.DISPATCHING
                    it.executionQuiescent = false
                }
        } ?: return
        dispatch(record)
    }

    private fun dispatch(record: ReleaseRecord<T>) {
        synchronized(lock) {
            if (record.releaseState != ReleaseState.DISPATCHING) return
        }
        val completionObserved = AtomicBoolean(false)
        val completion: (FrameReleaseCompletion) -> Unit = { outcome ->
            completionObserved.set(true)
            completeRelease(record.request.releaseId, outcome, callbackConfirmedQuiescence = true)
        }
        val result = try {
            releaseHandler.dispatch(record.request, completion)
        } catch (_: Throwable) {
            completeRelease(
                record.request.releaseId,
                FrameReleaseCompletion.UNKNOWN,
                callbackConfirmedQuiescence = false,
            )
            return
        }

        var becameQuiescentRejection = false
        synchronized(lock) {
            val current = releases[record.request.releaseId] ?: return@synchronized
            if (current.releaseState != ReleaseState.DISPATCHING) return@synchronized

            if (result == ReleaseDispatchResult.REJECTED_NOT_ACCEPTED) {
                current.releaseState = if (completionObserved.get()) {
                    ReleaseState.UNKNOWN
                } else {
                    current.executionQuiescent = true
                    becameQuiescentRejection = true
                    ReleaseState.PENDING
                }
            } else {
                current.releaseState = ReleaseState.ACTIVE
            }
        }
        if (becameQuiescentRejection) {
            // A rejected dispatch is known not to have started execution. It is
            // eligible for handoff only after the bounded release retry budget.
        }
        notifyDrainObserversIfComplete()
    }

    private fun completeRelease(
        releaseId: Long,
        completion: FrameReleaseCompletion,
        callbackConfirmedQuiescence: Boolean,
    ) {
        var transfer: ReleaseRecord<T>? = null
        synchronized(lock) {
            val record = releases[releaseId] ?: return
            if (record.terminalCallbackObserved) return
            if (callbackConfirmedQuiescence) {
                record.terminalCallbackObserved = true
                record.executionQuiescent = true
            }
            when (completion) {
                FrameReleaseCompletion.RELEASED -> {
                    if (callbackConfirmedQuiescence) {
                        releases.remove(releaseId)
                        completedReleaseCount += 1
                    } else {
                        record.releaseState = ReleaseState.UNKNOWN
                    }
                }
                FrameReleaseCompletion.FAILED -> {
                    record.releaseState = ReleaseState.FAILED
                    if (record.executionQuiescent) transfer = record
                }
                FrameReleaseCompletion.UNKNOWN -> {
                    record.releaseState = ReleaseState.UNKNOWN
                    if (record.executionQuiescent) transfer = record
                }
            }
        }
        transfer?.let { attemptOwnershipTransfer(it) }
        notifyDrainObserversIfComplete()
    }

    private fun attemptOwnershipTransfer(record: ReleaseRecord<T>) {
        val sink = ownershipSink ?: return
        val provider = metadataProvider ?: return
        val shouldPrepare = synchronized(lock) {
            releases[record.request.releaseId] === record &&
                record.executionQuiescent &&
                !record.terminalCallbackObserved.not() &&
                record.ownershipState == null
        }
        if (!shouldPrepare) return

        val descriptor = try {
            val metadata = provider.describe(record.request.resource)
            if (metadata.resourceId <= 0L || metadata.estimatedBytes < 0L) return
            val createdAtMillis = clockMillis()
            if (createdAtMillis < 0L) return
            OwnershipTransferDescriptor(
                transferId = "capture-frame:$sessionGeneration:${record.request.releaseId}",
                sessionGeneration = sessionGeneration,
                resourceId = metadata.resourceId,
                releaseId = record.request.releaseId,
                reason = when (record.releaseState) {
                    ReleaseState.FAILED -> QuarantineReason.RELEASE_FAILED
                    else -> if (record.retryCount >= MAX_RETRIES) {
                        QuarantineReason.EXECUTOR_REJECTED
                    } else {
                        QuarantineReason.RELEASE_UNKNOWN
                    }
                },
                estimatedBytes = metadata.estimatedBytes,
                createdAtMillis = createdAtMillis,
            )
        } catch (_: Throwable) {
            return
        }

        val acceptedForSubmission = synchronized(lock) {
            if (releases[record.request.releaseId] !== record ||
                record.ownershipState != null ||
                !record.executionQuiescent
            ) {
                false
            } else {
                record.transferDescriptor = descriptor
                record.ownershipState = OwnershipState.IN_FLIGHT
                true
            }
        }
        if (acceptedForSubmission) submitOwnershipTransfer(record, alreadyInFlight = true, sink = sink)
    }

    private fun submitOwnershipTransfer(
        record: ReleaseRecord<T>,
        alreadyInFlight: Boolean,
        sink: ReleaseOwnershipSink<T>? = ownershipSink,
    ) {
        if (sink == null) return
        val descriptor = synchronized(lock) {
            if (releases[record.request.releaseId] !== record ||
                (alreadyInFlight && record.ownershipState != OwnershipState.IN_FLIGHT)
            ) {
                null
            } else {
                record.transferDescriptor
            }
        } ?: return

        val result = try {
            sink.acceptOwnership(descriptor, record.request.resource)
        } catch (_: Throwable) {
            OwnershipTransferResult.UNKNOWN
        }

        when (result) {
            OwnershipTransferResult.ACCEPTED,
            OwnershipTransferResult.ALREADY_ACCEPTED -> completeOwnershipTransfer(record)
            OwnershipTransferResult.REJECTED_NOT_ACCEPTED -> synchronized(lock) {
                if (releases[record.request.releaseId] === record &&
                    record.ownershipState == OwnershipState.IN_FLIGHT
                ) {
                    record.ownershipState = OwnershipState.PENDING
                }
            }
            OwnershipTransferResult.UNKNOWN -> synchronized(lock) {
                if (releases[record.request.releaseId] === record &&
                    record.ownershipState == OwnershipState.IN_FLIGHT
                ) {
                    record.ownershipState = OwnershipState.UNKNOWN
                }
            }
        }
    }

    private fun completeOwnershipTransfer(record: ReleaseRecord<T>) {
        synchronized(lock) {
            if (releases[record.request.releaseId] !== record) return
            if (record.ownershipState != OwnershipState.IN_FLIGHT &&
                record.ownershipState != OwnershipState.RECONCILING
            ) return
            releases.remove(record.request.releaseId)
        }
    }

    private fun isDrainedLocked(): Boolean =
        closed && frame == null && !leaseOutstanding && releases.isEmpty()

    private fun notifyDrainObserversIfComplete() {
        val notification = synchronized(lock) {
            if (drainObservers.isEmpty()) return
            val hasPendingOwnership = releases.values.any {
                it.ownershipState == OwnershipState.PENDING ||
                    it.ownershipState == OwnershipState.IN_FLIGHT ||
                    it.ownershipState == OwnershipState.RECONCILING
            }
            val result = when {
                isDrainedLocked() -> CaptureDrainResult.DRAINED
                hasPendingOwnership -> null
                closed && frame == null && !leaseOutstanding &&
                    releases.values.none {
                        it.releaseState == ReleaseState.PENDING ||
                            it.releaseState == ReleaseState.DISPATCHING ||
                            it.releaseState == ReleaseState.ACTIVE
                    } && releases.values.any { it.releaseState == ReleaseState.FAILED } ->
                    CaptureDrainResult.RELEASE_FAILED
                closed && frame == null && !leaseOutstanding &&
                    releases.values.none {
                        it.releaseState == ReleaseState.PENDING ||
                            it.releaseState == ReleaseState.DISPATCHING ||
                            it.releaseState == ReleaseState.ACTIVE
                    } && releases.values.any {
                        it.releaseState == ReleaseState.UNKNOWN ||
                            it.ownershipState == OwnershipState.UNKNOWN
                    } -> CaptureDrainResult.RELEASE_UNKNOWN
                else -> null
            } ?: return
            drainObservers.toList().also { drainObservers.clear() } to result
        }
        notification.first.forEach { it(notification.second) }
    }

    private data class ReleaseRecord<T : Any>(
        val request: FrameReleaseRequest<T>,
        var releaseState: ReleaseState = ReleaseState.PENDING,
        var retryCount: Int = 0,
        var executionQuiescent: Boolean = false,
        var terminalCallbackObserved: Boolean = false,
        var transferDescriptor: OwnershipTransferDescriptor? = null,
        var ownershipState: OwnershipState? = null,
    )

    private enum class ReleaseState { PENDING, DISPATCHING, ACTIVE, FAILED, UNKNOWN }
    private enum class OwnershipState { PENDING, IN_FLIGHT, RECONCILING, UNKNOWN }

    private companion object {
        const val MAX_RETRIES = 3
    }
}

enum class FramePublishResult { STORED, REPLACED, BACKPRESSURE, CLOSED }

interface FrameReleaseHandler<T : Any> {
    fun dispatch(
        request: FrameReleaseRequest<T>,
        completion: (FrameReleaseCompletion) -> Unit,
    ): ReleaseDispatchResult
}

data class FrameReleaseRequest<T : Any>(
    val sessionGeneration: Long,
    val releaseId: Long,
    val resource: T,
    val reason: FrameReleaseReason,
)

enum class FrameReleaseReason { REPLACED, BACKPRESSURE, CLOSED, DRAIN, LEASE_CLOSED }
enum class ReleaseDispatchResult { ACCEPTED, REJECTED_NOT_ACCEPTED }
enum class FrameReleaseCompletion { RELEASED, FAILED, UNKNOWN }
enum class CaptureDrainResult { DRAINED, TIMED_OUT, RELEASE_FAILED, RELEASE_UNKNOWN, INVALID_SESSION }

data class CaptureDrainSnapshot(
    val ingestionStopped: Boolean,
    val slotEmpty: Boolean,
    val outstandingLeaseCount: Int,
    val pendingReleaseCount: Int,
    val activeReleaseCount: Int,
    val unresolvedReleaseCount: Int,
    val releaseFailureCount: Int,
    val releaseUnknownCount: Int,
    val completedReleaseCount: Int,
    val pendingOwnershipTransferCount: Int = 0,
    val inFlightOwnershipTransferCount: Int = 0,
    val unresolvedOwnershipTransferCount: Int = 0,
)
