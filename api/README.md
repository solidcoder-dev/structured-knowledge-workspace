# HTTP contract conventions

The OpenAPI 0.3.1 contract describes the lean model. Business endpoints remain
unimplemented; the health endpoint and PostgreSQL/Flyway bootstrap are executable.

## Ownership

The public entry point is `openapi.yaml`; HTTP operations live in `paths/`.
Schemas in `components/` are grouped by properties, core resources, search,
transactions and shared HTTP concerns. Core resources must not depend on
transaction or search messages. References point directly to the owning file.

## Identity and properties

The server generates UUIDs for Workspaces, Entries and Relationships. Create
requests reject supplied resource IDs. Database defaults generate IDs when
inserting; idempotency responses recover those IDs after lost responses.
There is no permanent retired-ID registry or absolute never-reuse guarantee.

Properties default to an empty object. Keys follow PropertyName and values are
strings, numbers, booleans or homogeneous scalar arrays; empty arrays are valid.
Nested objects, nested/mixed arrays and null are invalid. OpenAPI 3.0.3 does not
express map-key constraints; request validation and the database both enforce them.
PostgreSQL JSONB also bounds numeric values to its numeric range and rejects U+0000;
the future adapter must report unsupported values as 422, not an internal error.
PropertyValue uses anyOf so empty arrays remain valid despite overlapping branches.

## Relationships

Edges have an ID, Workspace, source, target, type and creation timestamp.
They have no version, ETag, position or update operation. To replace an edge,
delete it and create a new one; the new instance receives a new ID.
The triple (source, target, type) is unique within a Workspace; duplicates return 409.
Both endpoints must exist in that Workspace; missing/foreign endpoints return 404.
Cycles and self-links are allowed; framework-specific restrictions belong above.

Entry creation may include initial edges to existing Entries. Its response contains
the Entry plus all created Relationships in request order, including generated IDs.
The response ETag refers to the Entry. The whole operation succeeds or rolls back.
Deleting a Relationship requires only its ID. Deleting an absent edge returns 404.

## Mutable resource preconditions

Workspace and Entry responses include metadata.etag and metadata.version, also in
lists/search/transaction results. ETags are opaque and include quotes. The ETag
header agrees with the metadata value; clients must not derive it from version.
Property mutations use the owning Entry/Workspace tag and return that resource.
Only effective changes increment version and update updatedAt; no-ops preserve both.
Missing If-Match returns 428; malformed/multiple/wildcard tags return 400, stale
tags 412. Conditional deletion of an absent Entry/Workspace returns 404.
Successful resource deletion returns 204. A Workspace with Entries, or an Entry
with any incoming/outgoing edge, cannot be deleted (409). No automatic cascades.

## Idempotency

Scope is HTTP method plus canonical route, including Workspace and the authenticated
principal when authentication is added. Keys contain 1–255 visible ASCII characters
(U+0021 through U+007E), without spaces; character and byte limits therefore agree.
Equivalent JSON bodies ignore object-key order but preserve array order and values.
Successful responses, including status, Location, ETag and body, are retained at
least 24 hours. A different payload under the same live key returns 409
IDEMPOTENCY_KEY_REUSED. Same-key requests must be serialized before executing any
mutation. The application may use a transaction-scoped advisory lock derived from
scope/key; a hash collision only serializes unrelated requests.
A unique completed-response row alone is not a substitute for coordinating execution.

Store the completed response in the same transaction as the business writes.
Rolled-back requests leave no record. Expired records are removed/replaced under
the same lock; after expiry a retry may execute again. Cleanup uses expires_at.

## Atomic transactions

All mutations execute in array order in one PostgreSQL transaction. CREATE_ENTRY
may declare a unique localRef such as capability. Subsequent CREATE_RELATIONSHIP
uses sourceEntryRef/targetEntryRef, either an existing UUID or @capability.
Duplicate, unknown or forward local references return 422 and roll back everything.
Initial relationships inside CREATE_ENTRY refer only to already persisted UUIDs;
use CREATE_RELATIONSHIP to connect new Entries. createdEntryIds maps declared local
references to generated UUIDs. entries/relationships include surviving affected
resources once, at their final state. Initial relationships are included too.
Updates/deletes of Entries use persisted UUIDs and expectedVersion.
expectedVersion is checked immediately before each mutation; each effective change
increments once, including multiple writes to the same Entry within the transaction.
New Entries start at version 1. Stale versions return 409 VERSION_CONFLICT.
Delete edges explicitly before deleting their endpoints. A failed operation rolls
back the entire request and identifies its index in the problem detail pointer.

## Pagination and search

Lists use keyset pagination ordered by (created_at, id) ascending within a Workspace.
Cursors are opaque, bound to route, Workspace, filters and order; malformed or
mismatched cursors return 400. No query session or snapshot is persisted.
Inserts/deletes between pages can affect results; these listings are not exports
of a frozen point in time. Relationship lists use the same order; BOTH emits
self-links once. The database's ordered indexes support these listing paths.

Search orders by score descending with id as tie-breaker. Its cursor includes
continuation values; reranking can cause repeats or omissions across pages.
Scores are query-local; exact/filter-only matches score 1. Highlights are plain text.
Filters and graph candidates intersect before ranking. Graph traversal excludes
the starting node, deduplicates visits, follows at most maxDepth and rejects more
than 10,000 visited nodes with 422 GRAPH_LIMIT_EXCEEDED.
EQUALS compares the entire JSON value including array order. JSONB containment
alone is insufficient for array equality. TEXT/EXACT must read committed data;
semantic indexes are derived and may lag. Deleted Entries are never returned.
Unavailable semantic retrieval returns 503 for SEMANTIC/HYBRID.
Search implementations and embedding storage are deferred; the bootstrap adds
only the core JSONB and relational indexes, not a semantic service.

## Scope and validation

Flyway owns the database schema; see ../docs/persistence.md.
Run `./gradlew clean build` to validate/generate OpenAPI, compile Kotlin and run
PostgreSQL integration tests. This requires JDK 21, Docker and dependency access.
Authentication, authorization and trusted audit storage remain separate future work.
Business author/history properties do not constitute an immutable audit log.
