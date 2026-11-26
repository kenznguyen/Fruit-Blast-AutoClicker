package com.fruitblast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableReleaseIntentContractsTest {
    @Test
    fun preparedIntentPreservesIdentityAndDurableMetadata() {
        val descriptor = ownershipDescriptor()
        val intent = preparedIntent(descriptor = descriptor)

        assertEquals(descriptor, intent.descriptor)
        assertEquals(1, intent.schemaVersion)
        assertEquals(ReleaseObligationState.PREPARED, intent.obligationState)
        assertEquals(ReleaseResourceState.CALLER_OWNED, intent.resourceState)
        assertEquals(1L, intent.revision)
        assertEquals(123456790L, intent.updatedAtMillis)
        assertEquals("sha256:0123456789abcdef", intent.payloadHash)
    }

    @Test
    fun preparedIntentRejectsInvalidDurableMetadata() {
        assertInvalid { preparedIntent(schemaVersion = 0) }
        assertInvalid { preparedIntent(revision = 0L) }
        assertInvalid { preparedIntent(updatedAtMillis = -1L) }
        assertInvalid { preparedIntent(payloadHash = " ") }
    }

    @Test
    fun obligationAndResourceStatesMustDescribeTheSameCustodyPhase() {
        assertInvalid {
            preparedIntent(
                obligationState = ReleaseObligationState.PREPARED,
                resourceState = ReleaseResourceState.RUNTIME_OWNED,
            )
        }
        assertInvalid {
            preparedIntent(
                obligationState = ReleaseObligationState.ACCEPTED,
                resourceState = ReleaseResourceState.CALLER_OWNED,
            )
        }
        assertInvalid {
            preparedIntent(
                obligationState = ReleaseObligationState.RELEASED,
                resourceState = ReleaseResourceState.UNKNOWN,
            )
        }
        assertInvalid {
            preparedIntent(
                obligationState = ReleaseObligationState.UNKNOWN,
                resourceState = ReleaseResourceState.RUNTIME_OWNED,
            )
        }
    }

    @Test
    fun releaseIntentTransitionsAreMonotonicAndFailClosed() {
        assertTrue(ReleaseObligationState.PREPARED.canTransitionTo(ReleaseObligationState.ACCEPTED))
        assertTrue(ReleaseObligationState.PREPARED.canTransitionTo(ReleaseObligationState.RELEASED))
        assertTrue(ReleaseObligationState.PREPARED.canTransitionTo(ReleaseObligationState.UNKNOWN))
        assertTrue(ReleaseObligationState.ACCEPTED.canTransitionTo(ReleaseObligationState.RELEASED))
        assertTrue(ReleaseObligationState.ACCEPTED.canTransitionTo(ReleaseObligationState.UNKNOWN))

        assertFalse(ReleaseObligationState.ACCEPTED.canTransitionTo(ReleaseObligationState.PREPARED))
        assertFalse(ReleaseObligationState.RELEASED.canTransitionTo(ReleaseObligationState.PREPARED))
        assertFalse(ReleaseObligationState.RELEASED.canTransitionTo(ReleaseObligationState.UNKNOWN))
        assertFalse(ReleaseObligationState.UNKNOWN.canTransitionTo(ReleaseObligationState.ACCEPTED))
        assertFalse(ReleaseObligationState.UNKNOWN.canTransitionTo(ReleaseObligationState.RELEASED))
    }

    @Test
    fun commitOutcomesKeepAmbiguityDistinctFromConfirmedRollback() {
        assertEquals(
            setOf(
                DurableLedgerCommitResult.COMMITTED,
                DurableLedgerCommitResult.NOT_COMMITTED,
                DurableLedgerCommitResult.UNKNOWN,
                DurableLedgerCommitResult.CONTRACT_VIOLATION,
            ),
            DurableLedgerCommitResult.values().toSet(),
        )
    }

    @Test
    fun lookupDistinguishesRecoveredAbsenceFromUnknownStorageOutcome() {
        assertTrue(DurableReleaseIntentLookup.NotFoundAfterRecovery is DurableReleaseIntentLookup)
        assertTrue(DurableReleaseIntentLookup.Unknown is DurableReleaseIntentLookup)
        assertFalse(
            DurableReleaseIntentLookup.NotFoundAfterRecovery ==
                DurableReleaseIntentLookup.Unknown,
        )
    }

    private fun ownershipDescriptor() = OwnershipTransferDescriptor(
        transferId = "capture-frame:7:13",
        sessionGeneration = 7L,
        resourceId = 41L,
        releaseId = 13L,
        reason = QuarantineReason.RELEASE_UNKNOWN,
        estimatedBytes = 4096L,
        createdAtMillis = 123456789L,
    )

    private fun preparedIntent(
        descriptor: OwnershipTransferDescriptor = ownershipDescriptor(),
        schemaVersion: Int = 1,
        obligationState: ReleaseObligationState = ReleaseObligationState.PREPARED,
        resourceState: ReleaseResourceState = ReleaseResourceState.CALLER_OWNED,
        revision: Long = 1L,
        updatedAtMillis: Long = 123456790L,
        payloadHash: String = "sha256:0123456789abcdef",
    ) = DurableReleaseIntent(
        descriptor = descriptor,
        schemaVersion = schemaVersion,
        obligationState = obligationState,
        resourceState = resourceState,
        revision = revision,
        updatedAtMillis = updatedAtMillis,
        payloadHash = payloadHash,
    )

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected contract validation failure.
        }
    }
}
