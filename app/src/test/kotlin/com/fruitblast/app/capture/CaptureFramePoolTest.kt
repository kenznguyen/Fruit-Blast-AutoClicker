package com.fruitblast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFramePoolTest {
    @Test
    fun keepsOnlyTheLatestUnleasedFrameAndReleasesReplacedFrame() {
        val released = mutableListOf<String>()
        val pool = CaptureFramePool<String> { released += it }

        assertEquals(FramePublishResult.STORED, pool.offerLatest("frame-1"))
        assertEquals(FramePublishResult.REPLACED, pool.offerLatest("frame-2"))
        assertEquals(listOf("frame-1"), released)

        val lease = pool.acquireLatest()
        assertEquals("frame-2", lease?.useValue { it })
        lease?.close()
        pool.close()

        assertEquals(listOf("frame-1", "frame-2"), released)
    }

    @Test
    fun dropsIncomingFrameWhenTheSingleSlotIsLeased() {
        val released = mutableListOf<String>()
        val pool = CaptureFramePool<String> { released += it }
        pool.offerLatest("leased-frame")
        val lease = pool.acquireLatest()

        assertEquals(FramePublishResult.BACKPRESSURE, pool.offerLatest("dropped-frame"))
        assertEquals(listOf("dropped-frame"), released)
        assertEquals("leased-frame", lease?.useValue { it })

        lease?.close()
        pool.close()
        assertEquals(listOf("dropped-frame", "leased-frame"), released)
    }

    @Test
    fun acquireReturnsNullWhenNoFrameIsAvailable() {
        val pool = CaptureFramePool<String> {}

        assertNull(pool.acquireLatest())

        pool.close()
    }

    @Test
    fun closeReleasesAnAvailableFrameAndRejectsFutureFrames() {
        val released = mutableListOf<String>()
        val pool = CaptureFramePool<String> { released += it }
        pool.offerLatest("queued-frame")

        pool.close()

        assertEquals(listOf("queued-frame"), released)
        assertEquals(FramePublishResult.CLOSED, pool.offerLatest("late-frame"))
        assertEquals(listOf("queued-frame", "late-frame"), released)
        assertNull(pool.acquireLatest())
    }

    @Test
    fun closeDefersReleasingLeasedFrameUntilLeaseIsClosed() {
        val released = mutableListOf<String>()
        val pool = CaptureFramePool<String> { released += it }
        pool.offerLatest("active-frame")
        val lease = pool.acquireLatest()

        pool.close()

        assertTrue(lease?.isClosed == false)
        assertTrue(released.isEmpty())
        assertNull(pool.acquireLatest())

        lease?.close()
        lease?.close()

        assertEquals(listOf("active-frame"), released)
    }

    @Test
    fun leaseReleaseMakesTheSlotAvailableForTheNextLatestFrame() {
        val released = mutableListOf<String>()
        val pool = CaptureFramePool<String> { released += it }
        pool.offerLatest("first")
        val firstLease = pool.acquireLatest()

        assertEquals(FramePublishResult.BACKPRESSURE, pool.offerLatest("blocked"))
        firstLease?.close()

        assertEquals(FramePublishResult.STORED, pool.offerLatest("second"))
        val secondLease = pool.acquireLatest()
        assertEquals("second", secondLease?.useValue { it })

        secondLease?.close()
        pool.close()
        assertEquals(listOf("blocked", "first", "second"), released)
    }

    @Test
    fun leaseValueRemainsStableUntilItsLeaseEnds() {
        val first = Any()
        val pool = CaptureFramePool<Any> {}
        pool.offerLatest(first)
        val lease = pool.acquireLatest()

        assertSame(first, lease?.useValue { it })
        assertEquals(FramePublishResult.BACKPRESSURE, pool.offerLatest(Any()))
        assertSame(first, lease?.useValue { it })

        lease?.close()
        pool.close()
    }

    @Test
    fun closeIsIdempotent() {
        var releases = 0
        val pool = CaptureFramePool<String> { releases += 1 }
        pool.offerLatest("frame")

        pool.close()
        pool.close()

        assertFalse(pool.offerLatest("late") == FramePublishResult.STORED)
        assertEquals(2, releases)
    }

    @Test
    fun releaseCallbackFailureDoesNotChangePublishResult() {
        val pool = CaptureFramePool<String> {
            if (it == "old-frame") error("release failed")
        }
        pool.offerLatest("old-frame")

        val result = pool.offerLatest("new-frame")

        assertEquals(FramePublishResult.REPLACED, result)
        pool.close()
    }

    @Test
    fun rejectedReleaseDispatchRemainsPendingUntilAccepted() {
        var dispatchAttempts = 0
        val handler = object : FrameReleaseHandler<String> {
            override fun dispatch(
                request: FrameReleaseRequest<String>,
                completion: (FrameReleaseCompletion) -> Unit,
            ): ReleaseDispatchResult {
                dispatchAttempts += 1
                return ReleaseDispatchResult.REJECTED_NOT_ACCEPTED
            }
        }
        val pool = CaptureFramePool(releaseHandler = handler)

        pool.offerLatest("old-frame")
        assertEquals(FramePublishResult.REPLACED, pool.offerLatest("new-frame"))

        val snapshot = pool.snapshot()
        assertEquals(1, snapshot.pendingReleaseCount)
        assertEquals(1, dispatchAttempts)
        pool.close()
    }

    @Test
    fun unknownDispatchOutcomeIsUnresolvedAndIsNotRetried() {
        var dispatchAttempts = 0
        val handler = object : FrameReleaseHandler<String> {
            override fun dispatch(
                request: FrameReleaseRequest<String>,
                completion: (FrameReleaseCompletion) -> Unit,
            ): ReleaseDispatchResult {
                dispatchAttempts += 1
                error("dispatch outcome cannot be determined")
            }
        }
        val pool = CaptureFramePool(releaseHandler = handler)

        pool.offerLatest("old-frame")
        assertEquals(FramePublishResult.REPLACED, pool.offerLatest("new-frame"))

        val snapshot = pool.snapshot()
        assertEquals(1, snapshot.unresolvedReleaseCount)
        assertEquals(1, dispatchAttempts)
        pool.close()
    }

    @Test
    fun unknownReleaseCompletionRemainsUnresolved() {
        lateinit var completionCallback: (FrameReleaseCompletion) -> Unit
        val handler = object : FrameReleaseHandler<String> {
            override fun dispatch(
                request: FrameReleaseRequest<String>,
                completion: (FrameReleaseCompletion) -> Unit,
            ): ReleaseDispatchResult {
                completionCallback = completion
                return ReleaseDispatchResult.ACCEPTED
            }
        }
        val pool = CaptureFramePool(releaseHandler = handler)

        pool.offerLatest("old-frame")
        pool.offerLatest("new-frame")
        completionCallback(FrameReleaseCompletion.UNKNOWN)

        assertEquals(1, pool.snapshot().unresolvedReleaseCount)
        pool.close()
    }


    @Test
    fun retriesOnlyConfirmedRejectionsAndStopsAfterThreeRetries() {
        var dispatchAttempts = 0
        val handler = object : FrameReleaseHandler<String> {
            override fun dispatch(
                request: FrameReleaseRequest<String>,
                completion: (FrameReleaseCompletion) -> Unit,
            ): ReleaseDispatchResult {
                dispatchAttempts += 1
                return ReleaseDispatchResult.REJECTED_NOT_ACCEPTED
            }
        }
        val pool = CaptureFramePool(releaseHandler = handler)

        pool.offerLatest("old-frame")
        pool.offerLatest("new-frame")
        repeat(3) { pool.retryPendingReleases() }

        assertEquals(4, dispatchAttempts)
        assertEquals(0, pool.snapshot().pendingReleaseCount)
        assertEquals(1, pool.snapshot().unresolvedReleaseCount)
    }

    @Test
    fun acceptedReleaseCompletionResolvesTheReleaseObligation() {
        val handler = object : FrameReleaseHandler<String> {
            override fun dispatch(
                request: FrameReleaseRequest<String>,
                completion: (FrameReleaseCompletion) -> Unit,
            ): ReleaseDispatchResult {
                completion(FrameReleaseCompletion.RELEASED)
                return ReleaseDispatchResult.ACCEPTED
            }
        }
        val pool = CaptureFramePool(releaseHandler = handler)

        pool.offerLatest("old-frame")
        pool.offerLatest("new-frame")

        assertEquals(0, pool.snapshot().unresolvedReleaseCount)
        assertEquals(1, pool.snapshot().completedReleaseCount)
    }

}
