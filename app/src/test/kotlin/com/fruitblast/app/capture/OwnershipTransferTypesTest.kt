package com.fruitblast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OwnershipTransferTypesTest {
    @Test
    fun descriptorPreservesEveryIdentityAndAuditField() {
        val reason = QuarantineReason.values().first()
        val descriptor = OwnershipTransferDescriptor(
            transferId = "transfer-001",
            sessionGeneration = 7L,
            resourceId = 41L,
            releaseId = 13L,
            reason = reason,
            estimatedBytes = 4096L,
            createdAtMillis = 123456789L,
        )

        assertEquals("transfer-001", descriptor.transferId)
        assertEquals(7L, descriptor.sessionGeneration)
        assertEquals(41L, descriptor.resourceId)
        assertEquals(13L, descriptor.releaseId)
        assertEquals(reason, descriptor.reason)
        assertEquals(4096L, descriptor.estimatedBytes)
        assertEquals(123456789L, descriptor.createdAtMillis)
    }

    @Test
    fun descriptorUsesValueEqualityAndCopyPreservesIdentity() {
        val reason = QuarantineReason.values().first()
        val original = OwnershipTransferDescriptor(
            transferId = "transfer-002",
            sessionGeneration = 8L,
            resourceId = 42L,
            releaseId = 14L,
            reason = reason,
            estimatedBytes = 8192L,
            createdAtMillis = 987654321L,
        )
        val equivalent = OwnershipTransferDescriptor(
            transferId = "transfer-002",
            sessionGeneration = 8L,
            resourceId = 42L,
            releaseId = 14L,
            reason = reason,
            estimatedBytes = 8192L,
            createdAtMillis = 987654321L,
        )

        assertEquals(original, equivalent)
        assertEquals(original.hashCode(), equivalent.hashCode())
        assertEquals(original, original.copy())
        assertNotEquals(original, original.copy(releaseId = 15L))
    }
}
