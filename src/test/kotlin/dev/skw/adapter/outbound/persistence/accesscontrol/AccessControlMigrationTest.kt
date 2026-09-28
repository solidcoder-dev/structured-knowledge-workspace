package dev.skw.adapter.outbound.persistence.accesscontrol

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class AccessControlMigrationTest {
    private val migration = Path.of("src/main/resources/db/migration/V7__fix_access_control_policy_identity.sql").toFile().readText()

    @Test
    fun `namespace registration is workspace scoped and deterministic`() {
        assertTrue(migration.contains("namespaces_name_valid CHECK"))
    }

    @Test
    fun `policies constrain principals scopes and permissions`() {
        assertTrue(migration.contains("UNIQUE NULLS NOT DISTINCT (workspace_id, principal_id, scope_type, namespace)"))
        assertTrue(migration.contains("policy_permissions_identity"))
        assertTrue(migration.contains("policy_permissions_policy_fk"))
    }
}
