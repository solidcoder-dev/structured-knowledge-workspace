package dev.skw.application.workspace

import dev.skw.application.port.out.WorkspaceRepository
import dev.skw.domain.workspace.Workspace
import dev.skw.domain.workspace.WorkspaceId
import java.time.Instant

data class ListWorkspacesQuery(
    val limit: Int,
    val cursor: WorkspaceCursor? = null,
)

data class WorkspaceCursor(
    val createdAt: Instant,
    val id: WorkspaceId,
)

data class WorkspacePageRequest(
    val limit: Int,
    val after: WorkspaceCursor? = null,
)

data class WorkspacePage(
    val items: List<Workspace>,
    val nextCursor: WorkspaceCursor?,
)

fun interface ListWorkspacesUseCase {
    fun list(query: ListWorkspacesQuery): WorkspacePage
}

class ListWorkspacesService(
    private val repository: WorkspaceRepository,
) : ListWorkspacesUseCase {
    override fun list(query: ListWorkspacesQuery): WorkspacePage {
        require(query.limit in 1..100) { "Workspace page limit must be between 1 and 100" }
        return repository.list(
            WorkspacePageRequest(
                limit = query.limit,
                after = query.cursor,
            ),
        )
    }
}
