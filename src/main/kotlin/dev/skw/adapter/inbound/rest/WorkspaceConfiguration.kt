package dev.skw.adapter.inbound.rest

import com.fasterxml.jackson.databind.ObjectMapper
import dev.skw.adapter.PropertyJsonMapper
import dev.skw.application.entry.CreateEntryService
import dev.skw.application.entry.CreateEntryUseCase
import dev.skw.application.entry.DeleteEntryPropertyService
import dev.skw.application.entry.DeleteEntryService
import dev.skw.application.entry.DeleteEntryUseCase
import dev.skw.application.entry.GetEntryService
import dev.skw.application.entry.GetEntryUseCase
import dev.skw.application.entry.ListEntriesService
import dev.skw.application.entry.ListEntriesUseCase
import dev.skw.application.entry.SetEntryPropertyService
import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.RelationshipRepository
import dev.skw.application.port.out.TransactionRunner
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

    @Bean fun createEntryUseCase(
        workspaces: WorkspaceRepository,
        entries: EntryRepository,
        relationships: RelationshipRepository,
        transactions: TransactionRunner,
    ): CreateEntryUseCase = CreateEntryService(workspaces, entries, relationships, transactions)

    @Bean fun getEntryUseCase(entries: EntryRepository): GetEntryUseCase = GetEntryService(entries)

    @Bean fun listEntriesUseCase(
        workspaces: WorkspaceRepository,
        entries: EntryRepository,
    ): ListEntriesUseCase = ListEntriesService(workspaces, entries)

    @Bean fun setEntryPropertyService(entries: EntryRepository) = SetEntryPropertyService(entries)

    @Bean fun deleteEntryPropertyService(entries: EntryRepository) = DeleteEntryPropertyService(entries)

    @Bean fun deleteEntryUseCase(entries: EntryRepository): DeleteEntryUseCase = DeleteEntryService(entries)

    @Bean fun entryCursorCodec() = EntryCursorCodec()

    @Bean fun entryRestMapper(
        json: PropertyJsonMapper,
        etag: ResourceEtag,
    ) = EntryRestMapper(json, etag)
}
