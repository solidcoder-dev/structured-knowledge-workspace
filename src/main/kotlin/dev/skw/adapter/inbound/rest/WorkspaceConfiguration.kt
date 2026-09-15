package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.workspace.CreateWorkspaceService
import dev.skw.application.workspace.CreateWorkspaceUseCase
import dev.skw.application.workspace.DeleteWorkspacePropertyService
import dev.skw.application.workspace.DeleteWorkspaceService
import dev.skw.application.workspace.DeleteWorkspaceUseCase
import dev.skw.application.workspace.GetWorkspaceService
import dev.skw.application.workspace.GetWorkspaceUseCase
import dev.skw.application.workspace.ListWorkspacesService
import dev.skw.application.workspace.ListWorkspacesUseCase
import dev.skw.application.workspace.SetWorkspacePropertyService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class WorkspaceConfiguration {
    @Bean fun workspaceJsonMapper(objectMapper: ObjectMapper) = PropertyJsonMapper(objectMapper)

    @Bean fun createWorkspaceUseCase(repository: WorkspaceRepository): CreateWorkspaceUseCase = CreateWorkspaceService(repository)

    @Bean fun getWorkspaceUseCase(repository: WorkspaceRepository): GetWorkspaceUseCase = GetWorkspaceService(repository)

    @Bean fun setWorkspacePropertyService(repository: WorkspaceRepository) = SetWorkspacePropertyService(repository)

    @Bean fun deleteWorkspacePropertyService(repository: WorkspaceRepository) = DeleteWorkspacePropertyService(repository)

    @Bean fun deleteWorkspaceUseCase(repository: WorkspaceRepository): DeleteWorkspaceUseCase = DeleteWorkspaceService(repository)

    @Bean fun listWorkspacesUseCase(repository: WorkspaceRepository): ListWorkspacesUseCase = ListWorkspacesService(repository)

    @Bean fun resourceEtag() = ResourceEtag()

    @Bean fun workspaceCursorCodec() = WorkspaceCursorCodec()

    @Bean fun workspaceRestMapper(
        json: PropertyJsonMapper,
        etag: ResourceEtag,
    ) = WorkspaceRestMapper(json, etag)
}
