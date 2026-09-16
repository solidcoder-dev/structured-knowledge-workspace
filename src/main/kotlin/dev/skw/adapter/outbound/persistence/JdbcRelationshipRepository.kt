package dev.skw.adapter.outbound.persistence

import dev.skw.application.port.out.RelationshipRepository
import dev.skw.application.relationship.RelationshipAlreadyExists
import dev.skw.application.relationship.RelationshipDirection
import dev.skw.application.relationship.RelationshipEndpointMissing
import dev.skw.application.relationship.RelationshipPage
import dev.skw.application.relationship.RelationshipPageRequest
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.Relationship
import dev.skw.domain.relationship.RelationshipId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class JdbcRelationshipRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : RelationshipRepository {
    override fun create(
        workspaceId: WorkspaceId,
        sourceEntryId: EntryId,
        targetEntryId: EntryId,
        type: RelationshipType,
    ): Relationship =
        try {
            jdbc.queryForObject(
                """INSERT INTO skw.relationships (workspace_id, source_entry_id, target_entry_id, type)
            VALUES (:workspaceId, :source, :target, :type) RETURNING id, created_at""",
                MapSqlParameterSource()
                    .addValue(
                        "workspaceId",
                        workspaceId.value,
                    ).addValue("source", sourceEntryId.value)
                    .addValue("target", targetEntryId.value)
                    .addValue("type", type.value),
            ) { rs, _ ->
                Relationship(
                    RelationshipId(rs.getObject("id", java.util.UUID::class.java)),
                    workspaceId,
                    sourceEntryId,
                    targetEntryId,
                    type,
                    rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
                )
            }
        } catch (error: DataIntegrityViolationException) {
            when (constraintName(error)) {
                "relationships_edge_unique" -> throw RelationshipAlreadyExists()
                "relationships_source_fk", "relationships_target_fk" -> throw RelationshipEndpointMissing()
                else -> throw error
            }
        } ?: error("Relationship insert returned no row")

    override fun findById(
        workspaceId: WorkspaceId,
        relationshipId: RelationshipId,
    ): Relationship? =
        jdbc
            .query(
                "SELECT * FROM skw.relationships WHERE workspace_id = :workspaceId AND id = :relationshipId",
                MapSqlParameterSource().addValue("workspaceId", workspaceId.value).addValue("relationshipId", relationshipId.value),
                ::mapRow,
            ).firstOrNull()

    override fun delete(
        workspaceId: WorkspaceId,
        relationshipId: RelationshipId,
    ): Boolean =
        jdbc.update(
            "DELETE FROM skw.relationships WHERE workspace_id = :workspaceId AND id = :relationshipId",
            MapSqlParameterSource().addValue("workspaceId", workspaceId.value).addValue("relationshipId", relationshipId.value),
        ) == 1

    override fun listForEntry(request: RelationshipPageRequest): RelationshipPage {
        val parameters =
            MapSqlParameterSource()
                .addValue("workspaceId", request.workspaceId.value)
                .addValue("entryId", request.entryId.value)
                .addValue("limit", request.limit + 1)
        val direction =
            when (request.direction) {
                RelationshipDirection.INCOMING -> "r.target_entry_id = :entryId"
                RelationshipDirection.OUTGOING -> "r.source_entry_id = :entryId"
                RelationshipDirection.BOTH -> "(r.source_entry_id = :entryId OR r.target_entry_id = :entryId)"
            }
        val type =
            request.type?.let {
                parameters.addValue("type", it.value)
                "AND r.type = :type"
            } ?: ""
        val after =
            request.after?.let {
                parameters.addValue("afterCreatedAt", it.createdAt).addValue("afterId", it.relationshipId.value)
                "AND (r.created_at, r.id) > (:afterCreatedAt, :afterId)"
            } ?: ""
        val rows =
            jdbc.query(
                """SELECT r.* FROM skw.relationships r
                   WHERE r.workspace_id = :workspaceId AND $direction $type $after
                   ORDER BY r.created_at ASC, r.id ASC
                   LIMIT :limit""",
                parameters,
                ::mapRow,
            )
        val items = rows.take(request.limit)
        return RelationshipPage(
            items,
            if (rows.size > request.limit) {
                items.lastOrNull()?.let { RelationshipCursorFactory.from(request, it) }
            } else {
                null
            },
        )
    }

    private fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): Relationship =
        Relationship(
            RelationshipId(rs.getObject("id", java.util.UUID::class.java)),
            WorkspaceId(rs.getObject("workspace_id", java.util.UUID::class.java)),
            EntryId(rs.getObject("source_entry_id", java.util.UUID::class.java)),
            EntryId(rs.getObject("target_entry_id", java.util.UUID::class.java)),
            RelationshipType(rs.getString("type")),
            rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
        )

    private fun constraintName(error: DataIntegrityViolationException): String? {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is org.postgresql.util.PSQLException) return cause.serverErrorMessage?.constraint
            cause = cause.cause
        }
        return null
    }
}

private object RelationshipCursorFactory {
    fun from(
        request: RelationshipPageRequest,
        relationship: Relationship,
    ) = dev.skw.application.relationship.RelationshipCursor(
        request.workspaceId,
        request.entryId,
        request.direction,
        request.type,
        relationship.createdAt,
        relationship.id,
    )
}
