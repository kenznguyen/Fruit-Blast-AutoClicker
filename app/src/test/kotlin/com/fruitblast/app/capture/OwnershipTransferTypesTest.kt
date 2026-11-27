package com.fruitblast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OwnershipTransferTypesTest {
    @Test
    fun quarantineReasonsExposeTheApprovedClassification() {
        assertEquals(
            setOf(
                QuarantineReason.RELEASE_FAILED,
                QuarantineReason.RELEASE_UNKNOWN,
                QuarantineReason.EXECUTOR_REJECTED,
                QuarantineReason.OWNERSHIP_TRANSFER_UNKNOWN,
                QuarantineReason.OWNERSHIP_CONTRACT_VIOLATION,
            ),
            QuarantineReason.values().toSet(),
        )
    }

    @Test
    fun descriptorPreservesEveryIdentityAndAuditField() {
        val descriptor = descriptor()

        assertEquals("transfer-001", descriptor.transferId)
        assertEquals(7L, descriptor.sessionGeneration)
        assertEquals(41L, descriptor.resourceId)
        assertEquals(13L, descriptor.releaseId)
        assertEquals(QuarantineReason.RELEASE_FAILED, descriptor.reason)
        assertEquals(4096L, descriptor.estimatedBytes)
        assertEquals(123456789L, descriptor.createdAtMillis)
    }

    @Test
    fun descriptorUsesValueEqualityAndCopyPreservesIdentity() {
        val original = descriptor()
        val equivalent = descriptor()

        assertEquals(original, equivalent)
        assertEquals(original.hashCode(), equivalent.hashCode())
        assertEquals(original, original.copy())
        assertNotEquals(original, original.copy(releaseId = 15L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun descriptorRejectsBlankTransferId() {
        descriptor(transferId = "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun descriptorRejectsNonPositiveSessionGeneration() {
        descriptor(sessionGeneration = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun descriptorRejectsNonPositiveResourceId() {
        descriptor(resourceId = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun descriptorRejectsNonPositiveReleaseId() {
        descriptor(releaseId = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun descriptorRejectsNegativeEstimatedBytes() {
        descriptor(estimatedBytes = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun descriptorRejectsNegativeCreationTime() {
        descriptor(createdAtMillis = -1L)
    }

    private fun descriptor(
        transferId: String = "transfer-001",
        sessionGeneration: Long = 7L,
        resourceId: Long = 41L,
        releaseId: Long = 13L,
        estimatedBytes: Long = 4096L,
        createdAtMillis: Long = 123456789L,
    ) = OwnershipTransferDescriptor(
        transferId = transferId,
        sessionGeneration = sessionGeneration,
        resourceId = resourceId,
        releaseId = releaseId,
        reason = QuarantineReason.RELEASE_FAILED,
        estimatedBytes = estimatedBytes,
        createdAtMillis = createdAtMillis,
    )
}
