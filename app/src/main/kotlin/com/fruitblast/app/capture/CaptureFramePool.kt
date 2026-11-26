package com.fruitblast.app.capture

import java.io.Closeable

/**
 * Thread-safe, single-slot mailbox for capture frames.
 *
 * A newer frame replaces an older frame that has not been acquired yet.
 * While a consumer holds a [FrameLease], producers are backpressured and
 * incoming frames are released rather than overwriting the leased value.
 *
 * [releaseFrame] is invoked outside the internal lock on the thread that
 * completes the corresponding operation. It must be safe to call from any
 * producer, consumer, or close caller thread and should not block.
 */
class CaptureFramePool<T : Any>(
    private val releaseFrame: (T) -> Unit,
) : Closeable {
    private val lock = Any()

    private var frame: T? = null
    private var leaseOutstanding = false
    private var closed = false
    private var leaseGeneration = 0L

    /**
     * Transfers ownership of [value] to this pool.
     *
     * STORED means the slot was empty; REPLACED means an unconsumed frame was
     * released and replaced; BACKPRESSURE means a consumer still holds the
     * slot's lease; CLOSED means the pool no longer accepts frames.
     *
     * In every outcome other than STORED, ownership is still consumed by this
     * call and [releaseFrame] is invoked for the incoming value.
     */
    fun offerLatest(value: T): FramePublishResult {
        var frameToRelease: T? = null
        var incomingToRelease: T? = null

        val result = synchronized(lock) {
            when {
                closed -> {
                    incomingToRelease = value
                    FramePublishResult.CLOSED
                }

                leaseOutstanding -> {
                    incomingToRelease = value
                    FramePublishResult.BACKPRESSURE
                }

                else -> {
                    frameToRelease = frame
                    frame = value
                    if (frameToRelease == null) {
                        FramePublishResult.STORED
                    } else {
                        FramePublishResult.REPLACED
                    }
                }
            }
        }

        frameToRelease?.let(releaseFrame)
        incomingToRelease?.let(releaseFrame)
        return result
    }

    /**
     * Acquires the pending latest frame once.
     *
     * Returns null when the slot is empty, already leased, or closed. The
     * frame is released when the returned lease closes, including when the
     * pool was closed while the consumer still held that lease.
     */
    fun acquireLatest(): FrameLease<T>? {
        val acquired = synchronized(lock) {
            if (closed || leaseOutstanding) {
                null
            } else {
                val current = frame ?: return@synchronized null
                leaseOutstanding = true
                leaseGeneration += 1
                current to leaseGeneration
            }
        } ?: return null

        return FrameLease(acquired.first) {
            releaseLease(acquired.second)
        }
    }

    /**
     * Stops accepting or serving frames.
     *
     * An available frame is released immediately. A frame with an outstanding
     * lease remains valid until that lease closes, then it is released once.
     */
    override fun close() {
        val frameToRelease = synchronized(lock) {
            if (closed) {
                null
            } else {
                closed = true
                if (leaseOutstanding) {
                    null
                } else {
                    frame.also { frame = null }
                }
            }
        }

        frameToRelease?.let(releaseFrame)
    }

    private fun releaseLease(generation: Long) {
        val frameToRelease = synchronized(lock) {
            if (!leaseOutstanding || leaseGeneration != generation) {
                null
            } else {
                leaseOutstanding = false
                frame.also { frame = null }
            }
        }

        frameToRelease?.let(releaseFrame)
    }
}

/** Outcome of transferring a frame into [CaptureFramePool]. */
enum class FramePublishResult {
    STORED,
    REPLACED,
    BACKPRESSURE,
    CLOSED,
}
