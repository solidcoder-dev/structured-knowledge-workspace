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
        jdbc.update(
            """INSERT INTO skw.access_policies
                   (workspace_id, principal_id, namespace_name, permissions)
               VALUES (:workspaceId, :principalId, :namespaceName, CAST(:permissions AS TEXT[]))
               ON CONFLICT (workspace_id, principal_id, (COALESCE(namespace_name, '')))
               DO UPDATE SET permissions = EXCLUDED.permissions""",
            MapSqlParameterSource()
                .addValue("workspaceId", workspaceId.value)
                .addValue("principalId", policy.principalId.value)
                .addValue("namespaceName", namespace)
                .addValue("permissions", postgresTextArray(policy)),
        )
    }

    override fun find(
        workspaceId: WorkspaceId,
        principalId: PrincipalId,
    ): List<Policy> =
        jdbc.query(
            """SELECT principal_id, namespace_name, permissions
               FROM skw.access_policies
               WHERE workspace_id = :workspaceId AND principal_id = :principalId
               ORDER BY namespace_name NULLS FIRST""",
            MapSqlParameterSource()
                .addValue("workspaceId", workspaceId.value)
                .addValue("principalId", principalId.value),
            ::mapRow,
        )

    private fun postgresTextArray(policy: Policy): String =
        Permission.entries
            .filter { policy.allows(policy.principalId, it, policy.scope) }
            .sortedBy { it.name }
            .joinToString(prefix = "{", postfix = "}") { "\"${it.name}\"" }

    private fun mapRow(
        resultSet: ResultSet,
        rowNumber: Int,
    ): Policy {
        val namespace = resultSet.getString("namespace_name")?.let(::Namespace)
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
