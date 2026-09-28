package dev.skw.domain.accesscontrol

class Authorizer(policies: Iterable<Policy>) {
    private val policies = policies.toList()

    fun isAllowed(principalId: PrincipalId, permission: Permission, scope: Scope): Boolean =
        policies.any { it.allows(principalId, permission, scope) }
}
