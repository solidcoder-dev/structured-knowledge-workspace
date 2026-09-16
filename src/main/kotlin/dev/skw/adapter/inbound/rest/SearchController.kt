package dev.skw.adapter.inbound.rest

import dev.skw.adapter.inbound.rest.generated.api.SearchApi
import dev.skw.adapter.inbound.rest.generated.model.SearchRequest
import dev.skw.adapter.inbound.rest.generated.model.SearchResponse
import dev.skw.application.search.SearchEntriesUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class SearchController(
    private val search: SearchEntriesUseCase,
    private val mapper: SearchRestMapper,
) : SearchApi {
    override fun searchEntries(
        workspaceId: UUID,
        searchRequest: SearchRequest,
    ): ResponseEntity<SearchResponse> = ResponseEntity.ok(mapper.toResponse(search.search(mapper.toQuery(workspaceId, searchRequest))))
}
