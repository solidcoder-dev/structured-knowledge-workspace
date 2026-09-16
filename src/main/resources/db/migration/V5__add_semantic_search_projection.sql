CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;

CREATE TABLE skw.entry_semantic_embeddings (
    workspace_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    profile_id TEXT NOT NULL CHECK (btrim(profile_id) <> ''),
    source_version BIGINT NOT NULL CHECK (source_version >= 1),
    content_hash CHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    embedding public.vector NULL,
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (workspace_id, entry_id, profile_id),
    CONSTRAINT entry_semantic_embeddings_entry_fk
        FOREIGN KEY (workspace_id, entry_id)
        REFERENCES skw.entries (workspace_id, id)
        ON DELETE CASCADE,
    CONSTRAINT entry_semantic_embeddings_dimensions_ck
        CHECK (embedding IS NULL OR public.vector_dims(embedding) = dimensions)
);

COMMENT ON TABLE skw.entry_semantic_embeddings IS
    'Derived semantic projection; ON DELETE CASCADE is intentional because it is not a business resource.';
