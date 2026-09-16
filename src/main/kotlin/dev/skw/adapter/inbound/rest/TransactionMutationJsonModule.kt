package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.module.SimpleModule
import dev.skw.adapter.inbound.rest.generated.model.CreateEntryMutation
import dev.skw.adapter.inbound.rest.generated.model.CreateEntryRequest
import dev.skw.adapter.inbound.rest.generated.model.CreateRelationshipMutation
import dev.skw.adapter.inbound.rest.generated.model.DeleteEntryMutation
import dev.skw.adapter.inbound.rest.generated.model.DeleteEntryPropertyMutation
import dev.skw.adapter.inbound.rest.generated.model.DeleteRelationshipMutation
import dev.skw.adapter.inbound.rest.generated.model.Mutation
import dev.skw.adapter.inbound.rest.generated.model.SetEntryPropertyMutation
import dev.skw.adapter.inbound.rest.generated.model.TransactionRelationshipRequest

internal enum class DecodedMutationKind {
    CREATE_ENTRY,
    SET_ENTRY_PROPERTY,
    DELETE_ENTRY_PROPERTY,
    DELETE_ENTRY,
    CREATE_RELATIONSHIP,
    DELETE_RELATIONSHIP,
}

internal abstract class BaseDecodedMutation : Mutation {
    abstract val kind: DecodedMutationKind
    override val operation = Mutation.Operation.DELETE_RELATIONSHIP
    override val entry: CreateEntryRequest get() = error("Field is not present for this mutation")
    override val entryId: java.util.UUID get() = error("Field is not present for this mutation")
    override val expectedVersion: Long get() = error("Field is not present for this mutation")
    override val `property`: String get() = error("Field is not present for this mutation")
    override val `value`: JsonNode get() = error("Field is not present for this mutation")
    override val relationship: TransactionRelationshipRequest get() = error("Field is not present for this mutation")
    override val relationshipId: java.util.UUID get() = error("Field is not present for this mutation")
    override val localRef: String? get() = null
}

class TransactionMutationDeserializer : JsonDeserializer<Mutation>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): Mutation {
        val mapper = parser.codec as ObjectMapper
        val node = mapper.readTree<JsonNode>(parser)
        return when (val operation = node["operation"]?.asText()) {
            "CREATE_ENTRY" -> {
                val value = mapper.treeToValue(node, CreateEntryMutation::class.java)
                object : BaseDecodedMutation() {
                    override val kind = DecodedMutationKind.CREATE_ENTRY
                    override val entry = value.entry
                    override val localRef = value.localRef
                }
            }
            "SET_ENTRY_PROPERTY" -> {
                val value = mapper.treeToValue(node, SetEntryPropertyMutation::class.java)
                object : BaseDecodedMutation() {
                    override val kind = DecodedMutationKind.SET_ENTRY_PROPERTY
                    override val entryId = value.entryId
                    override val expectedVersion = value.expectedVersion
                    override val `property` = value.`property`
                    override val `value` = value.`value`
                }
            }
            "DELETE_ENTRY_PROPERTY" -> {
                val value = mapper.treeToValue(node, DeleteEntryPropertyMutation::class.java)
                object : BaseDecodedMutation() {
                    override val kind = DecodedMutationKind.DELETE_ENTRY_PROPERTY
                    override val entryId = value.entryId
                    override val expectedVersion = value.expectedVersion
                    override val `property` = value.`property`
                }
            }
            "DELETE_ENTRY" -> {
                val value = mapper.treeToValue(node, DeleteEntryMutation::class.java)
                object : BaseDecodedMutation() {
                    override val kind = DecodedMutationKind.DELETE_ENTRY
                    override val entryId = value.entryId
                    override val expectedVersion = value.expectedVersion
                }
            }
            "CREATE_RELATIONSHIP" -> {
                val value = mapper.treeToValue(node, CreateRelationshipMutation::class.java)
                object : BaseDecodedMutation() {
                    override val kind = DecodedMutationKind.CREATE_RELATIONSHIP
                    override val relationship = value.relationship
                }
            }
            "DELETE_RELATIONSHIP" -> {
                val value = mapper.treeToValue(node, DeleteRelationshipMutation::class.java)
                object : BaseDecodedMutation() {
                    override val kind = DecodedMutationKind.DELETE_RELATIONSHIP
                    override val relationshipId = value.relationshipId
                }
            }
            else -> throw IllegalArgumentException("Unknown transaction operation '$operation'")
        }
    }
}

class TransactionMutationJacksonModule : SimpleModule("transaction-mutations") {
    init {
        setMixInAnnotation(Mutation::class.java, MutationJacksonMixin::class.java)
        addDeserializer(Mutation::class.java, TransactionMutationDeserializer())
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
@JsonDeserialize(using = TransactionMutationDeserializer::class)
private abstract class MutationJacksonMixin
