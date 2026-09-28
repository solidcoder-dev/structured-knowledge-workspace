package dev.skw.application.port.out.accesscontrol

import dev.skw.domain.accesscontrol.Policy
import dev.skw.domain.accesscontrol.PrincipalId
import dev.skw.domain.workspace.WorkspaceId

interface PolicyRepository {
    fun save(
        workspaceId: WorkspaceId,
        policy: Policy,
    )

    fun find(
        workspaceId: WorkspaceId,
        principalId: PrincipalId,
    ): List<Policy>
}
