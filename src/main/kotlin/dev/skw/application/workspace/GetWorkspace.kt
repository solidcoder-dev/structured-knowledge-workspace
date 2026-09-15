package dev.skw.application.workspace

import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.domain.workspace.Workspace
import dev.skw.domain.workspace.WorkspaceId

class WorkspaceNotFound(
    id: WorkspaceId,
) : RuntimeException("Workspace $id was not found")

fun interface GetWorkspaceUseCase {
    fun get(id: WorkspaceId): Workspace
}

class GetWorkspaceService(
    private val repository: WorkspaceRepository,
) : GetWorkspaceUseCase {
    override fun get(id: WorkspaceId): Workspace = repository.findById(id) ?: throw WorkspaceNotFound(id)
}
