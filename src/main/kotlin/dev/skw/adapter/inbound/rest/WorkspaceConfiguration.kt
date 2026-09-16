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
import dev.skw.application.idempotency.IdempotentExecutionService
import dev.skw.application.port.out.EntryRepository
import dev.skw.application.port.out.GraphCandidateFinder
import dev.skw.application.port.out.KnowledgeSearch
import dev.skw.application.port.out.RelationshipRepository
import dev.skw.application.port.out.TransactionRunner
import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.application.relationship.CreateRelationshipService
import dev.skw.application.relationship.CreateRelationshipUseCase
import dev.skw.application.relationship.DeleteRelationshipService
import dev.skw.application.relationship.DeleteRelationshipUseCase
import dev.skw.application.relationship.GetRelationshipService
import dev.skw.application.relationship.GetRelationshipUseCase
import dev.skw.application.relationship.ListEntryRelationshipsService
import dev.skw.application.relationship.ListEntryRelationshipsUseCase
import dev.skw.application.search.SearchEntriesService
import dev.skw.application.search.SearchEntriesUseCase
import dev.skw.application.transaction.ExecuteTransactionService
import dev.skw.application.transaction.ExecuteTransactionUseCase
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
    @Bean
    fun idempotentExecutionService(
        store: dev.skw.application.idempotency.IdempotencyStore,
        transactions: TransactionRunner,
    ) = IdempotentExecutionService(store, transactions)

    @Bean fun workspaceJsonMapper(objectMapper: ObjectMapper) = PropertyJsonMapper(objectMapper)

    @Bean fun canonicalJsonHasher(objectMapper: ObjectMapper) = CanonicalJsonHasher(objectMapper)

    @Bean fun transactionMutationJacksonModule() = TransactionMutationJacksonModule()

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

    @Bean fun searchCursorCodec() = SearchCursorCodec()

    @Bean fun searchRestMapper(
        json: PropertyJsonMapper,
        objectMapper: ObjectMapper,
        entryMapper: EntryRestMapper,
        cursorCodec: SearchCursorCodec,
        hasher: CanonicalJsonHasher,
    ) = SearchRestMapper(json, objectMapper, entryMapper, cursorCodec, hasher)

    @Bean fun searchEntriesUseCase(
        workspaces: WorkspaceRepository,
        entries: EntryRepository,
        search: KnowledgeSearch,
        graph: GraphCandidateFinder,
    ): SearchEntriesUseCase = SearchEntriesService(workspaces, entries, search, graph)

    @Bean fun createRelationshipUseCase(
        workspaces: WorkspaceRepository,
        entries: EntryRepository,
        relationships: RelationshipRepository,
    ): CreateRelationshipUseCase = CreateRelationshipService(workspaces, entries, relationships)

    @Bean fun getRelationshipUseCase(relationships: RelationshipRepository): GetRelationshipUseCase = GetRelationshipService(relationships)

    @Bean fun deleteRelationshipUseCase(relationships: RelationshipRepository): DeleteRelationshipUseCase =
        DeleteRelationshipService(relationships)

    @Bean fun listEntryRelationshipsUseCase(
        workspaces: WorkspaceRepository,
        entries: EntryRepository,
        relationships: RelationshipRepository,
    ): ListEntryRelationshipsUseCase = ListEntryRelationshipsService(workspaces, entries, relationships)

    @Bean fun relationshipCursorCodec() = RelationshipCursorCodec()

    @Bean
    fun transactionRestMapper(entryRestMapper: EntryRestMapper) = TransactionRestMapper(entryRestMapper)

    @Bean
    fun executeTransactionUseCase(
        workspaces: WorkspaceRepository,
        entries: EntryRepository,
        relationships: RelationshipRepository,
        transactions: TransactionRunner,
        createRelationship: CreateRelationshipUseCase,
        getRelationship: GetRelationshipUseCase,
        deleteRelationship: DeleteRelationshipUseCase,
    ): ExecuteTransactionUseCase =
        ExecuteTransactionService(
            workspaces,
            CreateEntryService(workspaces, entries, relationships, transactions),
            SetEntryPropertyService(entries),
            DeleteEntryPropertyService(entries),
            DeleteEntryService(entries),
            createRelationship,
            getRelationship,
            deleteRelationship,
            transactions,
        )
}
