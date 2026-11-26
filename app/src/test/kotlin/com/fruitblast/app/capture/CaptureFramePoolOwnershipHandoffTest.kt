package com.fruitblast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T04 RED contract tests.
 *
 * The constructor parameters and reconciliation entry point exercised here are
 * the proposed T04 test-facing API:
 * - ownershipSink
 * - metadataProvider
 * - clockMillis
 * - reconcileOwnershipTransfers()
 *
 * Production implementation is intentionally absent in this RED commit.
 */
class CaptureFramePoolOwnershipHandoffTest {
    private class RecordingSink(
        var acceptResult: OwnershipTransferResult = OwnershipTransferResult.ACCEPTED,
        var queryResult: OwnershipTransferStatus = OwnershipTransferStatus.UNKNOWN,
    ) : ReleaseOwnershipSink<String> {
        val descriptors = mutableListOf<OwnershipTransferDescriptor>()
        val resources = mutableListOf<String>()
        val queries = mutableListOf<String>()

        override fun acceptOwnership(
            descriptor: OwnershipTransferDescriptor,
            resource: String,
        ): OwnershipTransferResult {
            descriptors += descriptor
            resources += resource
            return acceptResult
        }

        override fun queryTransfer(transferId: String): OwnershipTransferStatus {
            queries += transferId
            return queryResult
        }
    }

    private class FixedMetadataProvider(
        var metadata: FrameResourceMetadata = FrameResourceMetadata(
            resourceId = 7L,
            estimatedBytes = 128L,
        ),
    ) : FrameResourceMetadataProvider<String> {
        var calls = 0
        var failure: RuntimeException? = null

        override fun describe(resource: String): FrameResourceMetadata {
            calls += 1
            failure?.let { throw it }
            return metadata
        }
    }

    private fun handler(
        outcome: FrameReleaseCompletion = FrameReleaseCompletion.UNKNOWN,
        dispatchResult: ReleaseDispatchResult = ReleaseDispatchResult.ACCEPTED,
        throwOnDispatch: Boolean = false,
        deferCompletion: (((FrameReleaseCompletion) -> Unit) -> Unit)? = null,
        attempts: (() -> Unit)? = null,
    ) = object : FrameReleaseHandler<String> {
        override fun dispatch(
            request: FrameReleaseRequest<String>,
            completion: (FrameReleaseCompletion) -> Unit,
        ): ReleaseDispatchResult {
            attempts?.invoke()
            if (throwOnDispatch) error("dispatch outcome is unknown")
            if (deferCompletion != null) deferCompletion(completion)
            else completion(outcome)
            return dispatchResult
        }
    }

    private fun pool(
        sink: RecordingSink,
        metadata: FixedMetadataProvider = FixedMetadataProvider(),
        releaseHandler: FrameReleaseHandler<String> = handler(),
        generation: Long = 42L,
    ): CaptureFramePool<String> = CaptureFramePool<String>(
        releaseHandler = releaseHandler,
        sessionGeneration = generation,
        ownershipSink = sink,
        metadataProvider = metadata,
        clockMillis = { 1234L },
    )

    private fun triggerRelease(pool: CaptureFramePool<String>) {
        pool.offerLatest("resource")
        pool.offerLatest("replacement")
    }

    @Test fun transferRequiresPositiveSessionGeneration() {
        val sink = RecordingSink()
        val metadata = FixedMetadataProvider()
        assertTrue(runCatching { pool(sink, metadata, generation = 0L) }.isFailure)
        assertTrue(sink.descriptors.isEmpty())
    }

    @Test fun descriptorUsesProviderResourceId() {
        val sink = RecordingSink()
        pool(sink).also(::triggerRelease)
        assertEquals(7L, sink.descriptors.single().resourceId)
    }

    @Test fun descriptorUsesProviderEstimatedBytes() {
        val sink = RecordingSink()
        val metadata = FixedMetadataProvider(FrameResourceMetadata(19L, 4096L))
        pool(sink, metadata).also(::triggerRelease)
        assertEquals(4096L, sink.descriptors.single().estimatedBytes)
    }

    @Test fun metadataProviderIsCalledOnlyOnceForOneReleaseObligation() {
        val sink = RecordingSink(OwnershipTransferResult.REJECTED_NOT_ACCEPTED)
        val metadata = FixedMetadataProvider()
        val pool = pool(sink, metadata)
        triggerRelease(pool)
        repeat(3) { pool.retryRejectedOwnershipTransfers() }
        assertEquals(1, metadata.calls)
    }

    @Test fun invalidMetadataDoesNotCallRegistryOrReleaseResource() {
        val sink = RecordingSink()
        val metadata = FixedMetadataProvider(FrameResourceMetadata(0L, -1L))
        var releases = 0
        val releaseHandler = handler(attempts = { releases++ })
        val pool = pool(sink, metadata, releaseHandler)
        triggerRelease(pool)
        assertTrue(sink.descriptors.isEmpty())
        assertEquals(1, releases)
    }

    @Test fun metadataProviderExceptionRetainsPoolCustody() {
        val sink = RecordingSink()
        val metadata = FixedMetadataProvider().apply {
            failure = IllegalStateException("metadata unavailable")
        }
        val pool = pool(sink, metadata)
        triggerRelease(pool)
        assertTrue(sink.descriptors.isEmpty())
        assertEquals(1, pool.snapshot().unresolvedReleaseCount)
    }

    @Test fun retryUsesStableTransferId() {
        val sink = RecordingSink(OwnershipTransferResult.REJECTED_NOT_ACCEPTED)
        val pool = pool(sink)
        triggerRelease(pool)
        repeat(3) { pool.retryRejectedOwnershipTransfers() }
        assertTrue(sink.descriptors.map { it.transferId }.distinct().size == 1)
    }

    @Test fun reconciliationUsesOriginalImmutableDescriptor() {
        val sink = RecordingSink(
            acceptResult = OwnershipTransferResult.UNKNOWN,
            queryResult = OwnershipTransferStatus.UNKNOWN,
        )
        val pool = pool(sink)
        triggerRelease(pool)
        val original = sink.descriptors.single()
        pool.reconcileOwnershipTransfers()
        assertEquals(listOf(original.transferId), sink.queries)
        sink.queryResult = OwnershipTransferStatus.NOT_COMMITTED
        pool.reconcileOwnershipTransfers()
        assertSame(original, sink.descriptors.last())
        assertEquals(original, sink.descriptors.last())
    }

    @Test fun acceptedTransferRelinquishesPoolCustodyExactlyOnce() {
        val sink = RecordingSink(OwnershipTransferResult.ACCEPTED)
        var releases = 0
        val pool = pool(sink, releaseHandler = handler(attempts = { releases++ }))
        triggerRelease(pool)
        assertEquals(1, sink.resources.size)
        assertEquals(1, releases)
    }

    @Test fun alreadyAcceptedTransferRelinquishesPoolCustodyExactlyOnce() {
        val sink = RecordingSink(OwnershipTransferResult.ALREADY_ACCEPTED)
        val pool = pool(sink)
        triggerRelease(pool)
        assertEquals(1, sink.resources.size)
        assertEquals(1, sink.descriptors.size)
    }

    @Test fun rejectedTransferRetainsResourceInPoolCustody() {
        val sink = RecordingSink(OwnershipTransferResult.REJECTED_NOT_ACCEPTED)
        val pool = pool(sink)
        triggerRelease(pool)
        assertEquals(1, pool.snapshot().pendingOwnershipTransferCount)
    }

    @Test fun unknownTransferDoesNotRelinquishCustody() {
        val sink = RecordingSink(OwnershipTransferResult.UNKNOWN)
        val pool = pool(sink)
        triggerRelease(pool)
        assertEquals(1, pool.snapshot().unresolvedOwnershipTransferCount)
    }

    @Test fun transferInFlightDoesNotReleaseResourceFromEitherOwner() {
        val sink = RecordingSink()
        var callback: ((FrameReleaseCompletion) -> Unit)? = null
        val pool = pool(sink, releaseHandler = handler(
            deferCompletion = { callback = it },
        ))
        triggerRelease(pool)
        assertTrue(sink.resources.isEmpty())
        assertNotNull(callback)
    }

    @Test fun poolCloseTransfersOnlyTheRemainingSlotAfterFirstTransferCommits() {
        val sink = RecordingSink(OwnershipTransferResult.ACCEPTED)
        var releaseCount = 0
        val pool = pool(sink, releaseHandler = handler(
            outcome = FrameReleaseCompletion.UNKNOWN,
            attempts = { releaseCount++ },
        ))
        triggerRelease(pool)
        assertEquals(listOf("resource"), sink.resources)

        pool.close()

        assertEquals(2, releaseCount)
        assertEquals(listOf("resource", "replacement"), sink.resources)
        assertEquals(2, sink.descriptors.map { it.transferId }.distinct().size)
    }

    @Test fun duplicateReconciliationDoesNotCreateSecondTransferObligation() {
        val sink = RecordingSink(OwnershipTransferResult.UNKNOWN, OwnershipTransferStatus.UNKNOWN)
        val pool = pool(sink)
        triggerRelease(pool)
        pool.reconcileOwnershipTransfers()
        pool.reconcileOwnershipTransfers()
        assertEquals(1, sink.descriptors.map { it.transferId }.distinct().size)
    }

    @Test fun registryBudgetRejectionDoesNotLoseOrReleaseResource() {
        val sink = RecordingSink(OwnershipTransferResult.REJECTED_NOT_ACCEPTED)
        var released = 0
        val pool = pool(sink, releaseHandler = handler(attempts = { released++ }))
        triggerRelease(pool)
        assertEquals(1, released)
        assertEquals(1, pool.snapshot().pendingOwnershipTransferCount)
    }

    @Test fun unknownThenQueryAcceptedMovesCustodyToRegistry() {
        val sink = RecordingSink(OwnershipTransferResult.UNKNOWN, OwnershipTransferStatus.ACCEPTED)
        val pool = pool(sink)
        triggerRelease(pool)
        pool.reconcileOwnershipTransfers()
        assertEquals(1, sink.queries.size)
        assertEquals(1, sink.resources.size)
    }

    @Test fun unknownThenQueryNotCommittedRetriesSameDescriptor() {
        val sink = RecordingSink(OwnershipTransferResult.UNKNOWN, OwnershipTransferStatus.NOT_COMMITTED)
        val pool = pool(sink)
        triggerRelease(pool)
        val first = sink.descriptors.single()
        pool.reconcileOwnershipTransfers()
        assertEquals(first, sink.descriptors.last())
        assertEquals(first.transferId, sink.descriptors.last().transferId)
    }

    @Test fun unknownThenQueryUnknownKeepsResourceFrozen() {
        val sink = RecordingSink(OwnershipTransferResult.UNKNOWN, OwnershipTransferStatus.UNKNOWN)
        val pool = pool(sink)
        triggerRelease(pool)
        pool.reconcileOwnershipTransfers()
        assertEquals(1, pool.snapshot().unresolvedOwnershipTransferCount)
    }

    @Test fun repeatedUnknownReconciliationDoesNotCreateUnboundedTransferAttempts() {
        val sink = RecordingSink(OwnershipTransferResult.UNKNOWN, OwnershipTransferStatus.UNKNOWN)
        val pool = pool(sink)
        triggerRelease(pool)
        repeat(5) { pool.reconcileOwnershipTransfers() }
        assertTrue(sink.queries.size <= 5)
        assertEquals(1, sink.descriptors.map { it.transferId }.distinct().size)
    }

    @Test fun lateReleaseCallbackAfterTransferCommitCannotReleaseTwice() {
        val sink = RecordingSink(OwnershipTransferResult.ACCEPTED)
        var completion: ((FrameReleaseCompletion) -> Unit)? = null
        var releases = 0
        val pool = pool(sink, releaseHandler = handler(
            deferCompletion = { completion = it },
            attempts = { releases++ },
        ))
        triggerRelease(pool)
        completion?.invoke(FrameReleaseCompletion.UNKNOWN)
        completion?.invoke(FrameReleaseCompletion.RELEASED)
        assertEquals(1, sink.resources.size)
        assertEquals(1, releases)
    }

    @Test fun duplicateReleaseCallbackCannotChangeCommittedCustody() {
        val sink = RecordingSink(OwnershipTransferResult.ACCEPTED)
        var completion: ((FrameReleaseCompletion) -> Unit)? = null
        val pool = pool(sink, releaseHandler = handler(deferCompletion = { completion = it }))
        triggerRelease(pool)
        completion?.invoke(FrameReleaseCompletion.UNKNOWN)
        completion?.invoke(FrameReleaseCompletion.UNKNOWN)
        assertEquals(1, sink.resources.size)
    }

    @Test fun unknownCompletionIsHandoffEligibleAfterCallbackQuiescence() {
        val sink = RecordingSink()
        val pool = pool(sink, releaseHandler = handler(outcome = FrameReleaseCompletion.UNKNOWN))
        triggerRelease(pool)
        assertEquals(1, sink.resources.size)
    }

    @Test fun failedCompletionIsHandoffEligibleAfterCallbackQuiescence() {
        val sink = RecordingSink()
        val pool = pool(sink, releaseHandler = handler(outcome = FrameReleaseCompletion.FAILED))
        triggerRelease(pool)
        assertEquals(1, sink.resources.size)
    }

    @Test fun dispatchExceptionWithoutQuiescenceDoesNotHandoff() {
        val sink = RecordingSink()
        val pool = pool(sink, releaseHandler = handler(throwOnDispatch = true))
        triggerRelease(pool)
        assertTrue(sink.resources.isEmpty())
    }

    @Test fun dispatchWithoutResultDoesNotHandoff() {
        val sink = RecordingSink()
        var completion: ((FrameReleaseCompletion) -> Unit)? = null
        val pool = pool(sink, releaseHandler = handler(deferCompletion = { completion = it }))
        triggerRelease(pool)
        assertTrue(sink.resources.isEmpty())
        assertNotNull(completion)
    }

    @Test fun acceptedDispatchWithoutCompletionRemainsActiveAndDoesNotHandoff() {
        val sink = RecordingSink()
        val pool = pool(sink, releaseHandler = handler(deferCompletion = {}))
        triggerRelease(pool)
        assertTrue(sink.resources.isEmpty())
        assertEquals(1, pool.snapshot().activeReleaseCount)
    }

    @Test fun acceptedReleaseCompletionDoesNotHandoff() {
        val sink = RecordingSink()
        val pool = pool(sink, releaseHandler = handler(outcome = FrameReleaseCompletion.RELEASED))
        triggerRelease(pool)
        assertTrue(sink.resources.isEmpty())
    }

    @Test fun exhaustedConfirmedRejectionsBecomeHandoffEligible() {
        val sink = RecordingSink()
        val rejectingHandler = object : FrameReleaseHandler<String> {
            override fun dispatch(
                request: FrameReleaseRequest<String>,
                completion: (FrameReleaseCompletion) -> Unit,
            ): ReleaseDispatchResult = ReleaseDispatchResult.REJECTED_NOT_ACCEPTED
        }
        val pool = pool(sink, releaseHandler = rejectingHandler)
        triggerRelease(pool)
        repeat(3) { pool.retryPendingReleases() }
        assertEquals(1, sink.resources.size)
    }

    @Test fun activeLeasePreventsReleaseAndHandoffUntilLeaseCloses() {
        val sink = RecordingSink()
        val pool = pool(sink)
        pool.offerLatest("leased")
        val lease = pool.acquireLatest()
        pool.close()
        assertTrue(sink.resources.isEmpty())
        lease?.close()
        assertEquals(1, sink.resources.size)
    }

    @Test fun latestFrameBehaviorIsPreserved() {
        val sink = RecordingSink()
        val pool = pool(sink)
        pool.offerLatest("first")
        pool.offerLatest("second")
        val lease = pool.acquireLatest()
        assertEquals("second", lease?.useValue { it })
        lease?.close()
    }

    @Test fun singleSlotBackpressureBehaviorIsPreserved() {
        val sink = RecordingSink()
        val pool = pool(sink)
        pool.offerLatest("leased")
        val lease = pool.acquireLatest()
        assertEquals(FramePublishResult.BACKPRESSURE, pool.offerLatest("blocked"))
        lease?.close()
    }

    @Test fun frameLeaseDefersReleaseUntilActiveReaderCompletes() {
        val sink = RecordingSink()
        val pool = pool(sink)
        pool.offerLatest("frame")
        val lease = pool.acquireLatest()
        var entered = false
        lease?.useValue {
            entered = true
            lease.close()
            assertTrue(sink.resources.isEmpty())
        }
        assertTrue(entered)
    }

    @Test fun poolCloseDoesNotDiscardUnresolvedCustody() {
        val sink = RecordingSink()
        val pool = pool(sink, releaseHandler = handler(deferCompletion = {}))
        triggerRelease(pool)
        pool.close()
        assertTrue(sink.resources.isEmpty())
        assertEquals(2, pool.snapshot().activeReleaseCount)
    }

    @Test fun legacyConstructorRetainsExistingReleaseBehavior() {
        val released = mutableListOf<String>()
        val pool = CaptureFramePool<String> { released += it }
        pool.offerLatest("old")
        pool.offerLatest("new")
        assertEquals(listOf("old"), released)
        pool.close()
        assertEquals(listOf("old", "new"), released)
    }

    @Test fun absentOwnershipSinkDoesNotEnableOwnershipTransfer() {
        val released = mutableListOf<String>()
        val pool = CaptureFramePool<String>(
            releaseHandler = handler(outcome = FrameReleaseCompletion.UNKNOWN),
            sessionGeneration = 42L,
        )
        triggerRelease(pool)
        assertEquals(1, pool.snapshot().unresolvedReleaseCount)
        assertTrue(released.isEmpty())
    }
}
