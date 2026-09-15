package dev.skw.adapter.outbound.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.entry.EntryCursor
import dev.skw.application.entry.EntryPage
import dev.skw.application.port.out.EntryDeleteResult
import dev.skw.application.port.out.EntryPageRequest
import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.SaveResult
import dev.skw.domain.Version
import dev.skw.domain.entry.Entry
import dev.skw.domain.entry.EntryId
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class JdbcEntryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    objectMapper: ObjectMapper,
) : EntryRepository {
    private val json = PropertyJsonMapper(objectMapper)
    private val objectMapper = objectMapper

    override fun save(entry: Entry) =
        jdbc.queryForObject(
            """INSERT INTO skw.entries (workspace_id, properties) VALUES (:workspaceId, CAST(:properties AS jsonb)) RETURNING *""",
            MapSqlParameterSource().addValue("workspaceId", entry.workspaceId.value).addValue("properties", json.toJson(entry.properties)),
            ::mapRow,
        )!!

    override fun findById(
        workspaceId: WorkspaceId,
        entryId: EntryId,
    ) = jdbc
        .query(
            "SELECT * FROM skw.entries WHERE workspace_id = :workspaceId AND id = :entryId",
            MapSqlParameterSource().addValue("workspaceId", workspaceId.value).addValue("entryId", entryId.value),
            ::mapRow,
        ).firstOrNull()

    override fun findExistingIds(
        workspaceId: WorkspaceId,
        ids: Set<EntryId>,
    ): Set<EntryId> {
        if (ids.isEmpty()) return emptySet()
        return jdbc
            .queryForList(
                "SELECT id FROM skw.entries WHERE workspace_id = :workspaceId AND id IN (:ids)",
                MapSqlParameterSource().addValue("workspaceId", workspaceId.value).addValue("ids", ids.map { it.value }),
            ).map { it["id"] as java.util.UUID }
            .map(::EntryId)
            .toSet()
    }

    override fun saveIfVersion(
        entry: Entry,
        expectedVersion: Version,
    ): SaveResult {
        val updated =
            jdbc.update(
                """UPDATE skw.entries SET properties = CAST(:properties AS jsonb), version = version + 1, updated_at = statement_timestamp()
            WHERE workspace_id = :workspaceId AND id = :entryId AND version = :version
            AND properties IS DISTINCT FROM CAST(:properties AS jsonb)""",
                MapSqlParameterSource()
                    .addValue(
                        "workspaceId",
                        entry.workspaceId.value,
                    ).addValue(
                        "entryId",
                        entry.id.value,
                    ).addValue("version", expectedVersion.value)
                    .addValue("properties", json.toJson(entry.properties)),
            )
        if (updated == 1) return SaveResult.SAVED
        val current = findById(entry.workspaceId, entry.id) ?: return SaveResult.NOT_FOUND
        return if (current.version == expectedVersion) SaveResult.SAVED else SaveResult.VERSION_CONFLICT
    }

    override fun delete(
        workspaceId: WorkspaceId,
        entryId: EntryId,
        expectedVersion: Version,
    ): EntryDeleteResult {
        val deleted =
            jdbc.update(
                """DELETE FROM skw.entries e WHERE e.workspace_id = :workspaceId AND e.id = :entryId AND e.version = :version
            AND NOT EXISTS (SELECT 1 FROM skw.relationships r WHERE r.workspace_id = e.workspace_id AND (r.source_entry_id = e.id OR r.target_entry_id = e.id))""",
                MapSqlParameterSource()
                    .addValue(
                        "workspaceId",
                        workspaceId.value,
                    ).addValue("entryId", entryId.value)
                    .addValue("version", expectedVersion.value),
            )
        if (deleted == 1) return EntryDeleteResult.DELETED
        val current = findById(workspaceId, entryId) ?: return EntryDeleteResult.NOT_FOUND
        if (current.version != expectedVersion) return EntryDeleteResult.VERSION_CONFLICT
        return if (jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM skw.relationships WHERE workspace_id = :workspaceId AND (source_entry_id = :entryId OR target_entry_id = :entryId))",
                MapSqlParameterSource().addValue("workspaceId", workspaceId.value).addValue("entryId", entryId.value),
                Boolean::class.java,
            ) ==
            true
        ) {
            EntryDeleteResult.CONNECTED
        } else {
            EntryDeleteResult.VERSION_CONFLICT
        }
    }

    override fun list(request: EntryPageRequest): EntryPage {
        val params = MapSqlParameterSource().addValue("workspaceId", request.workspaceId.value).addValue("limit", request.limit + 1)
        val after =
            request.after
                ?.also {
                    params.addValue("afterCreatedAt", it.createdAt).addValue("afterId", it.entryId.value)
                }?.let { "AND (created_at, id) > (:afterCreatedAt, :afterId)" }
                ?: ""
        val rows =
            jdbc.query(
                "SELECT * FROM skw.entries WHERE workspace_id = :workspaceId $after ORDER BY created_at ASC, id ASC LIMIT :limit",
                params,
                ::mapRow,
            )
        val items = rows.take(request.limit)
        return EntryPage(
            items,
            if (rows.size >
                request.limit
            ) {
                items.lastOrNull()?.let { EntryCursor(it.workspaceId, it.createdAt, it.id) }
            } else {
                null
            },
        )
    }

    private fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ) = Entry.restore(
        EntryId(rs.getObject("id", java.util.UUID::class.java)),
        WorkspaceId(rs.getObject("workspace_id", java.util.UUID::class.java)),
        json.toDomain(objectMapper.readTree(rs.getString("properties"))),
        Version.of(rs.getLong("version")),
        rs.getObject("created_at", java.time.OffsetDateTime::class.java).toInstant(),
        rs.getObject("updated_at", java.time.OffsetDateTime::class.java).toInstant(),
    )
}
