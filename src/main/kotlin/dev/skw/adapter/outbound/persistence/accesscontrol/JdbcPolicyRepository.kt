package dev.skw.adapter.outbound.persistence.accesscontrol

import dev.skw.application.port.out.accesscontrol.PolicyRepository
import dev.skw.domain.accesscontrol.Namespace
import dev.skw.domain.accesscontrol.Permission
import dev.skw.domain.accesscontrol.Policy
import dev.skw.domain.accesscontrol.PrincipalId
import dev.skw.domain.accesscontrol.Scope
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class JdbcPolicyRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : PolicyRepository {
    override fun save(
        workspaceId: WorkspaceId,
        policy: Policy,
    ) {
        val namespace = (policy.scope as? Scope.WorkspaceNamespace)?.namespace?.value
        val scopeType = if (namespace == null) "WORKSPACE" else "NAMESPACE"
        jdbc.update(
            """INSERT INTO skw.policies
                   (workspace_id, principal_id, scope_type, namespace)
               VALUES (:workspaceId, :principalId, :scopeType, :namespace)
               ON CONFLICT (workspace_id, principal_id, scope_type)
               DO UPDATE SET namespace = EXCLUDED.namespace""",
            MapSqlParameterSource()
                .addValue("workspaceId", workspaceId.value)
                .addValue("principalId", policy.principalId.value)
                .addValue("scopeType", scopeType)
                .addValue("namespace", namespace),
        )
        jdbc.update(
            "DELETE FROM skw.policy_permissions WHERE workspace_id = :workspaceId AND principal_id = :principalId AND scope_type = :scopeType",
            MapSqlParameterSource()
                .addValue(
                    "workspaceId",
                    workspaceId.value,
                ).addValue("principalId", policy.principalId.value)
                .addValue("scopeType", scopeType),
        )
        Permission.entries.filter { policy.allows(policy.principalId, it, policy.scope) }.forEach { permission ->
            jdbc.update(
                """INSERT INTO skw.policy_permissions
                   (workspace_id, principal_id, scope_type, namespace, permission)
                   VALUES (:workspaceId, :principalId, :scopeType, :namespace, :permission)""",
                MapSqlParameterSource()
                    .addValue("workspaceId", workspaceId.value)
                    .addValue("principalId", policy.principalId.value)
                    .addValue("scopeType", scopeType)
                    .addValue("namespace", namespace)
                    .addValue("permission", permission.name),
            )
        }
    }

    override fun find(
        workspaceId: WorkspaceId,
        principalId: PrincipalId,
    ): List<Policy> =
        jdbc.query(
            """SELECT p.principal_id, p.scope_type, p.namespace,
                      COALESCE(array_agg(pp.permission) FILTER (WHERE pp.permission IS NOT NULL), ARRAY[]::TEXT[]) AS permissions
               FROM skw.policies p
               LEFT JOIN skw.policy_permissions pp ON pp.workspace_id = p.workspace_id
                 AND pp.principal_id = p.principal_id AND pp.scope_type = p.scope_type
                 AND pp.namespace IS NOT DISTINCT FROM p.namespace
               WHERE p.workspace_id = :workspaceId AND p.principal_id = :principalId
               GROUP BY p.principal_id, p.scope_type, p.namespace
               ORDER BY p.namespace NULLS FIRST""",
            MapSqlParameterSource()
                .addValue("workspaceId", workspaceId.value)
                .addValue("principalId", principalId.value),
            ::mapRow,
        )

    private fun mapRow(
        resultSet: ResultSet,
        rowNumber: Int,
    ): Policy {
        val namespace = resultSet.getString("namespace")?.let(::Namespace)
        val permissions =
            (resultSet.getArray("permissions").array as Array<*>)
                .map { Permission.valueOf(it.toString()) }
                .toSet()
        return Policy(
            PrincipalId(resultSet.getString("principal_id")),
            if (namespace == null) Scope.Workspace else Scope.WorkspaceNamespace(namespace),
            permissions,
        )
    }
}
