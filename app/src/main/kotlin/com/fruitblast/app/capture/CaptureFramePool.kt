package com.fruitblast.app.capture

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class CaptureFramePool<T : Any>(
    private val releaseHandler: FrameReleaseHandler<T>,
    private val sessionGeneration: Long = 0L,
) : Closeable {
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
     * Retries only explicitly unaccepted obligations. The owner schedules
     * calls with the approved 100–1000 ms backoff; this pool creates no executor.
     */
    fun retryPendingReleases() {
        val retryIds = synchronized(lock) {
            releases.values
                .filter { it.state == ReleaseState.PENDING && it.retryCount < MAX_RETRIES }
                .map { it.request.releaseId }
        }
        retryIds.forEach(::dispatchRetry)
        synchronized(lock) {
            releases.values
                .filter { it.state == ReleaseState.PENDING && it.retryCount >= MAX_RETRIES }
                .forEach { it.state = ReleaseState.UNKNOWN }
        }
        notifyDrainObserversIfComplete()
    }

    fun snapshot(): CaptureDrainSnapshot = synchronized(lock) {
        CaptureDrainSnapshot(
            ingestionStopped = closed,
            slotEmpty = frame == null,
            outstandingLeaseCount = if (leaseOutstanding) 1 else 0,
            pendingReleaseCount = releases.values.count { it.state == ReleaseState.PENDING },
            activeReleaseCount = releases.values.count { it.state == ReleaseState.ACTIVE },
            unresolvedReleaseCount = releases.values.count {
                it.state == ReleaseState.FAILED || it.state == ReleaseState.UNKNOWN
            },
            releaseFailureCount = releases.values.count { it.state == ReleaseState.FAILED },
            releaseUnknownCount = releases.values.count { it.state == ReleaseState.UNKNOWN },
            completedReleaseCount = completedReleaseCount,
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
            nextReleaseId += 1
            ReleaseRecord(
                FrameReleaseRequest(sessionGeneration, nextReleaseId, value, reason),
            ).also { releases[nextReleaseId] = it }
        }
        dispatch(record)
    }

    private fun dispatchRetry(releaseId: Long) {
        val record = synchronized(lock) {
            releases[releaseId]?.takeIf { it.state == ReleaseState.PENDING }?.also {
                it.retryCount += 1
            }
        } ?: return
        dispatch(record)
    }

    private fun dispatch(record: ReleaseRecord<T>) {
        synchronized(lock) {
            if (record.state != ReleaseState.PENDING) return
            record.state = ReleaseState.ACTIVE
        }
        val completionObserved = AtomicBoolean(false)
        val completion: (FrameReleaseCompletion) -> Unit = { outcome ->
            completionObserved.set(true)
            completeRelease(record.request.releaseId, outcome)
        }
        val result = try {
            releaseHandler.dispatch(record.request, completion)
        } catch (_: Throwable) {
            completeRelease(record.request.releaseId, FrameReleaseCompletion.UNKNOWN)
            return
        }

        synchronized(lock) {
            val current = releases[record.request.releaseId] ?: return@synchronized
            if (result == ReleaseDispatchResult.REJECTED_NOT_ACCEPTED) {
                current.state = if (completionObserved.get()) {
                    ReleaseState.UNKNOWN
                } else {
                    ReleaseState.PENDING
                }
            }
        }
        notifyDrainObserversIfComplete()
    }

    private fun completeRelease(releaseId: Long, completion: FrameReleaseCompletion) {
        synchronized(lock) {
            val record = releases[releaseId] ?: return
            when (completion) {
                FrameReleaseCompletion.RELEASED -> {
                    releases.remove(releaseId)
                    completedReleaseCount += 1
                }
                FrameReleaseCompletion.FAILED -> record.state = ReleaseState.FAILED
                FrameReleaseCompletion.UNKNOWN -> record.state = ReleaseState.UNKNOWN
            }
        }
        notifyDrainObserversIfComplete()
    }

    private fun isDrainedLocked(): Boolean =
        closed && frame == null && !leaseOutstanding && releases.isEmpty()

    private fun notifyDrainObserversIfComplete() {
        val callbacks = synchronized(lock) {
            if (!isDrainedLocked() || drainObservers.isEmpty()) return
            drainObservers.toList().also { drainObservers.clear() }
        }
        callbacks.forEach { it(CaptureDrainResult.DRAINED) }
    }

    private data class ReleaseRecord<T : Any>(
        val request: FrameReleaseRequest<T>,
        var state: ReleaseState = ReleaseState.PENDING,
        var retryCount: Int = 0,
    )

    private enum class ReleaseState { PENDING, ACTIVE, FAILED, UNKNOWN }

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
)
