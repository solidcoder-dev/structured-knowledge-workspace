package dev.skw.adapter.outbound.persistence

import dev.skw.support.PostgresIntegrationTest
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import javax.sql.DataSource

class SchemaMigrationTest @Autowired constructor(
    dataSource: DataSource,
    private val flyway: Flyway,
    transactionManager: PlatformTransactionManager,
) : PostgresIntegrationTest() {
    private val jdbc = JdbcTemplate(dataSource)
    private val transaction = TransactionTemplate(transactionManager)

    private fun workspace(): UUID =
        jdbc.queryForObject("INSERT INTO skw.workspaces DEFAULT VALUES RETURNING id", UUID::class.java)!!

    private fun entry(workspace: UUID, properties: String = "{}"): UUID =
        jdbc.queryForObject(
            "INSERT INTO skw.entries (workspace_id, properties) VALUES (?, ?::jsonb) RETURNING id",
            UUID::class.java, workspace, properties,
        )!!

    private fun relationship(workspace: UUID, source: UUID, target: UUID, type: String = "supports"): UUID =
        jdbc.queryForObject(
            """INSERT INTO skw.relationships (workspace_id, source_entry_id, target_entry_id, type)
               VALUES (?, ?, ?, ?) RETURNING id""",
            UUID::class.java, workspace, source, target, type,
        )!!

    @Test
    fun `migrations apply once and their checksums validate`() {
        flyway.validate()
        assertEquals(0, flyway.info().pending().size)
        assertEquals(0, flyway.migrate().migrationsExecuted)
        assertEquals(
            listOf("1", "2", "3"),
            flyway.info().applied().mapNotNull { it.version?.version },
        )
    }

    @Test
    fun `valid properties and homogeneous arrays round trip`() {
        val workspace = workspace()
        val json = """{"kind":"capability","name":"Record expense","enabled":true,"amount":12.30,
            "labels":["mobile","finance"],"numbers":[1,2.5],"flags":[true,false],"empty":[]}"""
        val id = entry(workspace, json)
        assertTrue(jdbc.queryForObject(
            "SELECT properties = ?::jsonb FROM skw.entries WHERE workspace_id = ? AND id = ?",
            Boolean::class.java, json, workspace, id,
        )!!)
        assertEquals(1L, jdbc.queryForObject(
            "SELECT version FROM skw.entries WHERE workspace_id = ? AND id = ?",
            Long::class.java, workspace, id,
        ))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "null", "[]", """{"bad":null}""", """{"bad":{}}""",
        """{"bad":[1,"two"]}""", """{"bad":[[1]]}""", """{"bad":[null]}""",
        """{"BadKey":"value"}""", """{"bad/key":"value"}""",
    ])
    fun `invalid property shapes are rejected by PostgreSQL`(json: String) {
        assertThrows(DataIntegrityViolationException::class.java) { entry(workspace(), json) }
    }

    @Test
    fun `property count and value lengths are bounded`() {
        val workspace = workspace()
        val tooMany = (1..257).joinToString(",", "{", "}") { "\"p$it\":true" }
        val tooLong = "{\"text\":\"" + "x".repeat(100001) + "\"}"
        val tooManyValues = "{\"values\":[" + List(1001) { "true" }.joinToString(",") + "]}"
        for (json in listOf(tooMany, tooLong, tooManyValues)) {
            assertThrows(DataIntegrityViolationException::class.java) { entry(workspace, json) }
        }
    }

    @Test
    fun `foreign workspace and missing endpoints cannot be connected`() {
        val first = workspace()
        val second = workspace()
        val source = entry(first)
        val foreign = entry(second)
        assertThrows(DataIntegrityViolationException::class.java) { relationship(first, source, foreign) }
        assertThrows(DataIntegrityViolationException::class.java) { relationship(first, foreign, source) }
        assertThrows(DataIntegrityViolationException::class.java) { relationship(first, source, UUID.randomUUID()) }
    }

    @Test
    fun `edges are unique and immutable but different types and cycles are allowed`() {
        val workspace = workspace()
        val source = entry(workspace)
        val target = entry(workspace)
        val id = relationship(workspace, source, target)
        assertThrows(DataIntegrityViolationException::class.java) { relationship(workspace, source, target) }
        relationship(workspace, source, target, "depends-on")
        relationship(workspace, target, source)
        relationship(workspace, source, source)
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE skw.relationships SET type = 'contains' WHERE workspace_id = ? AND id = ?", workspace, id)
        }
        jdbc.update("DELETE FROM skw.relationships WHERE workspace_id = ? AND id = ?", workspace, id)
        assertNotEquals(id, relationship(workspace, source, target))
    }

    @Test
    fun `deletion never cascades through business resources`() {
        val workspace = workspace()
        val source = entry(workspace)
        val target = entry(workspace)
        val edge = relationship(workspace, source, target)
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update("DELETE FROM skw.workspaces WHERE id = ?", workspace)
        }
        for (id in listOf(source, target)) {
            assertThrows(DataIntegrityViolationException::class.java) {
                jdbc.update("DELETE FROM skw.entries WHERE workspace_id = ? AND id = ?", workspace, id)
            }
        }
        jdbc.update("DELETE FROM skw.relationships WHERE workspace_id = ? AND id = ?", workspace, edge)
        jdbc.update("DELETE FROM skw.entries WHERE workspace_id = ?", workspace)
        assertEquals(1, jdbc.update("DELETE FROM skw.workspaces WHERE id = ?", workspace))
    }

    @Test
    fun `a stale conditional write does not overwrite a newer version`() {
        val workspace = workspace()
        val id = entry(workspace)
        val sql = """UPDATE skw.entries SET properties = ?::jsonb, version = version + 1,
                     updated_at = statement_timestamp()
                     WHERE workspace_id = ? AND id = ? AND version = ?"""
        assertEquals(1, jdbc.update(sql, """{"name":"first"}""", workspace, id, 1L))
        assertEquals(0, jdbc.update(sql, """{"name":"stale"}""", workspace, id, 1L))
    }

    @Test
    fun `failed transaction rolls back its new entries`() {
        val workspace = workspace()
        assertThrows(DataIntegrityViolationException::class.java) {
            transaction.executeWithoutResult {
                val source = entry(workspace)
                relationship(workspace, source, UUID.randomUUID())
            }
        }
        assertEquals(0L, jdbc.queryForObject(
            "SELECT count(*) FROM skw.entries WHERE workspace_id = ?", Long::class.java, workspace,
        ))
    }

    @Test
    fun `idempotency results are unique and roll back with business writes`() {
        val workspace = workspace()
        val scope = "POST /api/v1/workspaces/$workspace"
        val key = UUID.randomUUID().toString()
        val insert = """INSERT INTO skw.idempotency_requests
            (scope, key, request_hash, response_status, response_headers, response_body, expires_at)
            VALUES (?, ?, ?, 201, '{"Location":"/resource","ETag":"\"v1\""}'::jsonb,
                    decode('7b7d', 'hex'), statement_timestamp() + INTERVAL '24 hours')"""
        jdbc.update(insert, scope, key, "a".repeat(64))
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(insert, scope, key, "a".repeat(64))
        }
        val rolledBackKey = UUID.randomUUID().toString()
        assertThrows(IllegalStateException::class.java) {
            transaction.executeWithoutResult {
                entry(workspace)
                jdbc.update(insert, scope, rolledBackKey, "b".repeat(64))
                error("simulate later application failure")
            }
        }
        assertEquals(0L, jdbc.queryForObject(
            "SELECT count(*) FROM skw.idempotency_requests WHERE scope = ? AND key = ?",
            Long::class.java, scope, rolledBackKey,
        ))
        assertEquals(0L, jdbc.queryForObject(
            "SELECT count(*) FROM skw.entries WHERE workspace_id = ?",
            Long::class.java, workspace,
        ))
    }

    @Test
    fun `idempotency keys accept visible ASCII and enforce byte boundaries`() {
        val scope = UUID.randomUUID().toString()
        val insert = """INSERT INTO skw.idempotency_requests
            (scope, key, request_hash, response_status, expires_at)
            VALUES (?, ?, ?, 201, statement_timestamp() + INTERVAL '24 hours')"""
        for (key in listOf("!", "~", "a".repeat(255))) {
            assertEquals(1, jdbc.update(insert, scope, key, "a".repeat(64)))
        }
        for (key in listOf("", "a".repeat(256), "é", "é".repeat(200), "has space", "tab\tkey", "line\nkey")) {
            assertThrows(DataIntegrityViolationException::class.java) {
                jdbc.update(insert, scope, key, "a".repeat(64))
            }
        }
    }
}
