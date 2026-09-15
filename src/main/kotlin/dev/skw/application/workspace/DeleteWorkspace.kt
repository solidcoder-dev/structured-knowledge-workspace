package dev.skw.application.workspace

import dev.skw.application.port.out.DeleteResult
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.domain.Version
import dev.skw.domain.workspace.WorkspaceId

class WorkspaceNotEmpty(
    id: WorkspaceId,
) : RuntimeException("Workspace $id is not empty")

fun interface DeleteWorkspaceUseCase {
    fun delete(
        id: WorkspaceId,
        expectedVersion: Version,
    )
}

class DeleteWorkspaceService(
    private val repository: WorkspaceRepository,
) : DeleteWorkspaceUseCase {
    override fun delete(
        id: WorkspaceId,
        expectedVersion: Version,
    ) {
        when (repository.delete(id, expectedVersion)) {
            DeleteResult.DELETED -> Unit
            DeleteResult.NOT_FOUND -> throw WorkspaceNotFound(id)
            DeleteResult.VERSION_CONFLICT -> throw VersionConflict(id, expectedVersion)
            DeleteResult.NOT_EMPTY -> throw WorkspaceNotEmpty(id)
        }
    }
}
