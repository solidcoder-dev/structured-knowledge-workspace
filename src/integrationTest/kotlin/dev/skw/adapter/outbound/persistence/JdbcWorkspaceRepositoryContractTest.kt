package dev.skw.adapter.outbound.persistence

import dev.skw.application.port.out.SaveResult
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
    }
