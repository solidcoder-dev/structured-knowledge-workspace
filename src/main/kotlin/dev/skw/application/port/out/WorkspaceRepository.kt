package dev.skw.application.port.out

import dev.skw.application.workspace.WorkspacePage
import dev.skw.application.workspace.WorkspacePageRequest
import dev.skw.domain.Version
import dev.skw.domain.workspace.Workspace
import dev.skw.domain.workspace.WorkspaceId

interface WorkspaceRepository {
    fun save(workspace: Workspace): Workspace

    fun findById(id: WorkspaceId): Workspace?

    fun saveIfVersion(
        workspace: Workspace,
        expectedVersion: Version,
    ): SaveResult

    fun delete(
        id: WorkspaceId,
        expectedVersion: Version,
    ): DeleteResult

    fun list(request: WorkspacePageRequest): WorkspacePage
}

enum class SaveResult { SAVED, NOT_FOUND, VERSION_CONFLICT }

enum class DeleteResult { DELETED, NOT_FOUND, VERSION_CONFLICT, NOT_EMPTY }
