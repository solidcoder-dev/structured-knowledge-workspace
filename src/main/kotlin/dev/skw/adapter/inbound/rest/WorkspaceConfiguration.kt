package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.workspace.CreateWorkspaceService
import dev.skw.application.workspace.CreateWorkspaceUseCase
import dev.skw.application.workspace.GetWorkspaceService
import dev.skw.application.workspace.GetWorkspaceUseCase
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class WorkspaceConfiguration {
    @Bean fun workspaceJsonMapper(objectMapper: ObjectMapper) = PropertyJsonMapper(objectMapper)

    @Bean fun createWorkspaceUseCase(repository: WorkspaceRepository): CreateWorkspaceUseCase = CreateWorkspaceService(repository)

    @Bean fun getWorkspaceUseCase(repository: WorkspaceRepository): GetWorkspaceUseCase = GetWorkspaceService(repository)

    @Bean fun workspaceRestMapper(json: PropertyJsonMapper) = WorkspaceRestMapper(json)
}
