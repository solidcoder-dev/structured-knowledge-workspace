package dev.skw.application

import dev.skw.application.port.out.DeleteResult
import dev.skw.application.port.out.SaveResult
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.workspace.CreateWorkspaceCommand
import dev.skw.application.workspace.CreateWorkspaceService
import dev.skw.application.workspace.DeleteWorkspacePropertyService
import dev.skw.application.workspace.DeleteWorkspaceService
import dev.skw.application.workspace.GetWorkspaceService
import dev.skw.application.workspace.ListWorkspacesQuery
import dev.skw.application.workspace.ListWorkspacesService
import dev.skw.application.workspace.SetWorkspacePropertyService
import dev.skw.application.workspace.VersionConflict
import dev.skw.application.workspace.WorkspaceCursor
import dev.skw.application.workspace.WorkspaceNotEmpty
import dev.skw.application.workspace.WorkspaceNotFound
import dev.skw.application.workspace.WorkspacePage
import dev.skw.application.workspace.WorkspacePageRequest
import dev.skw.domain.Version
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.Workspace
import dev.skw.domain.workspace.WorkspaceId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WorkspaceUseCasesTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `create delegates to repository and get returns stored workspace`() {
        val repository = FakeWorkspaceRepository()
        val created = CreateWorkspaceService(repository, clock).create(CreateWorkspaceCommand())
        assertEquals(created, GetWorkspaceService(repository).get(created.id))
        assertEquals(1, repository.saves)
    }

    @Test
    fun `get reports semantic not found`() {
        assertThrows(
            WorkspaceNotFound::class.java,
        ) { GetWorkspaceService(FakeWorkspaceRepository()).get(WorkspaceId(java.util.UUID.randomUUID())) }
    }

    @Test
    fun `delete maps repository outcomes to semantic errors`() {
        val repository = FakeWorkspaceRepository()
        val workspace = Workspace.create(now = now)
        repository.workspace = workspace
        DeleteWorkspaceService(repository).delete(workspace.id, workspace.version)
        assertEquals(DeleteResult.DELETED, repository.lastDelete)

        repository.deleteResult = DeleteResult.NOT_FOUND
        assertThrows(WorkspaceNotFound::class.java) { DeleteWorkspaceService(repository).delete(workspace.id, workspace.version) }
        repository.deleteResult = DeleteResult.VERSION_CONFLICT
        assertThrows(VersionConflict::class.java) { DeleteWorkspaceService(repository).delete(workspace.id, workspace.version) }
        repository.deleteResult = DeleteResult.NOT_EMPTY
        assertThrows(WorkspaceNotEmpty::class.java) { DeleteWorkspaceService(repository).delete(workspace.id, workspace.version) }
    }

    @Test
    fun `property mutations distinguish effective changes and no-ops`() {
        val repository = FakeWorkspaceRepository()
        val workspace = Workspace.create(now = now)
        repository.workspace = workspace
        val name = PropertyName("kind")
        val value = PropertyValue.StringValue("capability")
        val changed = SetWorkspacePropertyService(repository, clock = clock).set(workspace.id, workspace.version, name, value)
        assertEquals(2, changed.version.value)
        assertEquals(
            now,
            SetWorkspacePropertyService(repository, clock = clock).set(workspace.id, workspace.version, name, value).updatedAt,
        )

        repository.saveResult = SaveResult.VERSION_CONFLICT
        assertThrows(VersionConflict::class.java) {
            SetWorkspacePropertyService(repository, clock = clock).set(workspace.id, workspace.version, name, value)
        }
        repository.workspace = null
        assertThrows(WorkspaceNotFound::class.java) {
            DeleteWorkspacePropertyService(repository, clock = clock).delete(workspace.id, workspace.version, name)
        }
    }

    @Test
    fun `property delete is no-op for absent property and changes existing property`() {
        val repository = FakeWorkspaceRepository()
        val workspace = Workspace.create(now = now)
        repository.workspace = workspace
        val name = PropertyName("kind")
        val absent = DeleteWorkspacePropertyService(repository, clock = clock).delete(workspace.id, workspace.version, name)
        assertEquals(workspace.version, absent.version)
        val withProperty = workspace.setProperty(name, PropertyValue.BooleanValue(true), now)
        repository.workspace = withProperty
        val removed = DeleteWorkspacePropertyService(repository, clock = clock).delete(withProperty.id, withProperty.version, name)
        assertEquals(3, removed.version.value)
    }

    @Test
    fun `list delegates cursor and returns page`() {
        val repository = FakeWorkspaceRepository()
        val first = Workspace.create(now = now)
        val second = Workspace.create(now = now.plusSeconds(1))
        repository.page = WorkspacePage(listOf(first), WorkspaceCursor(now, first.id))
        val firstPage = ListWorkspacesService(repository).list(ListWorkspacesQuery(1))
        assertEquals(listOf(first), firstPage.items)
        assertEquals(first.id, firstPage.nextCursor!!.id)
        repository.page = WorkspacePage(listOf(second), null)
        val lastPage =
            ListWorkspacesService(
                repository,
            ).list(
                ListWorkspacesQuery(
                    1,
                    dev.skw.application.workspace
                        .WorkspaceCursor(now, first.id),
                ),
            )
        assertEquals(listOf(second), lastPage.items)
        assertEquals(null, lastPage.nextCursor)
    }

    private class FakeWorkspaceRepository : WorkspaceRepository {
        var workspace: Workspace? = null
        var saves = 0
        var deleteResult = DeleteResult.DELETED
        var lastDelete: DeleteResult? = null
        var saveResult = SaveResult.SAVED
        var page = WorkspacePage(emptyList(), null)

        override fun save(workspace: Workspace): Workspace {
            saves++
            this.workspace = workspace
            return workspace
        }

        override fun findById(id: WorkspaceId) = workspace?.takeIf { it.id == id }

        override fun saveIfVersion(
            workspace: Workspace,
            expectedVersion: Version,
        ) = saveResult

        override fun delete(
            id: WorkspaceId,
            expectedVersion: Version,
        ): DeleteResult {
            lastDelete = deleteResult
            return deleteResult
        }

        override fun list(request: WorkspacePageRequest) = page
    }
}
