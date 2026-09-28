package dev.skw.adapter.outbound.persistence.accesscontrol

import dev.skw.application.port.out.accesscontrol.NamespaceRegistrationResult
import dev.skw.application.port.out.accesscontrol.NamespaceRepository
import dev.skw.domain.accesscontrol.Namespace
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcNamespaceRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : NamespaceRepository {
    override fun register(
        workspaceId: WorkspaceId,
        namespace: Namespace,
    ): NamespaceRegistrationResult =
        try {
            jdbc.update(
                "INSERT INTO skw.namespaces (workspace_id, name) VALUES (:workspaceId, :name)",
                parameters(workspaceId, namespace),
            )
            NamespaceRegistrationResult.REGISTERED
        } catch (_: DuplicateKeyException) {
            NamespaceRegistrationResult.ALREADY_REGISTERED
        }

    override fun list(workspaceId: WorkspaceId): List<Namespace> =
        jdbc.query(
            """SELECT name FROM skw.namespaces
               WHERE workspace_id = :workspaceId
               ORDER BY registered_at ASC, name ASC""",
            MapSqlParameterSource("workspaceId", workspaceId.value),
        ) { resultSet, _ -> Namespace(resultSet.getString("name")) }

    private fun parameters(
        workspaceId: WorkspaceId,
        namespace: Namespace,
    ) = MapSqlParameterSource()
        .addValue("workspaceId", workspaceId.value)
        .addValue("name", namespace.value)
}
