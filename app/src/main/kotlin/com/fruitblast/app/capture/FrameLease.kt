package com.fruitblast.app.capture

import java.io.Closeable

data class FrameLeaseIdentity(
    val sessionGeneration: Long,
    val leaseId: Long,
)

interface FrameLease<T : Any> : Closeable {
    val identity: FrameLeaseIdentity
    val isClosed: Boolean
    fun <R> useValue(block: (T) -> R): R

    companion object {
        operator fun <T : Any> invoke(value: T, onRelease: () -> Unit): FrameLease<T> =
            FrameLeaseImpl(value, FrameLeaseIdentity(0L, 0L), onRelease)
    }
}

internal class FrameLeaseImpl<T : Any>(
    private val value: T,
    override val identity: FrameLeaseIdentity,
    private val onRelease: () -> Unit,
) : FrameLease<T> {
    private val lock = Any()
    private var closed = false
    private var activeReaders = 0
    private var releaseClaimed = false

    override val isClosed: Boolean
        get() = synchronized(lock) { closed }

    override fun <R> useValue(block: (T) -> R): R {
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
            if (closed) false else {
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
