package dev.skw.adapter.outbound.persistence.accesscontrol

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class AccessControlMigrationTest {
    private val migration =
        Path
            .of("src/main/resources/db/migration/V6__create_access_control.sql")
            .toFile()
            .readText()

    @Test
    fun `namespace registration is workspace scoped and deterministic`() {
        assertTrue(migration.contains("CONSTRAINT namespaces_pk PRIMARY KEY (workspace_id, name)"))
        assertTrue(migration.contains("CONSTRAINT namespaces_name_valid CHECK"))
        assertTrue(migration.contains("REFERENCES skw.workspaces (id) ON DELETE RESTRICT"))
    }

    @Test
    fun `policies constrain principals scopes and permissions`() {
        assertTrue(migration.contains("CONSTRAINT access_policies_principal_valid CHECK"))
        assertTrue(migration.contains("CONSTRAINT access_policies_namespace_fk"))
        assertTrue(migration.contains("CONSTRAINT access_policies_permissions_valid CHECK"))
        assertTrue(migration.contains("ARRAY['READ', 'CREATE', 'UPDATE', 'DELETE']::TEXT[]"))
        assertTrue(migration.contains("COALESCE(namespace_name, '')"))
    }
}
