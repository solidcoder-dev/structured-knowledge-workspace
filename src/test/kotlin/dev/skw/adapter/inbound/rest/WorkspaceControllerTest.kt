package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.adapter.inbound.rest.generated.model.CreateWorkspaceRequest
import dev.skw.application.port.out.DeleteResult
import dev.skw.application.port.out.SaveResult
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.workspace.CreateWorkspaceUseCase
import dev.skw.application.workspace.DeleteWorkspacePropertyService
import dev.skw.application.workspace.DeleteWorkspaceService
import dev.skw.application.workspace.GetWorkspaceUseCase
import dev.skw.application.workspace.ListWorkspacesService
import dev.skw.application.workspace.SetWorkspacePropertyService
import dev.skw.application.workspace.WorkspacePage
import dev.skw.application.workspace.WorkspacePageRequest
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.Workspace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
                SetWorkspacePropertyService(FakeRepository(workspace)),
                DeleteWorkspacePropertyService(FakeRepository(workspace)),
                DeleteWorkspaceService(FakeRepository(workspace)),
                ListWorkspacesService(FakeRepository(workspace)),
                WorkspaceRestMapper(PropertyJsonMapper(ObjectMapper())),
                ResourceEtag(),
                WorkspaceCursorCodec(),
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

    private class FakeRepository(
        private var workspace: dev.skw.domain.workspace.Workspace,
    ) : WorkspaceRepository {
        override fun save(workspace: dev.skw.domain.workspace.Workspace) = workspace

        override fun findById(id: dev.skw.domain.workspace.WorkspaceId) = workspace.takeIf { it.id == id }

        override fun saveIfVersion(
            workspace: dev.skw.domain.workspace.Workspace,
            expectedVersion: dev.skw.domain.Version,
        ) = SaveResult.SAVED

        override fun delete(
            id: dev.skw.domain.workspace.WorkspaceId,
            expectedVersion: dev.skw.domain.Version,
        ) = DeleteResult.DELETED

        override fun list(request: WorkspacePageRequest) = WorkspacePage(listOf(workspace), null)
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

    @Test
    fun `etag parser distinguishes missing malformed and valid tags`() {
        val etag = ResourceEtag()
        val id =
            dev.skw.domain.workspace
                .WorkspaceId(UUID.randomUUID())
        assertEquals(
            1,
            etag
                .requireVersion(
                    id,
                    etag.format(
                        id,
                        dev.skw.domain.Version
                            .initial(),
                    ),
                ).value,
        )
        assertThrows(MissingIfMatch::class.java) { etag.requireVersion(id, null) }
        assertThrows(MalformedEtag::class.java) { etag.requireVersion(id, "*") }
        assertThrows(MalformedEtag::class.java) { etag.requireVersion(id, "\"${id.value}-v1\", \"x\"") }
    }

    @Test
    fun `property deletion workspace deletion and listing map responses`() {
        val workspace = Workspace.create(now = Instant.parse("2026-01-01T00:00:00Z"))
        val controller = controllerFor(workspace)
        val tag = ResourceEtag().format(workspace.id, workspace.version)
        val property = controller.deleteWorkspaceProperty(workspace.id.value, "missing", tag)
        assertEquals(200, property.statusCode.value())
        assertEquals(tag, property.headers.getFirst("ETag"))
        assertEquals(204, controller.deleteWorkspace(workspace.id.value, tag).statusCode.value())
        val page = controller.listWorkspaces(null, 25)
        assertEquals(200, page.statusCode.value())
        assertEquals(1, page.body!!.items.size)
    }

    private fun controllerFor(workspace: Workspace) =
        WorkspaceController(
            CreateWorkspaceUseCase { workspace },
            GetWorkspaceUseCase { workspace },
            SetWorkspacePropertyService(FakeRepository(workspace)),
            DeleteWorkspacePropertyService(FakeRepository(workspace)),
            DeleteWorkspaceService(FakeRepository(workspace)),
            ListWorkspacesService(FakeRepository(workspace)),
            WorkspaceRestMapper(PropertyJsonMapper(ObjectMapper())),
            ResourceEtag(),
            WorkspaceCursorCodec(),
        )
}
