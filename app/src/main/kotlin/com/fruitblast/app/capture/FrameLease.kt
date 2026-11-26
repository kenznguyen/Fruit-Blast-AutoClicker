package com.fruitblast.app.capture

import java.io.Closeable

/**
 * Temporary access to a capture resource owned by the capture pipeline.
 *
 * Consumers must access the value only inside [useValue] and must not retain it.
 * Closing the lease prevents new readers and releases the resource exactly once
 * after any in-flight reader has returned.
 */
class FrameLease<T : Any> internal constructor(
    private val value: T,
    private val onRelease: () -> Unit,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var activeReaders = 0
    private var releaseClaimed = false

    val isClosed: Boolean
        get() = synchronized(lock) { closed }

    fun <R> useValue(block: (T) -> R): R {
        synchronized(lock) {
            check(!closed) { "Frame lease is closed" }
            activeReaders += 1
        }

        try {
            return block(value)
        } finally {
            releaseIfReadyAfterRead()
        }
    }

    override fun close() {
        val shouldRelease = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                claimReleaseIfReady()
            }
        }
        if (shouldRelease) onRelease()
    }

    private fun releaseIfReadyAfterRead() {
        val shouldRelease = synchronized(lock) {
            activeReaders -= 1
            check(activeReaders >= 0) { "Frame lease reader count underflow" }
            claimReleaseIfReady()
        }
        if (shouldRelease) onRelease()
    }

    private fun claimReleaseIfReady(): Boolean {
        if (!closed || activeReaders != 0 || releaseClaimed) return false
        releaseClaimed = true
        return true
    }
}
