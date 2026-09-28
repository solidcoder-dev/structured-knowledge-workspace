package dev.skw.domain.accesscontrol

sealed interface Scope {
    data object Workspace : Scope

    data class WorkspaceNamespace(val namespace: Namespace) : Scope
}
