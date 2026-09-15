package dev.skw.adapter.outbound.persistence

import dev.skw.application.entry.RelationshipAlreadyExists
import dev.skw.application.entry.RelationshipEndpointMissing
import dev.skw.application.port.out.RelationshipRepository
import dev.skw.domain.entry.EntryId
import dev.skw.domain.relationship.Relationship
import dev.skw.domain.relationship.RelationshipId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

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
            if (error.message?.contains("relationships_edge_unique") == true) throw RelationshipAlreadyExists()
            throw RelationshipEndpointMissing()
        } ?: error("Relationship insert returned no row")
}
