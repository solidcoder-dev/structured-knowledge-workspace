CREATE TABLE skw.namespaces (
    workspace_id UUID NOT NULL,
    name TEXT COLLATE "C" NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT namespaces_pk PRIMARY KEY (workspace_id, name),
    CONSTRAINT namespaces_workspace_fk FOREIGN KEY (workspace_id)
        REFERENCES skw.workspaces (id) ON DELETE RESTRICT,
    CONSTRAINT namespaces_name_valid CHECK (
        char_length(name) BETWEEN 1 AND 128
        AND name ~ '^[a-z][a-z0-9]*$'
    )
);

CREATE TABLE skw.policies (
    workspace_id UUID NOT NULL,
    principal_id TEXT COLLATE "C" NOT NULL,
    scope_type TEXT COLLATE "C" NOT NULL,
    namespace TEXT COLLATE "C",
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT policies_pk PRIMARY KEY (workspace_id, principal_id, scope_type),
    CONSTRAINT policies_workspace_fk FOREIGN KEY (workspace_id)
        REFERENCES skw.workspaces (id) ON DELETE RESTRICT,
    CONSTRAINT policies_principal_valid CHECK (octet_length(principal_id) BETWEEN 1 AND 255 AND principal_id ~ '^[!-~]+$'),
    CONSTRAINT policies_namespace_fk FOREIGN KEY (workspace_id, namespace)
        REFERENCES skw.namespaces (workspace_id, name) ON DELETE RESTRICT,
    CONSTRAINT policies_scope_valid CHECK (
        (scope_type = 'WORKSPACE' AND namespace IS NULL)
        OR (scope_type = 'NAMESPACE' AND namespace IS NOT NULL)
    ),
    CONSTRAINT policies_namespace_valid CHECK (namespace IS NULL OR (char_length(namespace) BETWEEN 1 AND 128 AND namespace ~ '^[a-z][a-z0-9]*$')),
    CONSTRAINT policies_scope_unique UNIQUE (workspace_id, principal_id, scope_type, namespace)
);

CREATE TABLE skw.policy_permissions (
    workspace_id UUID NOT NULL,
    principal_id TEXT COLLATE "C" NOT NULL,
    scope_type TEXT COLLATE "C" NOT NULL,
    namespace TEXT COLLATE "C",
    permission TEXT COLLATE "C" NOT NULL,
    CONSTRAINT policy_permissions_pk PRIMARY KEY (workspace_id, principal_id, scope_type, permission),
    CONSTRAINT policy_permissions_policy_fk FOREIGN KEY (workspace_id, principal_id, scope_type, namespace)
        REFERENCES skw.policies (workspace_id, principal_id, scope_type, namespace) ON DELETE CASCADE,
    CONSTRAINT policy_permissions_valid CHECK (permission IN ('READ', 'CREATE', 'UPDATE', 'DELETE')),
    CONSTRAINT policy_permissions_scope_valid CHECK (
        (scope_type = 'WORKSPACE' AND namespace IS NULL)
        OR (scope_type = 'NAMESPACE' AND namespace IS NOT NULL)
    )
);

CREATE INDEX policies_lookup_idx ON skw.policies (workspace_id, principal_id, namespace);
