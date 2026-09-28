package dev.skw.application.workspace

import dev.skw.application.accesscontrol.AuthorizeUseCase
import dev.skw.application.accesscontrol.requirePrincipal
import dev.skw.application.port.out.DeleteResult
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.domain.Version
import dev.skw.domain.accesscontrol.Permission
import dev.skw.domain.accesscontrol.PrincipalId
import dev.skw.domain.workspace.WorkspaceId

class WorkspaceNotEmpty(
    id: WorkspaceId,
) : RuntimeException("Workspace $id is not empty")

interface DeleteWorkspaceUseCase {
    fun delete(
        id: WorkspaceId,
        expectedVersion: Version,
    )

    fun delete(
        id: WorkspaceId,
        expectedVersion: Version,
        principal: PrincipalId?,
    ) = delete(id, expectedVersion)
}

class DeleteWorkspaceService(
    private val repository: WorkspaceRepository,
    private val authorize: AuthorizeUseCase,
) : DeleteWorkspaceUseCase {
    override fun delete(
        id: WorkspaceId,
        expectedVersion: Version,
    ) = delete(id, expectedVersion, null)

    override fun delete(
        id: WorkspaceId,
        expectedVersion: Version,
        principal: PrincipalId?,
    ) {
        authorize.authorize(requirePrincipal(principal), id, Permission.DELETE, null)
        when (repository.delete(id, expectedVersion)) {
            DeleteResult.DELETED -> Unit
            DeleteResult.NOT_FOUND -> throw WorkspaceNotFound(id)
            DeleteResult.VERSION_CONFLICT -> throw VersionConflict(id, expectedVersion)
            DeleteResult.NOT_EMPTY -> throw WorkspaceNotEmpty(id)
        }
    }
}
