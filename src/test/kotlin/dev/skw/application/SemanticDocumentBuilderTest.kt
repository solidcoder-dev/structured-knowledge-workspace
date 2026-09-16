package dev.skw.application

import dev.skw.application.semantic.EmbeddingProfile
import dev.skw.application.semantic.EmbeddingVector
import dev.skw.application.semantic.SemanticDocumentBuilder
import dev.skw.application.semantic.SemanticDocumentHasher
import dev.skw.domain.entry.Entry
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SemanticDocumentBuilderTest {
    private val workspace = WorkspaceId(UUID.randomUUID())
    private val builder = SemanticDocumentBuilder()

    @Test
    fun `document is deterministic sorted by property and preserves list order`() {
        val entry =
            Entry.create(
                workspace,
                mapOf(
                    PropertyName("tags") to PropertyValue.StringListValue(listOf("finance", "mobile")),
                    PropertyName("name") to PropertyValue.StringValue("Gonezo"),
                    PropertyName("enabled") to PropertyValue.BooleanValue(true),
                ),
                Instant.EPOCH,
            )
        assertEquals("name: Gonezo\ntags: finance\ntags: mobile", builder.build(entry).text)
        val equivalent =
            Entry.create(
                workspace,
                mapOf(
                    PropertyName("name") to PropertyValue.StringValue("Gonezo"),
                    PropertyName("tags") to PropertyValue.StringListValue(listOf("finance", "mobile")),
                    PropertyName("enabled") to PropertyValue.BooleanValue(false),
                ),
                Instant.EPOCH,
            )
        assertEquals(builder.build(entry), builder.build(equivalent))
    }

    @Test
    fun `empty and hash are stable`() {
        val entry = Entry.create(workspace, mapOf(PropertyName("count") to PropertyValue.NumberValue("1".toBigDecimal())), Instant.EPOCH)
        val document = builder.build(entry)
        assertEquals("", document.text)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", SemanticDocumentHasher().hash(document))
    }

    @Test
    fun `vectors reject invalid values and profile mismatch`() {
        assertThrows(IllegalArgumentException::class.java) { EmbeddingVector.of(listOf(Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { EmbeddingVector.of(listOf(Double.POSITIVE_INFINITY)) }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddingVector.of(listOf(1.0)).requireCompatibleWith(EmbeddingProfile("p", 2))
        }
    }
}
