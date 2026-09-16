package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.JsonNode
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.adapter.inbound.rest.generated.api.WorkspacesApi
import dev.skw.adapter.inbound.rest.generated.model.CreateWorkspaceRequest
import dev.skw.adapter.inbound.rest.generated.model.ResourceMetadata
import dev.skw.adapter.inbound.rest.generated.model.Workspace
import dev.skw.adapter.inbound.rest.generated.model.WorkspacePage
import dev.skw.application.idempotency.IdempotencyScope
import dev.skw.application.relationship.InvalidRelationshipCursor
import dev.skw.application.relationship.RelationshipAlreadyExists
import dev.skw.application.relationship.RelationshipEndpointMissing
import dev.skw.application.relationship.RelationshipNotFound
import dev.skw.application.search.GraphLimitExceeded
import dev.skw.application.search.InvalidPropertyFilter
import dev.skw.application.search.InvalidSearchCursor
import dev.skw.application.search.InvalidSearchRequest
import dev.skw.application.search.SearchCapabilityUnavailable
import dev.skw.application.transaction.DuplicateLocalRef
import dev.skw.application.transaction.InvalidLocalRef
import dev.skw.application.transaction.InvalidTransactionReference
import dev.skw.application.transaction.InvalidTransactionSize
import dev.skw.application.transaction.TransactionMutationFailed
import dev.skw.application.transaction.UnknownLocalRef
import dev.skw.application.workspace.CreateWorkspaceCommand
import dev.skw.application.workspace.CreateWorkspaceUseCase
import dev.skw.application.workspace.DeleteWorkspacePropertyService
import dev.skw.application.workspace.DeleteWorkspaceUseCase
import dev.skw.application.workspace.GetWorkspaceUseCase
import dev.skw.application.workspace.ListWorkspacesQuery
import dev.skw.application.workspace.ListWorkspacesUseCase
import dev.skw.application.workspace.SetWorkspacePropertyService
import dev.skw.application.workspace.VersionConflict
import dev.skw.application.workspace.WorkspaceNotEmpty
import dev.skw.application.workspace.WorkspaceNotFound
import dev.skw.domain.property.PropertyName
import dev.skw.domain.workspace.WorkspaceId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@RestController
class WorkspaceController(
    private val createWorkspace: CreateWorkspaceUseCase,
    private val getWorkspace: GetWorkspaceUseCase,
    private val setWorkspaceProperty: SetWorkspacePropertyService,
    private val deleteWorkspaceProperty: DeleteWorkspacePropertyService,
    private val deleteWorkspace: DeleteWorkspaceUseCase,
    private val listWorkspaces: ListWorkspacesUseCase,
    private val mapper: WorkspaceRestMapper,
    private val etag: ResourceEtag,
    private val cursorCodec: WorkspaceCursorCodec,
    private val idempotent: IdempotentRestExecutor? = null,
) : WorkspacesApi {
    override fun createWorkspace(
        idempotencyKey: String,
        createWorkspaceRequest: CreateWorkspaceRequest,
    ): ResponseEntity<Workspace> =
        idempotent?.execute(IdempotencyScope("POST", "/api/v1/workspaces"), idempotencyKey, createWorkspaceRequest, Workspace::class.java) {
            createWorkspaceResponse(createWorkspaceRequest)
        } ?: createWorkspaceResponse(createWorkspaceRequest)

    private fun createWorkspaceResponse(createWorkspaceRequest: CreateWorkspaceRequest): ResponseEntity<Workspace> {
        val created = createWorkspace.create(CreateWorkspaceCommand(mapper.toDomain(createWorkspaceRequest.properties.orEmpty())))
        return ResponseEntity
            .created(URI.create("/api/v1/workspaces/${created.id}"))
            .eTag(etag.format(created.id, created.version))
            .body(mapper.toRest(created))
    }

    override fun getWorkspace(workspaceId: UUID): ResponseEntity<Workspace> {
        val workspace = getWorkspace.get(WorkspaceId(workspaceId))
        return ResponseEntity.ok().eTag(etag.format(workspace.id, workspace.version)).body(mapper.toRest(workspace))
    }

    override fun deleteWorkspace(
        workspaceId: UUID,
        ifMatch: String?,
    ): ResponseEntity<Unit> {
        deleteWorkspace.delete(WorkspaceId(workspaceId), etag.requireVersion(WorkspaceId(workspaceId), ifMatch))
        return ResponseEntity.noContent().build()
    }

    override fun deleteWorkspaceProperty(
        workspaceId: UUID,
        propertyName: String,
        ifMatch: String?,
    ): ResponseEntity<Workspace> {
        val id = WorkspaceId(workspaceId)
        val updated = deleteWorkspaceProperty.delete(id, etag.requireVersion(id, ifMatch), PropertyName(propertyName))
        return ResponseEntity.ok().eTag(etag.format(updated.id, updated.version)).body(mapper.toRest(updated))
    }

    override fun listWorkspaces(
        cursor: String?,
        limit: Int,
    ): ResponseEntity<WorkspacePage> {
        val page = listWorkspaces.list(ListWorkspacesQuery(limit, cursor?.let(cursorCodec::decode)))
        return ResponseEntity.ok(
            WorkspacePage(
                items = page.items.map(mapper::toRest),
                nextCursor =
                    page.nextCursor?.let {
                        cursorCodec.encode(
                            dev.skw.application.workspace
                                .WorkspaceCursor(it.createdAt, it.id),
                        )
                    },
            ),
        )
    }

    override fun setWorkspaceProperty(
        workspaceId: UUID,
        propertyName: String,
        propertyValueRequest: dev.skw.adapter.inbound.rest.generated.model.PropertyValueRequest,
        ifMatch: String?,
    ): ResponseEntity<Workspace> {
        val id = WorkspaceId(workspaceId)
        val updated =
            setWorkspaceProperty.set(
                id,
                etag.requireVersion(id, ifMatch),
                PropertyName(propertyName),
                mapper.toDomainValue(propertyValueRequest.`value`),
            )
        return ResponseEntity.ok().eTag(etag.format(updated.id, updated.version)).body(mapper.toRest(updated))
    }
}

class WorkspaceRestMapper(
    private val json: PropertyJsonMapper,
    private val etag: ResourceEtag = ResourceEtag(),
) {
    fun toDomain(properties: Map<String, JsonNode>) = properties.mapKeys { PropertyName(it.key) }.mapValues { json.value(it.value) }

    fun toDomainValue(value: JsonNode) =
        try {
            json.value(value)
        } catch (error: IllegalStateException) {
            throw IllegalArgumentException("Invalid property value", error)
        }

    fun toRest(workspace: dev.skw.domain.workspace.Workspace) =
        Workspace(
            id = workspace.id.value,
            properties = workspace.properties.mapKeys { it.key.value }.mapValues { json.json(it.value) },
            metadata =
                ResourceMetadata(
                    etag = etag.format(workspace.id, workspace.version),
                    createdAt = OffsetDateTime.ofInstant(workspace.createdAt, ZoneOffset.UTC),
                    updatedAt = OffsetDateTime.ofInstant(workspace.updatedAt, ZoneOffset.UTC),
                    version = workspace.version.value,
                ),
        )
}

@RestControllerAdvice
class WorkspaceErrorHandler {
    @ExceptionHandler(dev.skw.application.idempotency.IdempotencyKeyReused::class)
    fun idempotencyKeyReused(error: dev.skw.application.idempotency.IdempotencyKeyReused) =
        problem(409, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused", error)

    @ExceptionHandler(WorkspaceNotFound::class)
    fun notFound(error: WorkspaceNotFound): ResponseEntity<dev.skw.adapter.inbound.rest.generated.model.Problem> =
        ResponseEntity
            .status(
                404,
            ).body(
                dev.skw.adapter.inbound.rest.generated.model.Problem(
                    title = "Workspace not found",
                    status = 404,
                    code = "WORKSPACE_NOT_FOUND",
                    detail = error.message,
                ),
            )

    @ExceptionHandler(MissingIfMatch::class)
    fun preconditionRequired(error: MissingIfMatch) = problem(428, "PRECONDITION_REQUIRED", "Precondition required", error)

    @ExceptionHandler(MalformedEtag::class, InvalidWorkspaceCursor::class)
    fun badRequest(error: RuntimeException) = problem(400, "BAD_REQUEST", "Malformed request", error)

    @ExceptionHandler(VersionConflict::class)
    fun preconditionFailed(error: VersionConflict) = problem(412, "VERSION_CONFLICT", "Precondition failed", error)

    @ExceptionHandler(WorkspaceNotEmpty::class)
    fun conflict(error: WorkspaceNotEmpty) = problem(409, "WORKSPACE_NOT_EMPTY", "Workspace is not empty", error)

    @ExceptionHandler(dev.skw.application.entry.EntryNotFound::class)
    fun entryNotFound(error: dev.skw.application.entry.EntryNotFound) = problem(404, "ENTRY_NOT_FOUND", "Entry not found", error)

    @ExceptionHandler(dev.skw.application.entry.EntryVersionConflict::class)
    fun entryVersionConflict(error: dev.skw.application.entry.EntryVersionConflict) =
        problem(412, "VERSION_CONFLICT", "Precondition failed", error)

    @ExceptionHandler(dev.skw.application.entry.EntryConnected::class)
    fun entryConnected(error: dev.skw.application.entry.EntryConnected) = problem(409, "ENTRY_CONNECTED", "Entry is connected", error)

    @ExceptionHandler(RelationshipAlreadyExists::class)
    fun relationshipExists(error: RelationshipAlreadyExists) =
        problem(409, "RELATIONSHIP_ALREADY_EXISTS", "Relationship already exists", error)

    @ExceptionHandler(RelationshipEndpointMissing::class)
    fun relationshipEndpointMissing(error: RelationshipEndpointMissing) = problem(404, "ENTRY_NOT_FOUND", "Entry not found", error)

    @ExceptionHandler(RelationshipNotFound::class)
    fun relationshipNotFound(error: RelationshipNotFound) = problem(404, "RELATIONSHIP_NOT_FOUND", "Relationship not found", error)

    @ExceptionHandler(InvalidRelationshipCursor::class)
    fun invalidRelationshipCursor(error: InvalidRelationshipCursor) = problem(400, "BAD_REQUEST", "Malformed request", error)

    @ExceptionHandler(InvalidEntryCursor::class)
    fun invalidEntryCursor(error: InvalidEntryCursor) = problem(400, "BAD_REQUEST", "Malformed request", error)

    @ExceptionHandler(InvalidSearchCursor::class)
    fun invalidSearchCursor(error: InvalidSearchCursor) = problem(400, "BAD_REQUEST", "Malformed request", error)

    @ExceptionHandler(InvalidSearchRequest::class)
    fun invalidSearchRequest(error: InvalidSearchRequest) = problem(422, "INVALID_SEARCH_REQUEST", "Invalid search request", error)

    @ExceptionHandler(InvalidPropertyFilter::class)
    fun invalidPropertyFilter(error: InvalidPropertyFilter) = problem(422, "INVALID_SEARCH_FILTER", "Invalid search filter", error)

    @ExceptionHandler(GraphLimitExceeded::class)
    fun graphLimitExceeded(error: GraphLimitExceeded) = problem(422, "GRAPH_LIMIT_EXCEEDED", "Graph candidate limit exceeded", error)

    @ExceptionHandler(SearchCapabilityUnavailable::class)
    fun searchCapabilityUnavailable(error: SearchCapabilityUnavailable) =
        problem(503, "SEARCH_CAPABILITY_UNAVAILABLE", "Search capability unavailable", error)

    @ExceptionHandler(IllegalArgumentException::class)
    fun unprocessable(error: IllegalArgumentException) = problem(422, "INVALID_PROPERTY_VALUE", "Invalid property value", error)

    @ExceptionHandler(TransactionMutationFailed::class)
    fun transactionMutationFailed(error: TransactionMutationFailed): ResponseEntity<dev.skw.adapter.inbound.rest.generated.model.Problem> {
        val cause = error.failure
        val (status, code, title) =
            when (cause) {
                is dev.skw.application.entry.EntryVersionConflict -> Triple(409, "VERSION_CONFLICT", "Version conflict")
                is dev.skw.application.entry.EntryConnected -> Triple(409, "ENTRY_CONNECTED", "Entry is connected")
                is RelationshipAlreadyExists -> Triple(409, "RELATIONSHIP_ALREADY_EXISTS", "Relationship already exists")
                is dev.skw.application.entry.EntryNotFound -> Triple(404, "ENTRY_NOT_FOUND", "Entry not found")
                is RelationshipNotFound -> Triple(404, "RELATIONSHIP_NOT_FOUND", "Relationship not found")
                is RelationshipEndpointMissing -> Triple(404, "ENTRY_NOT_FOUND", "Entry not found")
                is UnknownLocalRef, is DuplicateLocalRef, is InvalidLocalRef, is InvalidTransactionReference,
                is InvalidTransactionSize,
                -> Triple(422, "INVALID_TRANSACTION", "Invalid transaction")
                else -> Triple(500, "TRANSACTION_FAILED", "Transaction failed")
            }
        return ResponseEntity.status(status).body(
            dev.skw.adapter.inbound.rest.generated.model.Problem(
                title = title,
                status = status,
                code = code,
                detail = cause.message,
                errors =
                    listOf(
                        dev.skw.adapter.inbound.rest.generated.model.ProblemFieldError(
                            "/mutations/${error.index}",
                            cause.message ?: title,
                        ),
                    ),
            ),
        )
    }

    @ExceptionHandler(InvalidLocalRef::class, InvalidTransactionReference::class, InvalidTransactionSize::class)
    fun invalidTransaction(error: RuntimeException) = problem(422, "INVALID_TRANSACTION", "Invalid transaction", error)

    private fun problem(
        status: Int,
        code: String,
        title: String,
        error: RuntimeException,
    ): ResponseEntity<dev.skw.adapter.inbound.rest.generated.model.Problem> =
        ResponseEntity.status(status).body(
            dev.skw.adapter.inbound.rest.generated.model.Problem(
                title = title,
                status = status,
                code = code,
                detail = error.message,
            ),
        )
}
