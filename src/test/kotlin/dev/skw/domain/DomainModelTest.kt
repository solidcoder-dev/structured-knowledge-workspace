package dev.skw.domain

import dev.skw.domain.entry.Entry
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.relationship.Relationship
import dev.skw.domain.relationship.RelationshipId
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.Workspace
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class DomainModelTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val name = PropertyName("product.kind")

    @Test
    fun `workspace changes version only for effective property changes`() {
        val workspace = Workspace.create(now = now)
        val value = PropertyValue.StringValue("capability")
        val changed = workspace.setProperty(name, value, now.plusSeconds(1))
        assertEquals(2, changed.version.value)
        assertEquals(changed, changed.setProperty(name, value, now.plusSeconds(2)))
        assertEquals(changed, changed.removeProperty(PropertyName("missing"), now.plusSeconds(3)))
        assertEquals(3, changed.removeProperty(name, now.plusSeconds(4)).version.value)
    }

    @Test
    fun `property names and relationship types enforce the contract`() {
        listOf("", "Bad", "bad/key", "-bad", "bad_").forEach { assertThrows(IllegalArgumentException::class.java) { PropertyName(it) } }
        assertThrows(IllegalArgumentException::class.java) { PropertyName("a".repeat(129)) }
        assertEquals(PropertyName("a1.b-c_d"), PropertyName("a1.b-c_d"))
        assertThrows(IllegalArgumentException::class.java) { RelationshipType("Bad") }
    }

    @Test
    fun `property values are explicit and bounded`() {
        assertEquals(PropertyValue.NumberValue(BigDecimal("1.20")), PropertyValue.NumberValue(BigDecimal("1.20")))
        assertThrows(IllegalArgumentException::class.java) { PropertyValue.StringValue("x".repeat(100_001)) }
        assertThrows(IllegalArgumentException::class.java) { PropertyValue.BooleanListValue(List(1_001) { true }) }
    }

    @Test
    fun `entry is independent and relationship is immutable data`() {
        val workspaceId = WorkspaceId(UUID.randomUUID())
        val entry = Entry.create(workspaceId, now = now)
        assertEquals(workspaceId, entry.workspaceId)
        val relationship =
            Relationship(RelationshipId(UUID.randomUUID()), workspaceId, entry.id, entry.id, RelationshipType("supports"), now)
        assertNotEquals(relationship.id, RelationshipId(UUID.randomUUID()))
    }
}
