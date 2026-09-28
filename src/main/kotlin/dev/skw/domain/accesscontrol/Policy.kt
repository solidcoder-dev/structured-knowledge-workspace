package dev.skw.domain.accesscontrol

class Policy(
    val principalId: PrincipalId,
    val scope: Scope,
    permissions: Set<Permission>,
) {
    private val grantedPermissions = permissions.toSet()

    init {
        require(grantedPermissions.isNotEmpty()) { "A policy must grant at least one permission" }
    }

    fun allows(principalId: PrincipalId, permission: Permission, scope: Scope): Boolean =
        this.principalId == principalId && this.scope == scope && permission in grantedPermissions
}
