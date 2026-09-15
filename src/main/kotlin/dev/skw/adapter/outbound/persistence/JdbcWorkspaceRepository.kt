package dev.skw.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.port.out.DeleteResult
import dev.skw.application.port.out.SaveResult
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.domain.Version
import dev.skw.domain.workspace.Workspace
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class JdbcWorkspaceRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    objectMapper: ObjectMapper,
) : WorkspaceRepository {
    private val json = PropertyJsonMapper(objectMapper)
    private val objectMapper = objectMapper

    override fun save(workspace: Workspace): Workspace =
        jdbc.queryForObject(
            """INSERT INTO skw.workspaces (properties) VALUES (CAST(:properties AS jsonb)) RETURNING *""",
            MapSqlParameterSource("properties", json.toJson(workspace.properties)),
            ::mapRow,
        )!!

    override fun findById(id: WorkspaceId): Workspace? =
        jdbc
            .query(
                "SELECT * FROM skw.workspaces WHERE id = :id",
                MapSqlParameterSource("id", id.value),
                ::mapRow,
            ).firstOrNull()

    override fun saveIfVersion(
        workspace: Workspace,
        expectedVersion: Version,
    ): SaveResult {
        val updated =
            jdbc.update(
                """UPDATE skw.workspaces
               SET properties = CAST(:properties AS jsonb), version = version + 1, updated_at = statement_timestamp()
               WHERE id = :id AND version = :version
                 AND properties IS DISTINCT FROM CAST(:properties AS jsonb)""",
                MapSqlParameterSource()
                    .addValue("properties", json.toJson(workspace.properties))
                    .addValue("id", workspace.id.value)
                    .addValue("version", expectedVersion.value),
            )
        if (updated == 1) return SaveResult.SAVED
        val current = findById(workspace.id) ?: return SaveResult.NOT_FOUND
        return if (current.version == expectedVersion) SaveResult.SAVED else SaveResult.VERSION_CONFLICT
    }

    override fun delete(
        id: WorkspaceId,
        expectedVersion: Version,
    ): DeleteResult {
        val current = findById(id) ?: return DeleteResult.NOT_FOUND
        if (current.version != expectedVersion) return DeleteResult.VERSION_CONFLICT
        if (jdbc.queryForObject(
                "SELECT count(*) FROM skw.entries WHERE workspace_id = :id",
                MapSqlParameterSource("id", id.value),
                Long::class.java,
            )!! >
            0
        ) {
            return DeleteResult.NOT_EMPTY
        }
        jdbc.update(
            "DELETE FROM skw.workspaces WHERE id = :id AND version = :version",
            MapSqlParameterSource().addValue("id", id.value).addValue("version", expectedVersion.value),
        )
        return DeleteResult.DELETED
    }

    private fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): Workspace =
        Workspace.restore(
            WorkspaceId(rs.getObject("id", java.util.UUID::class.java)),
            json.toDomain(objectMapper.readTree(rs.getString("properties"))),
            Version.of(rs.getLong("version")),
            rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
            rs.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant(),
        )
}
