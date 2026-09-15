package dev.skw.application.port.out

import dev.skw.application.entry.EntryCursor
import dev.skw.application.entry.EntryPage
import dev.skw.domain.Version
import dev.skw.domain.entry.Entry
import dev.skw.domain.entry.EntryId
import dev.skw.domain.workspace.WorkspaceId

interface EntryRepository {
    fun save(entry: Entry): Entry

    fun findById(
        workspaceId: WorkspaceId,
        entryId: EntryId,
    ): Entry?

    fun findExistingIds(
        workspaceId: WorkspaceId,
        ids: Set<EntryId>,
    ): Set<EntryId>

    fun saveIfVersion(
        entry: Entry,
        expectedVersion: Version,
    ): SaveResult

    fun delete(
        workspaceId: WorkspaceId,
        entryId: EntryId,
        expectedVersion: Version,
    ): EntryDeleteResult

    fun list(request: EntryPageRequest): EntryPage
}

data class EntryPageRequest(
    val workspaceId: WorkspaceId,
    val limit: Int,
    val after: EntryCursor? = null,
)

enum class EntryDeleteResult { DELETED, NOT_FOUND, VERSION_CONFLICT, CONNECTED }
