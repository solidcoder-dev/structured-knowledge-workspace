package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import dev.skw.adapter.inbound.rest.generated.model.CreateEntryMutation
import dev.skw.adapter.inbound.rest.generated.model.CreateEntryRequest
import dev.skw.adapter.inbound.rest.generated.model.CreateRelationshipMutation
import dev.skw.adapter.inbound.rest.generated.model.DeleteEntryMutation
import dev.skw.adapter.inbound.rest.generated.model.DeleteEntryPropertyMutation
import dev.skw.adapter.inbound.rest.generated.model.DeleteRelationshipMutation
import dev.skw.adapter.inbound.rest.generated.model.SetEntryPropertyMutation
import dev.skw.adapter.inbound.rest.generated.model.TransactionRelationshipRequest
import java.util.UUID

data class TransactionRequestDto(val mutations: List<TransactionMutationDto>)

sealed interface TransactionMutationDto

data class CreateEntryDto(val entry: CreateEntryRequest, val localRef: String? = null) : TransactionMutationDto
data class SetEntryPropertyDto(val entryId: UUID, val expectedVersion: Long, val property: String, val value: JsonNode) : TransactionMutationDto
data class DeleteEntryPropertyDto(val entryId: UUID, val expectedVersion: Long, val property: String) : TransactionMutationDto
data class DeleteEntryDto(val entryId: UUID, val expectedVersion: Long) : TransactionMutationDto
data class CreateRelationshipDto(val relationship: TransactionRelationshipRequest) : TransactionMutationDto
data class DeleteRelationshipDto(val relationshipId: UUID) : TransactionMutationDto

class TransactionMutationDtoDeserializer : JsonDeserializer<TransactionMutationDto>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): TransactionMutationDto {
        val mapper = parser.codec as ObjectMapper
        val node = mapper.readTree<JsonNode>(parser)
        return when (val operation = node["operation"]?.asText()) {
            "CREATE_ENTRY" -> mapper.treeToValue(node, CreateEntryMutation::class.java).let {
                CreateEntryDto(it.entry, node["localRef"]?.takeUnless(JsonNode::isNull)?.asText())
            }
            "SET_ENTRY_PROPERTY" -> mapper.treeToValue(node, SetEntryPropertyMutation::class.java).let {
                SetEntryPropertyDto(it.entryId, it.expectedVersion, it.`property`, it.value)
            }
            "DELETE_ENTRY_PROPERTY" -> mapper.treeToValue(node, DeleteEntryPropertyMutation::class.java).let {
                DeleteEntryPropertyDto(it.entryId, it.expectedVersion, it.`property`)
            }
            "DELETE_ENTRY" -> mapper.treeToValue(node, DeleteEntryMutation::class.java).let {
                DeleteEntryDto(it.entryId, it.expectedVersion)
            }
            "CREATE_RELATIONSHIP" -> CreateRelationshipDto(mapper.treeToValue(node, CreateRelationshipMutation::class.java).relationship)
            "DELETE_RELATIONSHIP" -> DeleteRelationshipDto(mapper.treeToValue(node, DeleteRelationshipMutation::class.java).relationshipId)
            else -> throw IllegalArgumentException("Unknown transaction operation '$operation'")
        }
    }
}

class TransactionMutationJacksonModule : SimpleModule("transaction-mutations") {
    init {
        addDeserializer(TransactionMutationDto::class.java, TransactionMutationDtoDeserializer())
    }
}
