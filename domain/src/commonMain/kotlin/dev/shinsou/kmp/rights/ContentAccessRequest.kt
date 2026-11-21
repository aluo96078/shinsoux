package dev.shinsou.kmp.rights

import dev.shinsou.kmp.domain.model.RightsGrantRef

/**
 * Complete host-owned input for one protected content operation.
 *
 * The caller may describe the resource and constraints, but only `HostContentOperationGate`
 * resolves the current grant.  Keeping the authority outside this value prevents a plugin from
 * supplying its own permissive evaluator.
 */
public data class ContentAccessRequest(
    val grantReference: RightsGrantRef?,
    val scope: RightsScope,
    val context: RightsOperationContext = RightsOperationContext(),
    val hint: RightsHint? = null,
    val protectionAuthorization: ProtectionAuthorization? = null,
) {
    init {
        scope.validate()
        grantReference?.validate()
        hint?.validate()
    }
}
