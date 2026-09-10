-- Match per-Entry keyset pagination without changing applied migrations.
CREATE INDEX relationships_outgoing_page_idx
    ON skw.relationships (workspace_id, source_entry_id, created_at, id);
CREATE INDEX relationships_incoming_page_idx
    ON skw.relationships (workspace_id, target_entry_id, created_at, id);

-- The incoming page index also covers target FK checks. There is no
-- Workspace-wide relationship listing in the contract.
DROP INDEX skw.relationships_incoming_idx;
DROP INDEX skw.relationships_page_idx;

-- Keep the byte bound; visible ASCII makes bytes and characters agree.
-- Validation intentionally fails if existing rows violate the new contract.
ALTER TABLE skw.idempotency_requests
    ADD CONSTRAINT idempotency_key_ascii CHECK (key ~ '^[!-~]+$');
