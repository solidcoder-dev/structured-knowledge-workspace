package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.JsonNode
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.adapter.inbound.rest.generated.api.WorkspacesApi
import dev.skw.adapter.inbound.rest.generated.model.CreateWorkspaceRequest
import dev.skw.adapter.inbound.rest.generated.model.ResourceMetadata
import dev.skw.adapter.inbound.rest.generated.model.Workspace
import dev.skw.application.workspace.CreateWorkspaceCommand
import dev.skw.application.workspace.CreateWorkspaceUseCase
import dev.skw.application.workspace.GetWorkspaceUseCase
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
    private val mapper: WorkspaceRestMapper,
) : WorkspacesApi {
    override fun createWorkspace(
        idempotencyKey: String,
        createWorkspaceRequest: CreateWorkspaceRequest,
    ): ResponseEntity<Workspace> {
        val created = createWorkspace.create(CreateWorkspaceCommand(mapper.toDomain(createWorkspaceRequest.properties.orEmpty())))
        return ResponseEntity
            .created(URI.create("/api/v1/workspaces/${created.id}"))
            .eTag(mapper.etag(created.id.value, created.version.value))
            .body(mapper.toRest(created))
    }

    override fun getWorkspace(workspaceId: UUID): ResponseEntity<Workspace> {
        val workspace = getWorkspace.get(WorkspaceId(workspaceId))
        return ResponseEntity.ok().eTag(mapper.etag(workspace.id.value, workspace.version.value)).body(mapper.toRest(workspace))
    }

    override fun deleteWorkspace(
        workspaceId: UUID,
        ifMatch: String,
    ): ResponseEntity<Unit> = ResponseEntity.notFound().build()

    override fun deleteWorkspaceProperty(
        workspaceId: UUID,
        propertyName: String,
        ifMatch: String,
    ): ResponseEntity<Workspace> = ResponseEntity.notFound().build()

    override fun listWorkspaces(
        cursor: String?,
        limit: Int,
    ): ResponseEntity<dev.skw.adapter.inbound.rest.generated.model.WorkspacePage> = ResponseEntity.notFound().build()

    override fun setWorkspaceProperty(
        workspaceId: UUID,
        propertyName: String,
        ifMatch: String,
        propertyValueRequest: dev.skw.adapter.inbound.rest.generated.model.PropertyValueRequest,
    ): ResponseEntity<Workspace> = ResponseEntity.notFound().build()
}

class WorkspaceRestMapper(
    private val json: PropertyJsonMapper,
) {
    fun toDomain(properties: Map<String, JsonNode>) = properties.mapKeys { PropertyName(it.key) }.mapValues { json.value(it.value) }

    fun toRest(workspace: dev.skw.domain.workspace.Workspace) =
        Workspace(
            id = workspace.id.value,
            properties = workspace.properties.mapKeys { it.key.value }.mapValues { json.json(it.value) },
            metadata =
                ResourceMetadata(
                    etag = etag(workspace.id.value, workspace.version.value),
                    createdAt = OffsetDateTime.ofInstant(workspace.createdAt, ZoneOffset.UTC),
                    updatedAt = OffsetDateTime.ofInstant(workspace.updatedAt, ZoneOffset.UTC),
                    version = workspace.version.value,
                ),
        )

    fun etag(
        id: UUID,
        version: Long,
    ): String = "\"$id-v$version\""
}

@RestControllerAdvice
class WorkspaceErrorHandler {
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
}
