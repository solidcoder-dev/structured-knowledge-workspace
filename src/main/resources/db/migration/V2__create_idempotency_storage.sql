-- Completed responses only. The future application must serialize same-key
-- requests before mutation and commit this row with the corresponding writes.
CREATE TABLE skw.idempotency_requests (
    scope TEXT COLLATE "C" NOT NULL,
    key TEXT COLLATE "C" NOT NULL,
    request_hash TEXT COLLATE "C" NOT NULL,
    response_status SMALLINT NOT NULL,
    response_headers JSONB NOT NULL DEFAULT '{}'::JSONB,
    response_body BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT idempotency_requests_pk PRIMARY KEY (scope, key),
    CONSTRAINT idempotency_scope_length CHECK (octet_length(scope) BETWEEN 1 AND 1024),
    CONSTRAINT idempotency_key_length CHECK (octet_length(key) BETWEEN 1 AND 255),
    CONSTRAINT idempotency_hash_valid CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_status_success CHECK (response_status BETWEEN 200 AND 299),
    CONSTRAINT idempotency_headers_object CHECK (jsonb_typeof(response_headers) = 'object'),
    CONSTRAINT idempotency_retention CHECK (expires_at >= created_at + INTERVAL '24 hours')
);

CREATE INDEX idempotency_expiry_idx ON skw.idempotency_requests (expires_at);
