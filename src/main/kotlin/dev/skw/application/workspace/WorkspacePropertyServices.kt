package dev.skw.application.workspace

import dev.skw.application.port.out.SaveResult
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.domain.Version
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
    private val clock: Clock = Clock.systemUTC(),
) {
    fun set(
        id: WorkspaceId,
        expectedVersion: Version,
        name: PropertyName,
        value: PropertyValue,
    ) = persist(getWorkspace.get(id).setProperty(name, value, clock.instant()), id, expectedVersion)

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
    private val clock: Clock = Clock.systemUTC(),
) {
    fun delete(
        id: WorkspaceId,
        expectedVersion: Version,
        name: PropertyName,
    ) = persist(getWorkspace.get(id).removeProperty(name, clock.instant()), id, expectedVersion)

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
