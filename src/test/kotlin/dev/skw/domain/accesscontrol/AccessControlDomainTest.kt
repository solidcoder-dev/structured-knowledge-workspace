package dev.skw.domain.accesscontrol

import dev.skw.domain.property.PropertyName
import dev.skw.domain.relationship.RelationshipType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AccessControlDomainTest {
    private val principal = PrincipalId("principal-1")
    private val alpha = Namespace("alpha")
    private val beta = Namespace("beta")

    @Test
    fun `namespace is derived from property and relationship identifiers`() {
        assertTrue(alpha.matches(PropertyName("alpha.status")))
        assertTrue(alpha.matches(RelationshipType("alpha.references")))
        assertFalse(beta.matches(PropertyName("alpha.status")))
        assertThrows<IllegalArgumentException> { Namespace("Bad") }
    }

    @Test
    fun `principal and permission values reject invalid input`() {
        assertThrows<IllegalArgumentException> { PrincipalId("") }
        assertThrows<IllegalArgumentException> { PrincipalId(" ") }
        assertThrows<IllegalArgumentException> { Permission.valueOf("ADMIN") }
    }

    @Test
    fun `policy authorizes only its principal scope and permission`() {
        val policy = Policy(principal, Scope.WorkspaceNamespace(alpha), setOf(Permission.READ, Permission.CREATE))

        assertTrue(policy.allows(principal, Permission.CREATE, Scope.WorkspaceNamespace(alpha)))
        assertFalse(policy.allows(principal, Permission.DELETE, Scope.WorkspaceNamespace(alpha)))
        assertFalse(policy.allows(principal, Permission.CREATE, Scope.WorkspaceNamespace(beta)))
        assertFalse(policy.allows(PrincipalId("other"), Permission.CREATE, Scope.WorkspaceNamespace(alpha)))
        assertFalse(policy.allows(principal, Permission.CREATE, Scope.Workspace))
    }

    @Test
    fun `authorization denies by default and keeps workspace structural scope separate`() {
        val authorizer =
            Authorizer(
                listOf(Policy(principal, Scope.Workspace, setOf(Permission.CREATE, Permission.DELETE))),
            )

        assertTrue(authorizer.isAllowed(principal, Permission.CREATE, Scope.Workspace))
        assertFalse(authorizer.isAllowed(principal, Permission.CREATE, Scope.WorkspaceNamespace(alpha)))
        assertFalse(authorizer.isAllowed(PrincipalId("unknown"), Permission.READ, Scope.Workspace))
    }

    @Test
    fun `one principal can hold independent alpha beta and workspace policies`() {
        val policies =
            Authorizer(
                listOf(
                    Policy(principal, Scope.WorkspaceNamespace(alpha), setOf(Permission.CREATE, Permission.UPDATE)),
                    Policy(principal, Scope.WorkspaceNamespace(beta), setOf(Permission.READ)),
                    Policy(principal, Scope.Workspace, setOf(Permission.CREATE)),
                ),
            )
        assertTrue(policies.isAllowed(principal, Permission.CREATE, Scope.WorkspaceNamespace(alpha)))
        assertTrue(policies.isAllowed(principal, Permission.READ, Scope.WorkspaceNamespace(beta)))
        assertTrue(policies.isAllowed(principal, Permission.CREATE, Scope.Workspace))
        assertFalse(policies.isAllowed(principal, Permission.UPDATE, Scope.WorkspaceNamespace(beta)))
    }
}
