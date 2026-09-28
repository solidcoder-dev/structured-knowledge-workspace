package dev.skw.application.workspace

import dev.skw.application.accesscontrol.AuthorizeUseCase
import dev.skw.application.accesscontrol.requirePrincipal
import dev.skw.application.port.out.SaveResult
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.domain.Version
import dev.skw.domain.accesscontrol.Namespace
import dev.skw.domain.accesscontrol.Permission
import dev.skw.domain.accesscontrol.PrincipalId
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.Workspace
import dev.skw.domain.workspace.WorkspaceId
import java.time.Clock

class VersionConflict(
    id: WorkspaceId,
    expected: Version,
) : RuntimeException("Workspace $id does not have version ${expected.value}")

class SetWorkspacePropertyService(
    private val repository: WorkspaceRepository,
    private val getWorkspace: GetWorkspaceUseCase = GetWorkspaceService(repository),
    private val authorize: AuthorizeUseCase,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun set(
        id: WorkspaceId,
        expectedVersion: Version,
        name: PropertyName,
        value: PropertyValue,
        principal: PrincipalId? = null,
    ) = persist(
        getWorkspace.get(id).setProperty(name, value, clock.instant()).also {
            authorize.authorize(requirePrincipal(principal), id, Permission.UPDATE, Namespace.from(name.value))
        },
        id,
        expectedVersion,
    )

    private fun persist(
        workspace: Workspace,
        id: WorkspaceId,
        expected: Version,
    ) = when (repository.saveIfVersion(workspace, expected)) {
        SaveResult.SAVED -> workspace
        SaveResult.NOT_FOUND -> throw WorkspaceNotFound(id)
        SaveResult.VERSION_CONFLICT -> throw VersionConflict(id, expected)
    }
}

class DeleteWorkspacePropertyService(
    private val repository: WorkspaceRepository,
    private val getWorkspace: GetWorkspaceUseCase = GetWorkspaceService(repository),
    private val authorize: AuthorizeUseCase,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun delete(
        id: WorkspaceId,
        expectedVersion: Version,
        name: PropertyName,
        principal: PrincipalId? = null,
    ) = persist(
        getWorkspace.get(id).removeProperty(name, clock.instant()).also {
            authorize.authorize(requirePrincipal(principal), id, Permission.DELETE, Namespace.from(name.value))
        },
        id,
        expectedVersion,
    )

    private fun persist(
        workspace: Workspace,
        id: WorkspaceId,
        expected: Version,
    ) = when (repository.saveIfVersion(workspace, expected)) {
        SaveResult.SAVED -> workspace
        SaveResult.NOT_FOUND -> throw WorkspaceNotFound(id)
        SaveResult.VERSION_CONFLICT -> throw VersionConflict(id, expected)
    }
}
