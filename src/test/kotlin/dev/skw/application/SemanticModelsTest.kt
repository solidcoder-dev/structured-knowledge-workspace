package dev.skw.application

import dev.skw.application.semantic.EmbeddingProfile
import dev.skw.application.semantic.EmbeddingVector
import dev.skw.application.semantic.SemanticDocument
import dev.skw.application.semantic.SemanticDocumentBuilder
import dev.skw.application.semantic.SemanticDocumentHasher
import dev.skw.domain.entry.Entry
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SemanticModelsTest {
    private val workspace = WorkspaceId(UUID.randomUUID())
    private val builder = SemanticDocumentBuilder()

    @Test
    fun `semantic document is deterministic and includes only ordered textual properties`() {
        val entry =
            Entry.create(
                workspace,
                mapOf(
                    PropertyName("tags") to PropertyValue.StringListValue(listOf("finance", "mobile")),
                    PropertyName("name") to PropertyValue.StringValue("Gonezo"),
                    PropertyName("enabled") to PropertyValue.BooleanValue(true),
                    PropertyName("count") to PropertyValue.NumberValue("2".toBigDecimal()),
                ),
                Instant.EPOCH,
            )
        assertEquals("name: Gonezo\ntags: finance\ntags: mobile", builder.build(entry).text)
    }

    @Test
    fun `empty textual content has stable hash and vectors reject invalid values`() {
        val empty = builder.build(Entry.create(workspace, emptyMap(), Instant.EPOCH))
        assertEquals(SemanticDocument.EMPTY, empty)
        assertEquals(SemanticDocumentHasher().hash(empty), SemanticDocumentHasher().hash(empty))
        assertThrows(IllegalArgumentException::class.java) { EmbeddingVector.of(listOf(Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddingVector.of(listOf(Double.POSITIVE_INFINITY)) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddingVector.of(emptyList()) }
    }

    @Test
    fun `content hash ignores non textual changes`() {
        val first = Entry.create(workspace, mapOf(PropertyName("enabled") to PropertyValue.BooleanValue(true)), Instant.EPOCH)
        val second = Entry.create(workspace, mapOf(PropertyName("enabled") to PropertyValue.BooleanValue(false)), Instant.EPOCH)
        val hasher = SemanticDocumentHasher()
        assertEquals(hasher.hash(builder.build(first)), hasher.hash(builder.build(second)))
        val changed = Entry.create(workspace, mapOf(PropertyName("text") to PropertyValue.StringValue("changed")), Instant.EPOCH)
        assertNotEquals(hasher.hash(builder.build(first)), hasher.hash(builder.build(changed)))
    }

    @Test
    fun `profile validates dimensions`() {
        assertThrows(IllegalArgumentException::class.java) { EmbeddingProfile("", 3) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddingProfile("test", 0) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { EmbeddingVector.of(listOf(1.0)).requireCompatibleWith(EmbeddingProfile("test", 2)) }
    }
}
