package dev.skw.application.port.out.accesscontrol

import dev.skw.domain.accesscontrol.Namespace
import dev.skw.domain.workspace.WorkspaceId

interface NamespaceRepository {
    fun register(
        workspaceId: WorkspaceId,
        namespace: Namespace,
    ): NamespaceRegistrationResult

    fun list(workspaceId: WorkspaceId): List<Namespace>
}

enum class NamespaceRegistrationResult {
    REGISTERED,
    ALREADY_REGISTERED,
}
