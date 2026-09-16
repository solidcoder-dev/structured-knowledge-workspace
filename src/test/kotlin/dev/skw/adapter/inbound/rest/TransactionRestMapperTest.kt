package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.adapter.inbound.rest.generated.model.TransactionRequest
import dev.skw.application.transaction.CreateRelationshipMutation
import dev.skw.application.transaction.LocalEntryReference
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
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
        val mapper = TransactionRestMapper(EntryRestMapper(PropertyJsonMapper(objectMapper), ResourceEtag()))
        val mutation = mapper.toCommand(WorkspaceId(UUID.randomUUID()).value, request).mutations.single()
        assertTrue(mutation is CreateRelationshipMutation)
        assertTrue((mutation as CreateRelationshipMutation).sourceEntryRef is LocalEntryReference)
        assertEquals("a", (mutation.sourceEntryRef as LocalEntryReference).localRef.value)
    }
}
