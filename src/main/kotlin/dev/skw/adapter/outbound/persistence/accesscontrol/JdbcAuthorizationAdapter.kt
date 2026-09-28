package dev.skw.adapter.outbound.persistence.accesscontrol

import dev.skw.application.accesscontrol.AuthorizationPort
import dev.skw.application.port.out.accesscontrol.PolicyRepository
import dev.skw.domain.accesscontrol.Authorizer
import dev.skw.domain.accesscontrol.Namespace
import dev.skw.domain.accesscontrol.Permission
import dev.skw.domain.accesscontrol.PrincipalId
import dev.skw.domain.accesscontrol.Scope
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.stereotype.Component

@Component
class JdbcAuthorizationAdapter(
    private val policies: PolicyRepository,
) : AuthorizationPort {
    override fun authorize(
        principal: PrincipalId,
        workspace: WorkspaceId,
        permission: Permission,
        namespace: Namespace?,
    ) {
        val scope = namespace?.let(Scope::WorkspaceNamespace) ?: Scope.Workspace
        if (!Authorizer(policies.find(workspace, principal)).isAllowed(principal, permission, scope)) {
            throw dev.skw.application.accesscontrol.AuthorizationDenied(principal, workspace, permission, namespace)
        }
    }
}
