package dev.shinsou.kmp.rights

import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContentRightsContractTest {
    @Test
    fun everyContentOperationCanBeGrantedIndependently() {
        val scope = scope()

        ContentOperation.entries.forEach { grantedOperation ->
            val authority = InMemoryRightsAuthority()
            val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
            authority.admit(
                grant(
                    reference = reference,
                    scope = scope,
                    operations = setOf(grantedOperation),
                ),
            )

            ContentOperation.entries.forEach { requestedOperation ->
                assertEquals(
                    grantedOperation == requestedOperation,
                    ContentRightsEvaluator.allows(
                        reference,
                        scope,
                        requestedOperation,
                        1,
                        authority,
                    ),
                    "Granting $grantedOperation must not change $requestedOperation",
                )
            }
        }
    }

    @Test
    fun serializedGrantContainsPolicyOnly() {
        val encoded = Json.encodeToJsonElement(
            RightsGrant.serializer(),
            grant(
                reference = RightsGrantRef("55555555-5555-4555-8555-555555555555"),
                scope = scope(),
                operations = setOf(ContentOperation.DISPLAY),
                constraints = setOf(RightsConstraint.WatermarkRequired),
                protectionScheme = ProtectionScheme.Provider("registered-provider", 1),
            ),
        ).jsonObject

        assertEquals(
            setOf(
                "schemaVersion",
                "grantId",
                "scope",
                "provenance",
                "protectionScheme",
                "validFromEpochMillis",
                "validUntilEpochMillis",
                "allowedOperations",
                "constraints",
            ),
            encoded.keys,
        )
        assertFalse(encoded.keys.any { key ->
            key.contains("lease", ignoreCase = true) ||
                key.contains("session", ignoreCase = true) ||
                key.contains("nonce", ignoreCase = true) ||
                key.contains("material", ignoreCase = true)
        })
    }

    @Test
    fun missingUnknownAndUnadmittedGrantsDenyByDefault() {
        val scope = scope()
        val authority = InMemoryRightsAuthority()
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")

        assertEquals(
            RightsDecision.DENY,
            ContentRightsEvaluator.decide(null, scope, ContentOperation.DISPLAY, 10, authority),
        )
        assertFalse(
            ContentRightsEvaluator.allows(reference, scope, ContentOperation.DISPLAY, 10, authority),
        )
    }

    @Test
    fun admittedGrantIsBoundToScopeAndExpiresOrRevokes() {
        val scope = scope()
        val authority = InMemoryRightsAuthority()
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                validFrom = 100,
                validUntil = 200,
                operations = setOf(ContentOperation.DISPLAY),
            ),
        )

        assertTrue(ContentRightsEvaluator.allows(reference, scope, ContentOperation.DISPLAY, 100, authority))
        assertFalse(ContentRightsEvaluator.allows(reference, scope, ContentOperation.COPY, 100, authority))
        assertFalse(ContentRightsEvaluator.allows(reference, scope, ContentOperation.DISPLAY, 200, authority))
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope(contentRevision = 1),
                ContentOperation.DISPLAY,
                100,
                authority,
            ),
        )

        authority.revoke(reference)
        assertFalse(ContentRightsEvaluator.allows(reference, scope, ContentOperation.DISPLAY, 100, authority))
    }

    @Test
    fun admittedGrantDeepSnapshotsMutablePolicySets() {
        val scope = scope()
        val authority = InMemoryRightsAuthority()
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        val operations = mutableSetOf(ContentOperation.DISPLAY)
        val scopedOperations = mutableSetOf(ContentOperation.DISPLAY)
        val constraints = mutableSetOf<RightsConstraint>(
            RightsConstraint.ForOperations(
                operations = scopedOperations,
                constraint = RightsConstraint.WatermarkRequired,
            ),
        )
        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = operations,
                constraints = constraints,
            ),
        )

        operations += ContentOperation.COPY
        scopedOperations.clear()
        constraints.clear()

        assertFalse(ContentRightsEvaluator.allows(reference, scope, ContentOperation.COPY, 1, authority))
        assertFalse(ContentRightsEvaluator.allows(reference, scope, ContentOperation.DISPLAY, 1, authority))
        assertTrue(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.DISPLAY,
                1,
                authority,
                context = RightsOperationContext(watermarkApplied = true),
            ),
        )
    }

    @Test
    fun resolvedGrantRechecksRevocationAndReplacementAtEveryDecision() {
        val scope = scope()
        val authority = InMemoryRightsAuthority()
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = setOf(ContentOperation.DISPLAY),
            ),
        )
        val resolved = assertNotNull(authority.resolve(reference, scope, 1))
        assertTrue(resolved.allows(ContentOperation.DISPLAY, 1))

        authority.revoke(reference)
        assertFalse(resolved.allows(ContentOperation.DISPLAY, 1))

        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = setOf(ContentOperation.DISPLAY),
            ),
        )
        assertFalse(resolved.allows(ContentOperation.DISPLAY, 1))
        assertTrue(assertNotNull(authority.resolve(reference, scope, 1)).allows(ContentOperation.DISPLAY, 1))
    }

    @Test
    fun admittedPolicyIteratorsCannotMutateTheAuthoritySnapshot() {
        val scope = scope()
        val authority = InMemoryRightsAuthority()
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = setOf(ContentOperation.DISPLAY),
                constraints = setOf(
                    RightsConstraint.ForOperations(
                        setOf(ContentOperation.DISPLAY),
                        RightsConstraint.WatermarkRequired,
                    ),
                ),
            ),
        )
        val resolved = assertNotNull(authority.resolve(reference, scope, 1))

        val operationIterator = resolved.grant.allowedOperations.iterator()
        operationIterator.next()
        assertFails { (operationIterator as MutableIterator<ContentOperation>).remove() }

        val scoped = resolved.grant.constraints.single() as RightsConstraint.ForOperations
        val scopedIterator = scoped.operations.iterator()
        scopedIterator.next()
        assertFails { (scopedIterator as MutableIterator<ContentOperation>).remove() }

        assertFalse(resolved.allows(ContentOperation.DISPLAY, 1))
        assertTrue(
            resolved.allows(
                ContentOperation.DISPLAY,
                1,
                context = RightsOperationContext(watermarkApplied = true),
            ),
        )
    }

    @Test
    fun typedConstraintsAreRequiredForTheOperationContext() {
        val scope = scope()
        val authority = InMemoryRightsAuthority()
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = setOf(
                    ContentOperation.OFFLINE_STORE,
                    ContentOperation.TTS,
                    ContentOperation.ANNOTATE,
                ),
                constraints = setOf(
                    RightsConstraint.MaxOfflineBytes(10),
                    RightsConstraint.MaxTextChars(20),
                    RightsConstraint.WatermarkRequired,
                ),
            ),
        )

        assertFalse(ContentRightsEvaluator.allows(reference, scope, ContentOperation.OFFLINE_STORE, 1, authority))
        assertTrue(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.OFFLINE_STORE,
                1,
                authority,
                context = RightsOperationContext(offlineBytes = 10, textCharacters = 20, watermarkApplied = true),
            ),
        )
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.OFFLINE_STORE,
                1,
                authority,
                context = RightsOperationContext(offlineBytes = 11, textCharacters = 20, watermarkApplied = true),
            ),
        )
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.TTS,
                1,
                authority,
                context = RightsOperationContext(offlineBytes = 10, textCharacters = 21, watermarkApplied = true),
            ),
        )
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.ANNOTATE,
                1,
                authority,
                context = RightsOperationContext(offlineBytes = 10, textCharacters = 20),
            ),
        )
    }

    @Test
    fun unsupportedProtectionSchemesFailClosed() {
        val scope = scope()
        val authority = InMemoryRightsAuthority()
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = setOf(ContentOperation.DISPLAY),
                protectionScheme = ProtectionScheme.Provider("provider", 1),
            ),
        )

        assertFalse(ContentRightsEvaluator.allows(reference, scope, ContentOperation.DISPLAY, 1, authority))
    }

    @Test
    fun pluginHintsAreHostOwnedRestrictionsAndCannotElevate() {
        val scope = scope()
        val authority = InMemoryRightsAuthority()
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = setOf(ContentOperation.DISPLAY, ContentOperation.COPY),
            ),
        )

        assertTrue(ContentRightsEvaluator.allows(reference, scope, ContentOperation.DISPLAY, 1, authority))
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.DISPLAY,
                1,
                authority,
                hint = RightsHint(requestedOperations = setOf(ContentOperation.COPY)),
            ),
        )
        assertTrue(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.DISPLAY,
                1,
                authority,
                hint = RightsHint(deniedOperations = setOf(ContentOperation.COPY)),
            ),
        )
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.COPY,
                1,
                authority,
                hint = RightsHint(deniedOperations = setOf(ContentOperation.COPY)),
            ),
        )
        // A plugin request cannot manufacture an operation absent from the host grant.
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.EXPORT,
                1,
                authority,
                hint = RightsHint(requestedOperations = setOf(ContentOperation.EXPORT)),
            ),
        )
        // A protection hint is an additional restriction, not provider evidence.
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.DISPLAY,
                1,
                authority,
                hint = RightsHint(protectionScheme = ProtectionScheme.Provider("plugin", 1)),
            ),
        )

        val constrainedReference = RightsGrantRef("55555555-5555-4555-8555-555555555556")
        authority.admit(
            grant(
                reference = constrainedReference,
                scope = scope,
                operations = setOf(ContentOperation.TTS),
                constraints = setOf(RightsConstraint.MaxTextChars(10)),
            ),
        )
        // A weaker plugin constraint cannot relax the host's stricter limit.
        assertFalse(
            ContentRightsEvaluator.allows(
                constrainedReference,
                scope,
                ContentOperation.TTS,
                1,
                authority,
                context = RightsOperationContext(textCharacters = 11),
                hint = RightsHint(constraints = setOf(RightsConstraint.MaxTextChars(100))),
            ),
        )
    }

    @Test
    fun constraintsApplyOnlyToTheirOperation() {
        val scope = scope()
        val authority = InMemoryRightsAuthority()
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = setOf(
                    ContentOperation.DISPLAY,
                    ContentOperation.OFFLINE_STORE,
                    ContentOperation.SYNC_BLOB,
                ),
                constraints = setOf(RightsConstraint.MaxOfflineBytes(10)),
            ),
        )

        assertTrue(ContentRightsEvaluator.allows(reference, scope, ContentOperation.DISPLAY, 1, authority))
        assertTrue(ContentRightsEvaluator.allows(reference, scope, ContentOperation.SYNC_BLOB, 1, authority))
        assertFalse(ContentRightsEvaluator.allows(reference, scope, ContentOperation.OFFLINE_STORE, 1, authority))
        assertTrue(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.OFFLINE_STORE,
                1,
                authority,
                context = RightsOperationContext(offlineBytes = 10),
            ),
        )
    }

    @Test
    fun registeredProtectionAuthorizationIsBoundToAuthorityScopeOperationAndExpiry() {
        val scope = scope()
        val scheme = ProtectionScheme.Provider("registered-provider", 1)
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        val authority = InMemoryRightsAuthority()
        authority.registerProtectionProvider("registered-provider", 1)
        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = setOf(ContentOperation.DISPLAY),
                protectionScheme = scheme,
            ),
        )

        val legal = assertNotNull(
            authority.issueProtectionAuthorization(scheme, scope, ContentOperation.DISPLAY, 10),
        )
        assertTrue(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.DISPLAY,
                1,
                authority,
                authorization = legal,
            ),
        )

        val foreignAuthority = InMemoryRightsAuthority()
        foreignAuthority.registerProtectionProvider("registered-provider", 1)
        foreignAuthority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = setOf(ContentOperation.DISPLAY),
                protectionScheme = scheme,
            ),
        )
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.DISPLAY,
                1,
                foreignAuthority,
                authorization = legal,
            ),
        )

        val wrongScope = assertNotNull(
            authority.issueProtectionAuthorization(scheme, scope(contentRevision = 1), ContentOperation.DISPLAY, 10),
        )
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.DISPLAY,
                1,
                authority,
                authorization = wrongScope,
            ),
        )
        val wrongOperation = assertNotNull(
            authority.issueProtectionAuthorization(scheme, scope, ContentOperation.COPY, 10),
        )
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.DISPLAY,
                1,
                authority,
                authorization = wrongOperation,
            ),
        )
        val expired = assertNotNull(
            authority.issueProtectionAuthorization(scheme, scope, ContentOperation.DISPLAY, 2),
        )
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.DISPLAY,
                2,
                authority,
                authorization = expired,
            ),
        )
    }

    @Test
    fun resolvedProtectedGrantFailsClosedAfterProviderIsUnregistered() {
        val scope = scope()
        val scheme = ProtectionScheme.Provider("registered-provider", 1)
        val reference = RightsGrantRef("55555555-5555-4555-8555-555555555555")
        val authority = InMemoryRightsAuthority()
        authority.registerProtectionProvider("registered-provider", 1)
        authority.admit(
            grant(
                reference = reference,
                scope = scope,
                operations = setOf(ContentOperation.DISPLAY),
                protectionScheme = scheme,
            ),
        )
        val authorization = assertNotNull(
            authority.issueProtectionAuthorization(scheme, scope, ContentOperation.DISPLAY, 10),
        )
        val resolved = assertNotNull(authority.resolve(reference, scope, 1, authorization))
        assertTrue(resolved.allows(ContentOperation.DISPLAY, 1))

        authority.unregisterProtectionProvider("registered-provider", 1)

        assertFalse(resolved.allows(ContentOperation.DISPLAY, 1))
        assertFalse(
            ContentRightsEvaluator.allows(
                reference,
                scope,
                ContentOperation.DISPLAY,
                1,
                authority,
                authorization,
            ),
        )
    }

    private fun grant(
        reference: RightsGrantRef,
        scope: RightsScope,
        validFrom: Long = 0,
        validUntil: Long? = null,
        operations: Set<ContentOperation>,
        constraints: Set<RightsConstraint> = emptySet(),
        protectionScheme: ProtectionScheme = ProtectionScheme.None,
    ): RightsGrant = RightsGrant(
        schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
        grantId = reference,
        scope = scope,
        provenance = RightsProvenance.HostPolicy("test-policy"),
        protectionScheme = protectionScheme,
        validFromEpochMillis = validFrom,
        validUntilEpochMillis = validUntil,
        allowedOperations = operations,
        constraints = constraints,
    )

    private fun scope(contentRevision: Long? = 0): RightsScope {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        return RightsScope(
            publicationId = publication,
            acquisitionId = "33333333-3333-4333-8333-333333333333",
            unitId = UnitKey(publication, "22222222-2222-4222-8222-222222222222"),
            contentRevision = contentRevision,
        )
    }
}
