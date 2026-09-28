ALTER TABLE skw.policy_permissions
    DROP CONSTRAINT policy_permissions_policy_fk;

ALTER TABLE skw.policies
    DROP CONSTRAINT policies_pk,
    DROP CONSTRAINT policies_scope_unique;

ALTER TABLE skw.policies
    ADD CONSTRAINT policies_identity UNIQUE NULLS NOT DISTINCT (workspace_id, principal_id, scope_type, namespace);

ALTER TABLE skw.policy_permissions
    DROP CONSTRAINT policy_permissions_pk;

ALTER TABLE skw.policy_permissions
    ADD CONSTRAINT policy_permissions_identity UNIQUE NULLS NOT DISTINCT (workspace_id, principal_id, scope_type, namespace, permission);

ALTER TABLE skw.policies
    DROP CONSTRAINT policies_namespace_valid;

ALTER TABLE skw.policies
    ADD CONSTRAINT policies_namespace_valid CHECK (
        namespace IS NULL OR (char_length(namespace) BETWEEN 1 AND 64 AND namespace ~ '^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$')
    );

ALTER TABLE skw.namespaces
    DROP CONSTRAINT namespaces_name_valid;

ALTER TABLE skw.namespaces
    ADD CONSTRAINT namespaces_name_valid CHECK (
        char_length(name) BETWEEN 1 AND 64
        AND name ~ '^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$'
    );

ALTER TABLE skw.policy_permissions
    ADD CONSTRAINT policy_permissions_policy_fk FOREIGN KEY (workspace_id, principal_id, scope_type, namespace)
        REFERENCES skw.policies (workspace_id, principal_id, scope_type, namespace) ON DELETE CASCADE;
