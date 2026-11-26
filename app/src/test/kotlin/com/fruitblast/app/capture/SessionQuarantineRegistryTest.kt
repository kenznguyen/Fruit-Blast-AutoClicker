package com.fruitblast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionQuarantineRegistryTest {
    private fun descriptor(
        transferId: String,
        generation: Long = 1L,
        resourceId: Long = 1L,
        releaseId: Long = 1L,
        estimatedBytes: Long = 1L,
    ) = OwnershipTransferDescriptor(
        transferId = transferId,
        sessionGeneration = generation,
        resourceId = resourceId,
        releaseId = releaseId,
        reason = QuarantineReason.OWNERSHIP_TRANSFER_UNKNOWN,
        estimatedBytes = estimatedBytes,
        createdAtMillis = 100L,
    )

    @Test
    fun acceptsCustodyAndReportsCommittedTransfer() {
        val registry = SessionQuarantineRegistry<Any>()
        val resource = Any()
        val descriptor = descriptor("transfer-1")

        assertEquals(
            OwnershipTransferResult.ACCEPTED,
            registry.acceptOwnership(descriptor, resource),
        )
        assertEquals(
            OwnershipTransferStatus.ACCEPTED,
            registry.queryTransfer("transfer-1"),
        )
        assertSame(resource, registry.resourceFor("transfer-1"))
    }

    @Test
    fun sameTransferAndDescriptorIsIdempotent() {
        val registry = SessionQuarantineRegistry<Any>()
        val resource = Any()
        val descriptor = descriptor("transfer-1")

        assertEquals(
            OwnershipTransferResult.ACCEPTED,
            registry.acceptOwnership(descriptor, resource),
        )
        assertEquals(
            OwnershipTransferResult.ALREADY_ACCEPTED,
            registry.acceptOwnership(descriptor, resource),
        )
        assertSame(resource, registry.resourceFor("transfer-1"))
    }

    @Test
    fun sameTransferIdWithDifferentDescriptorIsRejectedWithoutReplacingCustody() {
        val registry = SessionQuarantineRegistry<Any>()
        val resource = Any()
        val original = descriptor("transfer-1")

        assertEquals(
            OwnershipTransferResult.ACCEPTED,
            registry.acceptOwnership(original, resource),
        )
        assertEquals(
            OwnershipTransferResult.REJECTED_NOT_ACCEPTED,
            registry.acceptOwnership(
                descriptor("transfer-1", releaseId = 2L),
                Any(),
            ),
        )
        assertSame(resource, registry.resourceFor("transfer-1"))
        assertEquals(
            OwnershipTransferStatus.ACCEPTED,
            registry.queryTransfer("transfer-1"),
        )
    }

    @Test
    fun oneResourceCannotBeAcceptedUnderTwoUnresolvedTransferIds() {
        val registry = SessionQuarantineRegistry<Any>()
        val resource = Any()

        assertEquals(
            OwnershipTransferResult.ACCEPTED,
            registry.acceptOwnership(descriptor("transfer-1"), resource),
        )
        assertEquals(
            OwnershipTransferResult.REJECTED_NOT_ACCEPTED,
            registry.acceptOwnership(
                descriptor("transfer-2", resourceId = 2L, releaseId = 2L),
                resource,
            ),
        )
        assertSame(resource, registry.resourceFor("transfer-1"))
        assertEquals(null, registry.resourceFor("transfer-2"))
    }

    @Test
    fun enforcesThirtyTwoMibQuarantineByteBudgetAtBoundary() {
        val registry = SessionQuarantineRegistry<Any>()
        assertEquals(
            OwnershipTransferResult.ACCEPTED,
            registry.acceptOwnership(
                descriptor("at-limit", estimatedBytes = 32L * 1024L * 1024L),
                Any(),
            ),
        )
        assertEquals(
            OwnershipTransferResult.REJECTED_NOT_ACCEPTED,
            registry.acceptOwnership(
                descriptor("over-limit", resourceId = 2L, releaseId = 2L, estimatedBytes = 1L),
                Any(),
            ),
        )
    }

    @Test
    fun enforcesMaximumOfTwoQuarantinedSessionGenerations() {
        val registry = SessionQuarantineRegistry<Any>()
        assertEquals(
            OwnershipTransferResult.ACCEPTED,
            registry.acceptOwnership(descriptor("g1", generation = 1L), Any()),
        )
        assertEquals(
            OwnershipTransferResult.ACCEPTED,
            registry.acceptOwnership(
                descriptor("g2", generation = 2L, resourceId = 2L, releaseId = 2L),
                Any(),
            ),
        )
        assertEquals(
            OwnershipTransferResult.REJECTED_NOT_ACCEPTED,
            registry.acceptOwnership(
                descriptor("g3", generation = 3L, resourceId = 3L, releaseId = 3L),
                Any(),
            ),
        )
    }

    @Test
    fun enforcesMaximumOfEightPendingReleaseObligations() {
        val registry = SessionQuarantineRegistry<Any>()
        repeat(8) { index ->
            assertEquals(
                OwnershipTransferResult.ACCEPTED,
                registry.acceptOwnership(
                    descriptor(
                        transferId = "transfer-$index",
                        resourceId = index + 1L,
                        releaseId = index + 1L,
                    ),
                    Any(),
                ),
            )
        }
        assertEquals(
            OwnershipTransferResult.REJECTED_NOT_ACCEPTED,
            registry.acceptOwnership(
                descriptor("transfer-overflow", resourceId = 9L, releaseId = 9L),
                Any(),
            ),
        )
    }

    @Test
    fun unknownTransferIsReportedNotCommitted() {
        val registry = SessionQuarantineRegistry<Any>()
        assertEquals(
            OwnershipTransferStatus.NOT_COMMITTED,
            registry.queryTransfer("never-submitted"),
        )
    }

    @Test
    fun concurrentDuplicateAcceptanceCommitsCustodyOnlyOnce() {
        val registry = SessionQuarantineRegistry<Any>()
        val descriptor = descriptor("concurrent-transfer")
        val resource = Any()
        val workers = 12
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val alreadyAccepted = AtomicInteger()
        val executor = Executors.newFixedThreadPool(workers)

        try {
            repeat(workers) {
                executor.execute {
                    ready.countDown()
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    when (registry.acceptOwnership(descriptor, resource)) {
                        OwnershipTransferResult.ACCEPTED -> accepted.incrementAndGet()
                        OwnershipTransferResult.ALREADY_ACCEPTED -> alreadyAccepted.incrementAndGet()
                        else -> error("Unexpected transfer result")
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, accepted.get())
        assertEquals(workers - 1, alreadyAccepted.get())
        assertSame(resource, registry.resourceFor("concurrent-transfer"))
    }
}
