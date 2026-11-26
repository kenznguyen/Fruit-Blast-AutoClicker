package com.fruitblast.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class DurableReleaseIntentContractsTest {

    @Test
    fun durableIntentHasNoPublicConstructionOrCopyBypass() {
        assertTrue(DurableReleaseIntent::class.java.constructors.none { Modifier.isPublic(it.modifiers) })
        assertTrue(DurableReleaseIntent::class.java.methods.none { it.name == "copy" })
    }

    @Test
    fun verifiedReleaseProofHasNoPublicConstructionOrCopyBypass() {
        assertTrue(VerifiedReleaseProof::class.java.constructors.none { Modifier.isPublic(it.modifiers) })
        assertTrue(VerifiedReleaseProof::class.java.methods.none { it.name == "copy" })
    }

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
                trustedVerifier(),
            ),
        )
        val released = intent.transition(
            ReleaseIntentTransition(
                expectedRevision = 1L,
                nextState = ReleaseObligationState.RELEASED,
                releaseProof = verifiedProof(),
            ),
            trustedVerifier(),
        ) as ReleaseIntentTransitionResult.Applied
        assertEquals(ReleaseObligationState.RELEASED, released.intent.obligationState)
        assertEquals(ReleaseResourceState.RELEASED, released.intent.resourceState)
        assertEquals(2L, released.intent.revision)
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
                trustedVerifier(),
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

        val maxRevision = DurableReleaseIntent.restore(
            descriptor = prepared.descriptor,
            schemaVersion = prepared.schemaVersion,
            obligationState = prepared.obligationState,
            resourceState = prepared.resourceState,
            revision = Long.MAX_VALUE,
            updatedAtMillis = prepared.updatedAtMillis,
            payloadHash = prepared.payloadHash,
            payloadFormatVersion = prepared.payloadFormatVersion,
        )
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
        authorityId: String = "capture-release-authority",
        evidenceId: String = "release-event:13",
    ) = VerifiedReleaseProof.issuedByAuthority(
        descriptor = descriptor,
        authorityId = authorityId,
        evidenceId = evidenceId,
    )

    private fun trustedVerifier() = ReleaseProofVerifier { proof, descriptor ->
        proof.descriptor == descriptor &&
            proof.authorityId == "capture-release-authority" &&
            proof.evidenceId.startsWith("release-event:")
    }

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
    ) = DurableReleaseIntent.restore(
        descriptor = descriptor,
        schemaVersion = schemaVersion,
        obligationState = obligationState,
        resourceState = resourceState,
        revision = revision,
        updatedAtMillis = updatedAtMillis,
        payloadHash = payloadHash,
        releaseProof = if (obligationState == ReleaseObligationState.RELEASED) verifiedProof(descriptor) else null,
        proofVerifier = if (obligationState == ReleaseObligationState.RELEASED) trustedVerifier() else null,
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
    fun durableIntentFactoriesAndProofVerificationFailClosed() {
        val prepared = DurableReleaseIntent.createPrepared(
            descriptor = ownershipDescriptor(),
            updatedAtMillis = 123456790L,
        )
        assertEquals(ReleaseObligationState.PREPARED, prepared.obligationState)

        assertInvalid {
            DurableReleaseIntent.restore(
                descriptor = prepared.descriptor,
                schemaVersion = 1,
                obligationState = ReleaseObligationState.RELEASED,
                resourceState = ReleaseResourceState.RELEASED,
                revision = 2L,
                updatedAtMillis = prepared.updatedAtMillis,
                payloadHash = prepared.payloadHash,
            )
        }

        val forgedProof = verifiedProof(authorityId = "untrusted-authority")
        assertEquals(
            ReleaseIntentTransitionResult.Rejected(ReleaseIntentTransitionRejection.RELEASE_PROOF_MISMATCH),
            prepared.transition(
                ReleaseIntentTransition(
                    expectedRevision = 1L,
                    nextState = ReleaseObligationState.RELEASED,
                    releaseProof = forgedProof,
                ),
                trustedVerifier(),
            ),
        )
    }

    @Test
    fun commitPolicyRequiresRecoveryEvidenceAndNeverAuthorizesResourceRelease() {
        assertEquals(
            DurableLedgerCommitDecision.Committed,
            DurableReleaseIntentDecisionPolicy.resolveCommit(DurableLedgerCommitResult.COMMITTED),
        )
        assertEquals(
            DurableLedgerCommitDecision.PreserveCustody,
            DurableReleaseIntentDecisionPolicy.resolveCommit(DurableLedgerCommitResult.NOT_COMMITTED),
        )
        assertEquals(
            DurableLedgerCommitDecision.NotCommittedConfirmed,
            DurableReleaseIntentDecisionPolicy.resolveCommit(
                DurableLedgerCommitResult.NOT_COMMITTED,
                rollbackEvidenceConfirmed = true,
            ),
        )
        assertEquals(
            DurableLedgerCommitDecision.PreserveCustody,
            DurableReleaseIntentDecisionPolicy.resolveCommit(DurableLedgerCommitResult.UNKNOWN),
        )
        assertEquals(
            DurableLedgerCommitDecision.StopRetry,
            DurableReleaseIntentDecisionPolicy.resolveCommit(DurableLedgerCommitResult.CONTRACT_VIOLATION),
        )
    }

    @Test
    fun lookupPolicyKeepsUnknownDistinctFromRecoveredAbsence() {
        val intent = preparedIntent()
        assertEquals(
            DurableLedgerLookupDecision.Found(intent),
            DurableReleaseIntentDecisionPolicy.resolveLookup(DurableReleaseIntentLookup.Found(intent)),
        )
        assertEquals(
            DurableLedgerLookupDecision.AbsenceConfirmed,
            DurableReleaseIntentDecisionPolicy.resolveLookup(DurableReleaseIntentLookup.NotFoundAfterRecovery),
        )
        assertEquals(
            DurableLedgerLookupDecision.PreserveCustody,
            DurableReleaseIntentDecisionPolicy.resolveLookup(DurableReleaseIntentLookup.Unknown),
        )
    }

}
