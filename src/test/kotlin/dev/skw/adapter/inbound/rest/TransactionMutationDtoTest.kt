package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class TransactionMutationDtoTest {
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(TransactionMutationJacksonModule())

    @Test
    fun `operation selects one concrete transport shape for every mutation`() {
        val entryId = UUID.randomUUID()
        val relationshipId = UUID.randomUUID()
        val request =
            mapper.readValue(
                """
                {
                  "mutations": [
                    {"operation":"CREATE_ENTRY","entry":{"properties":{}},"localRef":"entry"},
                    {"operation":"SET_ENTRY_PROPERTY","entryId":"$entryId","expectedVersion":1,"property":"name","value":"value"},
                    {"operation":"DELETE_ENTRY_PROPERTY","entryId":"$entryId","expectedVersion":2,"property":"name"},
                    {"operation":"DELETE_ENTRY","entryId":"$entryId","expectedVersion":3},
                    {"operation":"CREATE_RELATIONSHIP","relationship":{"sourceEntryRef":"@$entryId","targetEntryRef":"$entryId","type":"supports"}},
                    {"operation":"DELETE_RELATIONSHIP","relationshipId":"$relationshipId"}
                  ]
                }
                """.trimIndent(),
                TransactionRequestDto::class.java,
            )

        assertTrue(request.mutations[0] is CreateEntryDto)
        assertTrue(request.mutations[1] is SetEntryPropertyDto)
        assertTrue(request.mutations[2] is DeleteEntryPropertyDto)
        assertTrue(request.mutations[3] is DeleteEntryDto)
        assertTrue(request.mutations[4] is CreateRelationshipDto)
        assertTrue(request.mutations[5] is DeleteRelationshipDto)
    }

    @Test
    fun `unknown operation is rejected at transport boundary`() {
        assertThrows(Exception::class.java) {
            mapper.readValue(
                """{"mutations":[{"operation":"RENAME_ENTRY"}]}""",
                TransactionRequestDto::class.java,
            )
        }
    }

    @Test
    fun `operation-specific required fields are rejected at transport boundary`() {
        assertThrows(Exception::class.java) {
            mapper.readValue(
                """{"mutations":[{"operation":"CREATE_ENTRY"}]}""",
                TransactionRequestDto::class.java,
            )
        }
    }
}
