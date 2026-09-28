package dev.skw.application.accesscontrol

import dev.skw.domain.accesscontrol.Namespace
import dev.skw.domain.accesscontrol.Permission
import dev.skw.domain.accesscontrol.PrincipalId
import dev.skw.domain.workspace.WorkspaceId

/** The complete authorization input needed by a single application mutation. */
data class AuthorizationRequest(
    val principal: PrincipalId,
    val workspace: WorkspaceId,
    val permission: Permission,
    val namespace: Namespace? = null,
)

/**
 * Application-facing policy decision port.
 *
 * Implementations return normally when the request is allowed and throw
 * [AuthorizationDenied] when it is not. A null namespace is reserved for
 * workspace-scoped structural operations; a non-null namespace authorizes a
 * namespaced property or relationship operation.
 */
fun interface AuthorizationPort {
    fun authorize(
        principal: PrincipalId,
        workspace: WorkspaceId,
        permission: Permission,
        namespace: Namespace?,
    )
}

fun interface AuthorizeUseCase {
    fun authorize(
        principal: PrincipalId,
        workspace: WorkspaceId,
        permission: Permission,
        namespace: Namespace?,
    )
}

fun AuthorizeUseCase.authorize(
    principal: PrincipalId,
    workspace: WorkspaceId,
    permission: Permission,
) = authorize(principal, workspace, permission, null)

class AuthorizationDenied(
    val principal: PrincipalId,
    val workspace: WorkspaceId,
    val permission: Permission,
    val namespace: Namespace?,
) : RuntimeException(
        buildString {
            append("Authorization denied for ")
            append(permission)
            append(" in workspace ")
            append(workspace)
            namespace?.let { append(" for namespace ").append(it) }
        },
    )

class MissingPrincipal : RuntimeException("A principal is required for this mutation")

class AuthorizationInfrastructureUnavailable : RuntimeException("Authorization infrastructure is unavailable")

fun requirePrincipal(principal: PrincipalId?): PrincipalId = principal ?: throw MissingPrincipal()

/**
 * Small application boundary used by mutation services.
 *
 * It deliberately performs no policy lookup or matching itself; those
 * concerns belong to the injected port implementation.
 */
class AuthorizationService(
    private val authorizationPort: AuthorizationPort,
) : AuthorizeUseCase {
    override fun authorize(
        principal: PrincipalId,
        workspace: WorkspaceId,
        permission: Permission,
        namespace: Namespace?,
    ) = authorizationPort.authorize(principal, workspace, permission, namespace)
}
