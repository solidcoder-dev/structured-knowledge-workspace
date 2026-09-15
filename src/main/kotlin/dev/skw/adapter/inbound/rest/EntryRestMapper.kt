package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.JsonNode
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.adapter.inbound.rest.generated.model.Entry
import dev.skw.adapter.inbound.rest.generated.model.EntryPage
import dev.skw.adapter.inbound.rest.generated.model.Relationship
import dev.skw.adapter.inbound.rest.generated.model.ResourceMetadata
import dev.skw.application.entry.CreateEntryCommand
import dev.skw.application.entry.CreateEntryResult
import dev.skw.application.entry.Direction
import dev.skw.application.entry.InitialRelationshipCommand
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyName
import dev.skw.domain.relationship.RelationshipType
import dev.skw.domain.workspace.WorkspaceId
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class EntryRestMapper(
    private val json: PropertyJsonMapper,
    private val etag: ResourceEtag,
) {
    fun toCreateCommand(
        workspaceId: UUID,
        request: dev.skw.adapter.inbound.rest.generated.model.CreateEntryRequest,
    ) = CreateEntryCommand(
        WorkspaceId(workspaceId),
        request.properties
            .orEmpty()
            .mapKeys { PropertyName(it.key) }
            .mapValues { json.value(it.value) },
        request.relationships.orEmpty().map {
            InitialRelationshipCommand(
                if (it.direction ==
                    dev.skw.adapter.inbound.rest.generated.model.InitialRelationship.Direction.OUTGOING
                ) {
                    Direction.OUTGOING
                } else {
                    Direction.INCOMING
                },
                EntryId(it.otherEntryId),
                RelationshipType(it.type),
            )
        },
    )

    fun toRest(entry: dev.skw.domain.entry.Entry) =
        Entry(
            entry.id.value,
            entry.workspaceId.value,
            entry.properties
                .mapKeys {
                    it.key.value
                }.mapValues {
                    json.json(it.value)
                },
            ResourceMetadata(
                etag.format(entry.id.value, entry.version),
                OffsetDateTime.ofInstant(entry.createdAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(entry.updatedAt, ZoneOffset.UTC),
                entry.version.value,
            ),
        )

    fun toRest(relationship: dev.skw.domain.relationship.Relationship) =
        Relationship(
            relationship.id.value,
            relationship.workspaceId.value,
            relationship.sourceEntryId.value,
            relationship.targetEntryId.value,
            relationship.type.value,
            OffsetDateTime.ofInstant(relationship.createdAt, ZoneOffset.UTC),
        )

    fun toDomainValue(value: JsonNode) =
        try {
            json.value(value)
        } catch (error: IllegalStateException) {
            throw IllegalArgumentException("Invalid property value", error)
        }

    fun toResponse(result: CreateEntryResult) =
        dev.skw.adapter.inbound.rest.generated.model
            .CreateEntryResponse(toRest(result.entry), result.relationships.map(::toRest))

    fun toPage(
        items: List<dev.skw.domain.entry.Entry>,
        cursor: String?,
    ) = EntryPage(items.map(::toRest), cursor)
}
