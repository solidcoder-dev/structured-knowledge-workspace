package dev.skw.adapter.inbound.rest

import dev.skw.adapter.inbound.rest.generated.api.EntriesApi
import dev.skw.adapter.inbound.rest.generated.model.CreateEntryRequest
import dev.skw.adapter.inbound.rest.generated.model.CreateEntryResponse
import dev.skw.adapter.inbound.rest.generated.model.Entry
import dev.skw.adapter.inbound.rest.generated.model.EntryPage
import dev.skw.adapter.inbound.rest.generated.model.PropertyValueRequest
import dev.skw.application.entry.CreateEntryUseCase
import dev.skw.application.entry.DeleteEntryPropertyService
import dev.skw.application.entry.DeleteEntryUseCase
import dev.skw.application.entry.GetEntryUseCase
import dev.skw.application.entry.ListEntriesQuery
import dev.skw.application.entry.ListEntriesUseCase
import dev.skw.application.entry.SetEntryPropertyService
import dev.skw.domain.entry.EntryId
import dev.skw.domain.property.PropertyName
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
class EntryController(
    private val createEntry: CreateEntryUseCase,
    private val getEntry: GetEntryUseCase,
    private val listEntries: ListEntriesUseCase,
    private val setProperty: SetEntryPropertyService,
    private val deleteProperty: DeleteEntryPropertyService,
    private val deleteEntry: DeleteEntryUseCase,
    private val mapper: EntryRestMapper,
    private val etag: ResourceEtag,
    private val cursorCodec: EntryCursorCodec,
) : EntriesApi {
    override fun createEntry(
        workspaceId: UUID,
        idempotencyKey: String,
        createEntryRequest: CreateEntryRequest,
    ): ResponseEntity<CreateEntryResponse> {
        val result = createEntry.create(mapper.toCreateCommand(workspaceId, createEntryRequest))
        return ResponseEntity
            .created(
                URI.create("/api/v1/workspaces/$workspaceId/entries/${result.entry.id}"),
            ).eTag(etag.format(result.entry.id.value, result.entry.version))
            .body(mapper.toResponse(result))
    }

    override fun getEntry(
        workspaceId: UUID,
        entryId: UUID,
    ): ResponseEntity<Entry> {
        val value = getEntry.get(WorkspaceId(workspaceId), EntryId(entryId))
        return ResponseEntity.ok().eTag(etag.format(value.id.value, value.version)).body(mapper.toRest(value))
    }

    override fun listEntries(
        workspaceId: UUID,
        cursor: String?,
        limit: Int,
    ): ResponseEntity<EntryPage> {
        val id = WorkspaceId(workspaceId)
        val page = listEntries.list(ListEntriesQuery(id, limit, cursor?.let { cursorCodec.decode(it, id) }))
        return ResponseEntity.ok(mapper.toPage(page.items, page.nextCursor?.let(cursorCodec::encode)))
    }

    override fun setEntryProperty(
        workspaceId: UUID,
        entryId: UUID,
        propertyName: String,
        propertyValueRequest: PropertyValueRequest,
        ifMatch: String?,
    ): ResponseEntity<Entry> {
        val id = EntryId(entryId)
        val value =
            setProperty.set(
                WorkspaceId(workspaceId),
                id,
                etag.requireVersion(entryId, ifMatch),
                PropertyName(propertyName),
                mapper.toDomainValue(propertyValueRequest.`value`),
            )
        return ResponseEntity.ok().eTag(etag.format(entryId, value.version)).body(mapper.toRest(value))
    }

    override fun deleteEntryProperty(
        workspaceId: UUID,
        entryId: UUID,
        propertyName: String,
        ifMatch: String?,
    ): ResponseEntity<Entry> {
        val id = EntryId(entryId)
        val value = deleteProperty.delete(WorkspaceId(workspaceId), id, etag.requireVersion(entryId, ifMatch), PropertyName(propertyName))
        return ResponseEntity.ok().eTag(etag.format(entryId, value.version)).body(mapper.toRest(value))
    }

    override fun deleteEntry(
        workspaceId: UUID,
        entryId: UUID,
        ifMatch: String?,
    ): ResponseEntity<Unit> {
        deleteEntry.delete(WorkspaceId(workspaceId), EntryId(entryId), etag.requireVersion(entryId, ifMatch))
        return ResponseEntity.noContent().build()
    }
}
