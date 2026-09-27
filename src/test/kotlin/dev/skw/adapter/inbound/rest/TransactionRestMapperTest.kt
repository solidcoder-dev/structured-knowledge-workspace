package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.adapter.inbound.rest.generated.model.TransactionRequest
import dev.skw.application.transaction.CreateEntryMutation
import dev.skw.application.transaction.CreateRelationshipMutation
import dev.skw.application.transaction.LocalEntryReference
import dev.skw.application.transaction.SetEntryPropertyMutation
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class TransactionRestMapperTest {
    @Test
    fun `generated one of payloads map to application mutation references`() {
        val objectMapper = ObjectMapper().registerKotlinModule().registerModule(TransactionMutationJacksonModule())
        val request =
            objectMapper.readValue(
                """
                {"mutations":[{"operation":"CREATE_RELATIONSHIP","relationship":{"sourceEntryRef":"@a","targetEntryRef":"${UUID.randomUUID()}","type":"supports"}}]}
                """.trimIndent(),
                TransactionRequest::class.java,
            )
        val propertyMapper = PropertyJsonMapper(objectMapper)
        val mapper = TransactionRestMapper(EntryRestMapper(propertyMapper, ResourceEtag()), propertyMapper)
        val mutation = mapper.toCommand(WorkspaceId(UUID.randomUUID()).value, request).mutations.single()
        assertTrue(mutation is CreateRelationshipMutation)
        assertTrue((mutation as CreateRelationshipMutation).sourceEntryRef is LocalEntryReference)
        assertEquals("a", (mutation.sourceEntryRef as LocalEntryReference).localRef.value)
    }

    @Test
    fun `product definition transaction properties map to domain values`() {
        val objectMapper = ObjectMapper().registerKotlinModule().registerModule(TransactionMutationJacksonModule())
        val entryId = UUID.randomUUID()
        val request =
            objectMapper.readValue(
                """
                {
                  "mutations": [
                    {"operation":"CREATE_ENTRY","localRef":"item","entry":{"properties":{"product-definition.id":"$entryId","decision-table.conditions":["a","b"]}}},
                    {"operation":"SET_ENTRY_PROPERTY","entryId":"$entryId","expectedVersion":1,"property":"definition.write-token","value":"token"}
                  ]
                }
                """.trimIndent(),
                TransactionRequest::class.java,
            )
        val propertyMapper = PropertyJsonMapper(objectMapper)
        val mapper = TransactionRestMapper(EntryRestMapper(propertyMapper, ResourceEtag()), propertyMapper)

        val mutations = mapper.toCommand(UUID.randomUUID(), request).mutations

        assertEquals(
            PropertyValue.StringValue(entryId.toString()),
            (mutations[0] as CreateEntryMutation).properties.getValue(PropertyName("product-definition.id")),
        )
        assertEquals(
            PropertyValue.StringListValue(listOf("a", "b")),
            (mutations[0] as CreateEntryMutation).properties.getValue(PropertyName("decision-table.conditions")),
        )
        assertEquals(PropertyValue.StringValue("token"), (mutations[1] as SetEntryPropertyMutation).value)
    }

    @Test
    fun `invalid transaction property is rejected at the transport boundary`() {
        val objectMapper = ObjectMapper().registerKotlinModule().registerModule(TransactionMutationJacksonModule())
        val request =
            objectMapper.readValue(
                """{"mutations":[{"operation":"SET_ENTRY_PROPERTY","entryId":"${UUID.randomUUID()}","expectedVersion":1,"property":"x","value":{"nested":true}}]}""",
                TransactionRequest::class.java,
            )
        val propertyMapper = PropertyJsonMapper(objectMapper)
        val mapper = TransactionRestMapper(EntryRestMapper(propertyMapper, ResourceEtag()), propertyMapper)

        assertThrows(IllegalArgumentException::class.java) { mapper.toCommand(UUID.randomUUID(), request) }
    }
}
