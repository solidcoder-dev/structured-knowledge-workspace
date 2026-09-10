-- Flyway owns this schema and executes this migration transactionally.
-- Do not modify an applied migration; use the next version instead.

CREATE FUNCTION skw.valid_properties(document JSONB)
RETURNS BOOLEAN
LANGUAGE plpgsql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog
AS $$
DECLARE
    property RECORD;
    element JSONB;
    element_type TEXT;
    expected_type TEXT;
BEGIN
    IF jsonb_typeof(document) <> 'object' THEN
        RETURN FALSE;
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(document)) > 256 THEN
        RETURN FALSE;
    END IF;
    FOR property IN SELECT key, value FROM jsonb_each(document) LOOP
        IF char_length(property.key) > 128
           OR property.key COLLATE "C" !~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$' THEN
            RETURN FALSE;
        END IF;
        CASE jsonb_typeof(property.value)
            WHEN 'string' THEN
                IF char_length(property.value #>> '{}') > 100000 THEN
                    RETURN FALSE;
                END IF;
            WHEN 'number', 'boolean' THEN
                NULL;
            WHEN 'array' THEN
                IF jsonb_array_length(property.value) > 1000 THEN
                    RETURN FALSE;
                END IF;
                expected_type := NULL;
                FOR element IN SELECT value FROM jsonb_array_elements(property.value) LOOP
                    element_type := jsonb_typeof(element);
                    IF element_type NOT IN ('string', 'number', 'boolean') THEN
                        RETURN FALSE;
                    END IF;
                    IF expected_type IS NOT NULL AND element_type <> expected_type THEN
                        RETURN FALSE;
                    END IF;
                    expected_type := element_type;
                    IF element_type = 'string' AND char_length(element #>> '{}') > 100000 THEN
                        RETURN FALSE;
                    END IF;
                END LOOP;
            ELSE
                RETURN FALSE;
        END CASE;
    END LOOP;
    RETURN TRUE;
END;
$$;

CREATE TABLE skw.workspaces (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    properties JSONB NOT NULL DEFAULT '{}'::JSONB,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT workspaces_properties_valid CHECK (skw.valid_properties(properties)),
    CONSTRAINT workspaces_version_positive CHECK (version >= 1),
    CONSTRAINT workspaces_dates_ordered CHECK (updated_at >= created_at)
);

CREATE TABLE skw.entries (
    workspace_id UUID NOT NULL,
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    properties JSONB NOT NULL DEFAULT '{}'::JSONB,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT entries_pk PRIMARY KEY (workspace_id, id),
    CONSTRAINT entries_workspace_fk FOREIGN KEY (workspace_id)
        REFERENCES skw.workspaces (id) ON DELETE RESTRICT,
    CONSTRAINT entries_properties_valid CHECK (skw.valid_properties(properties)),
    CONSTRAINT entries_version_positive CHECK (version >= 1),
    CONSTRAINT entries_dates_ordered CHECK (updated_at >= created_at)
);

CREATE TABLE skw.relationships (
    workspace_id UUID NOT NULL,
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    source_entry_id UUID NOT NULL,
    target_entry_id UUID NOT NULL,
    type TEXT COLLATE "C" NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT relationships_pk PRIMARY KEY (workspace_id, id),
    CONSTRAINT relationships_source_fk FOREIGN KEY (workspace_id, source_entry_id)
        REFERENCES skw.entries (workspace_id, id) ON DELETE RESTRICT,
    CONSTRAINT relationships_target_fk FOREIGN KEY (workspace_id, target_entry_id)
        REFERENCES skw.entries (workspace_id, id) ON DELETE RESTRICT,
    CONSTRAINT relationships_edge_unique UNIQUE
        (workspace_id, source_entry_id, target_entry_id, type),
    CONSTRAINT relationships_type_valid CHECK (
        char_length(type) BETWEEN 1 AND 128
        AND type ~ '^[a-z][a-z0-9]*([._-][a-z0-9]+)*$'
    )
);

-- These rows are edges, not mutable aggregates.
CREATE FUNCTION skw.reject_relationship_update()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $$
BEGIN
    RAISE EXCEPTION 'Relationships are immutable; delete and create a new edge'
        USING ERRCODE = '23514', CONSTRAINT = 'relationships_immutable';
END;
$$;

CREATE TRIGGER relationships_immutable
BEFORE UPDATE ON skw.relationships
FOR EACH ROW EXECUTE FUNCTION skw.reject_relationship_update();

CREATE INDEX workspaces_page_idx ON skw.workspaces (created_at, id);
CREATE INDEX entries_page_idx ON skw.entries (workspace_id, created_at, id);
CREATE INDEX entries_properties_idx ON skw.entries USING GIN (properties jsonb_ops);
-- The unique edge index already supports outgoing traversal and source FK checks.
CREATE INDEX relationships_incoming_idx
    ON skw.relationships (workspace_id, target_entry_id, type, id);
CREATE INDEX relationships_page_idx
    ON skw.relationships (workspace_id, created_at, id);
