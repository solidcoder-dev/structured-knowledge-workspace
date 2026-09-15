package dev.skw.application

import dev.skw.application.port.out.DeleteResult
import dev.skw.application.port.out.SaveResult
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.workspace.CreateWorkspaceCommand
import dev.skw.application.workspace.CreateWorkspaceService
import dev.skw.application.workspace.GetWorkspaceService
import dev.skw.application.workspace.WorkspaceNotFound
import dev.skw.domain.Version
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

    private class FakeWorkspaceRepository : WorkspaceRepository {
        var workspace: Workspace? = null
        var saves = 0

        override fun save(workspace: Workspace): Workspace {
            saves++
            this.workspace = workspace
            return workspace
        }

        override fun findById(id: WorkspaceId) = workspace?.takeIf { it.id == id }

        override fun saveIfVersion(
            workspace: Workspace,
            expectedVersion: Version,
        ) = SaveResult.SAVED

        override fun delete(
            id: WorkspaceId,
            expectedVersion: Version,
        ) = DeleteResult.DELETED
    }
}
