package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.adapter.inbound.rest.generated.model.CreateWorkspaceRequest
import dev.skw.application.workspace.CreateWorkspaceUseCase
import dev.skw.application.workspace.GetWorkspaceUseCase
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.Workspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class WorkspaceControllerTest {
    @Test
    fun `post and get map properties headers and location`() {
        val workspace =
            Workspace.create(
                mapOf(PropertyName("kind") to PropertyValue.StringValue("capability")),
                Instant.parse("2026-01-01T00:00:00Z"),
            )
        val controller =
            WorkspaceController(
                CreateWorkspaceUseCase { workspace },
                GetWorkspaceUseCase { workspace },
                WorkspaceRestMapper(PropertyJsonMapper(ObjectMapper())),
            )
        val created =
            controller.createWorkspace(
                "request-1",
                CreateWorkspaceRequest(mapOf("kind" to ObjectMapper().nodeFactory.textNode("capability"))),
            )
        val fetched = controller.getWorkspace(workspace.id.value)
        assertEquals(201, created.statusCode.value())
        assertEquals("/api/v1/workspaces/${workspace.id}", created.headers.location!!.toString())
        assertEquals(created.headers.getFirst("ETag"), fetched.headers.getFirst("ETag"))
        assertEquals("capability", fetched.body!!.properties["kind"]!!.textValue())
    }

    @Test
    fun `get missing workspace is translated by the error handler`() {
        val error =
            dev.skw.application.workspace
                .WorkspaceNotFound(
                    dev.skw.domain.workspace
                        .WorkspaceId(UUID.randomUUID()),
                )
        val response = WorkspaceErrorHandler().notFound(error)
        assertEquals(404, response.statusCode.value())
        assertEquals("WORKSPACE_NOT_FOUND", response.body!!.code)
    }
}
