package dev.skw.adapter.outbound.persistence

import dev.skw.application.port.out.DeleteResult
import dev.skw.application.port.out.SaveResult
import dev.skw.application.workspace.WorkspacePageRequest
import dev.skw.domain.Version
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.Workspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcWorkspaceRepositoryContractTest
    @Autowired
    constructor(
        private val repository: JdbcWorkspaceRepository,
        dataSource: DataSource,
    ) : dev.skw.support.PostgresIntegrationTest() {
        private val jdbc = JdbcTemplate(dataSource)
        private val now = Instant.parse("2026-01-01T00:00:00Z")

        @BeforeEach
        fun clean() {
            jdbc.update("DELETE FROM skw.relationships")
            jdbc.update("DELETE FROM skw.entries")
            jdbc.update("DELETE FROM skw.workspaces")
        }

        @Test
        fun `create find and properties round trip`() {
            val created = repository.save(Workspace.create(mapOf(PropertyName("kind") to PropertyValue.StringValue("capability")), now))
            val found = repository.findById(created.id)!!
            assertEquals(1, found.version.value)
            assertEquals(PropertyValue.StringValue("capability"), found.properties[PropertyName("kind")])
        }

        @Test
        fun `effective update increments version while no-op and stale writes do not`() {
            val created = repository.save(Workspace.create(now = now))
            val changed = created.setProperty(PropertyName("kind"), PropertyValue.BooleanValue(true), now.plusSeconds(1))
            assertEquals(SaveResult.SAVED, repository.saveIfVersion(changed, Version.initial()))
            val current = repository.findById(created.id)!!
            assertEquals(2, current.version.value)
            assertEquals(SaveResult.SAVED, repository.saveIfVersion(current, current.version))
            assertEquals(2, repository.findById(created.id)!!.version.value)
            assertEquals(SaveResult.VERSION_CONFLICT, repository.saveIfVersion(changed, Version.initial()))
        }

        @Test
        fun `delete is conditional and refuses non-empty workspaces`() {
            val empty = repository.save(Workspace.create(now = now))
            assertEquals(DeleteResult.VERSION_CONFLICT, repository.delete(empty.id, Version.of(2)))
            assertEquals(DeleteResult.DELETED, repository.delete(empty.id, empty.version))
            assertEquals(DeleteResult.NOT_FOUND, repository.delete(empty.id, empty.version))

            val nonEmpty = repository.save(Workspace.create(now = now))
            jdbc.update(
                "INSERT INTO skw.entries (workspace_id, id) VALUES (?, ?)",
                nonEmpty.id.value,
                UUID.randomUUID(),
            )
            assertEquals(DeleteResult.NOT_EMPTY, repository.delete(nonEmpty.id, nonEmpty.version))
        }

        @Test
        fun `list is ordered by created time and id with keyset continuation`() {
            val createdAt = java.sql.Timestamp.from(now)
            val ids =
                listOf(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    UUID.fromString("00000000-0000-0000-0000-000000000003"),
                )
            ids.forEach { id ->
                jdbc.update(
                    "INSERT INTO skw.workspaces (id, properties, created_at, updated_at) VALUES (?, '{}'::jsonb, ?, ?)",
                    id,
                    createdAt,
                    createdAt,
                )
            }
            val first = repository.list(WorkspacePageRequest(2))
            assertEquals(ids.take(2), first.items.map { it.id.value })
            val second = repository.list(WorkspacePageRequest(2, first.nextCursor))
            assertEquals(listOf(ids[2]), second.items.map { it.id.value })
            assertEquals(null, second.nextCursor)
            assertEquals(emptySet<UUID>(), first.items.map { it.id.value }.intersect(second.items.map { it.id.value }.toSet()))
        }
    }
