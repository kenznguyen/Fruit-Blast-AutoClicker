package com.fruitblast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

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
        assertTrue(intent.payloadHash.matches(Regex("sha256:[0-9a-f]{64}")))
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
    fun releasedStateRequiresMatchingAuthoritativeProof() {
        val intent = preparedIntent()
        val unrelatedProof = verifiedProof(
            descriptor = ownershipDescriptor(resourceId = 99L),
        )

        assertEquals(
            ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.RELEASE_PROOF_REQUIRED),
            intent.transition(
                ReleaseIntentTransition(
                    expectedRevision = 1L,
                    nextState = ReleaseObligationState.RELEASED,
                ),
            ),
        )
        assertEquals(
            ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.RELEASE_PROOF_MISMATCH),
            intent.transition(
                ReleaseIntentTransition(
                    expectedRevision = 1L,
                    nextState = ReleaseObligationState.RELEASED,
                    releaseProof = unrelatedProof,
                ),
            ),
        )
        assertEquals(
            ReleaseIntentTransitionResult.Applied(
                intent.copy(
                    obligationState = ReleaseObligationState.RELEASED,
                    resourceState = ReleaseResourceState.RELEASED,
                    revision = 2L,
                ),
            ),
            intent.transition(
                ReleaseIntentTransition(
                    expectedRevision = 1L,
                    nextState = ReleaseObligationState.RELEASED,
                    releaseProof = verifiedProof(),
                ),
            ),
        )
    }

    @Test
    fun unknownCannotBeResolvedByOrdinaryTransitionEvenWithReleaseProof() {
        val unknown = preparedIntent(
            obligationState = ReleaseObligationState.UNKNOWN,
            resourceState = ReleaseResourceState.UNKNOWN,
        )

        assertEquals(
            ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.INVALID_STATE_TRANSITION),
            unknown.transition(
                ReleaseIntentTransition(
                    expectedRevision = 1L,
                    nextState = ReleaseObligationState.RELEASED,
                    releaseProof = verifiedProof(),
                ),
            ),
        )
    }

    @Test
    fun transitionGraphIsExhaustiveAndFailClosed() {
        val allowed = setOf(
            ReleaseObligationState.PREPARED to ReleaseObligationState.ACCEPTED,
            ReleaseObligationState.PREPARED to ReleaseObligationState.RELEASED,
            ReleaseObligationState.PREPARED to ReleaseObligationState.UNKNOWN,
            ReleaseObligationState.ACCEPTED to ReleaseObligationState.RELEASED,
            ReleaseObligationState.ACCEPTED to ReleaseObligationState.UNKNOWN,
        )
        ReleaseObligationState.values().forEach { from ->
            ReleaseObligationState.values().forEach { to ->
                assertEquals(
                    "Unexpected transition $from -> $to",
                    (from to to) in allowed,
                    from.canTransitionTo(to),
                )
            }
        }
    }

    @Test
    fun payloadHashIsCanonicalDeterministicAndCoversDescriptorIdentity() {
        val descriptor = ownershipDescriptor()
        val first = DurableReleasePayloadHasher.hash(descriptor)
        val second = DurableReleasePayloadHasher.hash(descriptor)

        assertEquals(first, second)
        assertTrue(first.matches(Regex("sha256:[0-9a-f]{64}")))
        assertNotEquals(
            first,
            DurableReleasePayloadHasher.hash(ownershipDescriptor(resourceId = 42L)),
        )
        assertNotEquals(
            first,
            DurableReleasePayloadHasher.hash(ownershipDescriptor(transferId = "capture-frame:7:014")),
        )
    }

    @Test
    fun payloadHashUsesFormatVersionAsDomainSeparation() {
        val descriptor = ownershipDescriptor()

        assertNotEquals(
            DurableReleasePayloadHasher.hash(descriptor, formatVersion = 1),
            DurableReleasePayloadHasher.hash(descriptor, formatVersion = 2),
        )
    }

    @Test
    fun transitionIncrementsRevisionOnlyForStateChanges() {
        val prepared = preparedIntent()
        val accepted = prepared.transition(
            ReleaseIntentTransition(
                expectedRevision = 1L,
                nextState = ReleaseObligationState.ACCEPTED,
            ),
        ) as ReleaseIntentTransitionResult.Applied

        assertEquals(2L, accepted.intent.revision)
        assertEquals(ReleaseResourceState.RUNTIME_OWNED, accepted.intent.resourceState)

        val repeated = accepted.intent.transition(
            ReleaseIntentTransition(
                expectedRevision = 2L,
                nextState = ReleaseObligationState.ACCEPTED,
            ),
        ) as ReleaseIntentTransitionResult.Idempotent

        assertEquals(2L, repeated.intent.revision)
    }

    @Test
    fun transitionRejectsStaleRevisionAndRevisionOverflow() {
        val prepared = preparedIntent()

        assertEquals(
            ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.STALE_REVISION),
            prepared.transition(
                ReleaseIntentTransition(
                    expectedRevision = 0L,
                    nextState = ReleaseObligationState.ACCEPTED,
                ),
            ),
        )

        val maxRevision = prepared.copy(revision = Long.MAX_VALUE)
        assertEquals(
            ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.REVISION_OVERFLOW),
            maxRevision.transition(
                ReleaseIntentTransition(
                    expectedRevision = Long.MAX_VALUE,
                    nextState = ReleaseObligationState.ACCEPTED,
                ),
            ),
        )
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
        assertNotEquals(
            DurableReleaseIntentLookup.NotFoundAfterRecovery,
            DurableReleaseIntentLookup.Unknown,
        )
        assertTrue(DurableReleaseIntentLookup.NotFoundAfterRecovery is DurableReleaseIntentLookup)
        assertTrue(DurableReleaseIntentLookup.Unknown is DurableReleaseIntentLookup)
    }

    private fun ownershipDescriptor(
        transferId: String = "capture-frame:7:13",
        resourceId: Long = 41L,
    ) = OwnershipTransferDescriptor(
        transferId = transferId,
        sessionGeneration = 7L,
        resourceId = resourceId,
        releaseId = 13L,
        reason = QuarantineReason.RELEASE_UNKNOWN,
        estimatedBytes = 4096L,
        createdAtMillis = 123456789L,
    )

    private fun verifiedProof(
        descriptor: OwnershipTransferDescriptor = ownershipDescriptor(),
    ) = VerifiedReleaseProof(
        descriptor = descriptor,
        authorityId = "capture-release-authority",
        evidenceId = "release-event:13",
    )

    private fun preparedIntent(
        descriptor: OwnershipTransferDescriptor = ownershipDescriptor(),
        schemaVersion: Int = 1,
        obligationState: ReleaseObligationState = ReleaseObligationState.PREPARED,
        resourceState: ReleaseResourceState = when (obligationState) {
            ReleaseObligationState.PREPARED -> ReleaseResourceState.CALLER_OWNED
            ReleaseObligationState.ACCEPTED -> ReleaseResourceState.RUNTIME_OWNED
            ReleaseObligationState.RELEASED -> ReleaseResourceState.RELEASED
            ReleaseObligationState.UNKNOWN -> ReleaseResourceState.UNKNOWN
        },
        revision: Long = 1L,
        updatedAtMillis: Long = 123456790L,
        payloadHash: String = DurableReleasePayloadHasher.hash(descriptor),
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
    @Test
    fun durableIntentHasNoPublicConstructionOrCopyBypass() {
        assertTrue(
            "DurableReleaseIntent must expose only controlled factories",
            DurableReleaseIntent::class.java.constructors.none { Modifier.isPublic(it.modifiers) },
        )
        assertTrue(
            "DurableReleaseIntent must not expose a data-class copy bypass",
            DurableReleaseIntent::class.java.methods.none { it.name == "copy" },
        )
    }

    @Test
    fun verifiedReleaseProofHasNoPublicConstructionOrCopyBypass() {
        assertTrue(
            "VerifiedReleaseProof must expose no public constructor",
            VerifiedReleaseProof::class.java.constructors.none { Modifier.isPublic(it.modifiers) },
        )
        assertTrue(
            "VerifiedReleaseProof must not expose a data-class copy bypass",
            VerifiedReleaseProof::class.java.methods.none { it.name == "copy" },
        )
    }

    @Test
    fun durableDecisionPolicyExistsForBehavioralCommitAndLookupResolution() {
        Class.forName("com.fruitblast.app.capture.DurableReleaseIntentDecisionPolicy")
    }

}
