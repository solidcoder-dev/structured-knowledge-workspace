package dev.skw.application.accesscontrol

import dev.skw.domain.accesscontrol.Namespace
import dev.skw.domain.accesscontrol.Permission
import dev.skw.domain.accesscontrol.PrincipalId
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class AuthorizationServiceTest {
    private val principal = PrincipalId("principal-1")
    private val workspace = WorkspaceId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val alpha = Namespace("alpha")

    @Test
    fun `structural authorization forwards a workspace request without a namespace`() {
        val port = RecordingAuthorizationPort()

        AuthorizationService(port).authorize(principal, workspace, Permission.CREATE)

        assertEquals(
            AuthorizationRequest(principal, workspace, Permission.CREATE, null),
            port.requests.single(),
        )
    }

    @Test
    fun `namespaced authorization forwards the namespace unchanged`() {
        val port = RecordingAuthorizationPort()

        AuthorizationService(port).authorize(principal, workspace, Permission.UPDATE, alpha)

        assertEquals(
            AuthorizationRequest(principal, workspace, Permission.UPDATE, alpha),
            port.requests.single(),
        )
    }

    @Test
    fun `a denied request is exposed as an authorization failure`() {
        val denial = AuthorizationDenied(principal, workspace, Permission.DELETE, alpha)
        val port = RecordingAuthorizationPort(denial)

        val thrown = assertThrows(AuthorizationDenied::class.java) {
            AuthorizationService(port).authorize(principal, workspace, Permission.DELETE, alpha)
        }

        assertEquals(denial, thrown)
    }

    private class RecordingAuthorizationPort(
        private val failure: AuthorizationDenied? = null,
    ) : AuthorizationPort {
        val requests = mutableListOf<AuthorizationRequest>()

        override fun authorize(
            principal: PrincipalId,
            workspace: WorkspaceId,
            permission: Permission,
            namespace: Namespace?,
        ) {
            requests += AuthorizationRequest(principal, workspace, permission, namespace)
            failure?.let { throw it }
        }
    }
}
