package dev.skw.application.workspace

import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.domain.property.PropertyName
import dev.skw.domain.property.PropertyValue
import dev.skw.domain.workspace.Workspace
import java.time.Clock

data class CreateWorkspaceCommand(
    val properties: Map<PropertyName, PropertyValue> = emptyMap(),
)

fun interface CreateWorkspaceUseCase {
    fun create(command: CreateWorkspaceCommand): Workspace
}

class CreateWorkspaceService(
    private val repository: WorkspaceRepository,
    private val clock: Clock = Clock.systemUTC(),
) : CreateWorkspaceUseCase {
    override fun create(command: CreateWorkspaceCommand): Workspace = repository.save(Workspace.create(command.properties, clock.instant()))
}
