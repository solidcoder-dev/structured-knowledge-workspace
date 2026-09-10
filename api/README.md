# HTTP contract conventions

This document supplements OpenAPI 3.0.3 where invariants cannot be expressed by
its schema vocabulary. The contract is not an implementation.

## Layout

- `openapi.yaml`: public entry point, paths and named component exports.
- `paths/`: HTTP operations grouped by resource/capability.
- `components/properties.yaml`: reusable value and key rules.
- `components/common.yaml`: transport metadata and process health.
- `components/workspaces.yaml`, `entries.yaml`, `relationships.yaml`: core resources.
- `components/search.yaml`, `transactions.yaml`: capability-specific messages.
- `components/errors.yaml`, `responses.yaml`, `headers.yaml`, `parameters.yaml`:
  shared HTTP contracts.

References point directly to their owner. Do not duplicate shared schemas or make
core resources depend on search or transaction messages. Files are grouped by
responsibility, not one file per field.

## Values and identity

Entry and Workspace properties default to an empty map; empty resources are valid.
Keys must match PropertyName on creation as well as individual property writes.
OpenAPI 3.0.3 cannot enforce map key patterns: the adapter must validate them.
Dates, exact monetary examples and URLs may be strings; their semantics are owned
by the consuming framework. PropertyValue uses anyOf so an empty homogeneous array
does not accidentally fail several overlapping oneOf array branches.

IDs are immutable UUIDs, never reused within their resource scope, including after
deletion. Client-supplied ID collisions return 409. Relationships require live
endpoints in the same Workspace; missing or foreign endpoints return 404.
Source, target and type are immutable; changing them means delete plus create.
The triple (source, target, type) is unique. Cycles and self-links are allowed by
the kernel; frameworks may prohibit them. No resource moves between Workspaces.

Initial relationships require client-generated IDs. Successful creation persists
those IDs unchanged, so returning only the Entry does not hide relationship IDs.

## Preconditions and retries

Every returned resource, including list/search/transaction results, has
`metadata.etag`: an opaque strong tag including its quotes. Individual resource
response ETag headers match that value. Clients must not derive tags from version.
Property and position mutations use the owning resource's tag and return its
complete representation. This parent-level concurrency convention is deliberate.

Missing If-Match returns 428; malformed, wildcard or multiple tags return 400;
an outdated tag returns 412. No-op writes preserve version and updatedAt.
Conditional DELETE of an absent resource returns 404, not an unconditional 204.
Successful DELETE returns 204; deleting an Entry with any relationships or a
nonempty Workspace returns 409. No cascades, including implicit comment deletion.

Idempotency keys are scoped to method plus canonical path (and authenticated
principal when authentication is introduced). Successful results, status and
headers are retained for at least 24 hours. Equivalent JSON bodies with reordered
object keys count as identical; array order remains significant. A different
payload returns 409 IDEMPOTENCY_KEY_REUSED. Concurrent identical requests are
serialized or return 409 IDEMPOTENCY_IN_PROGRESS; no duplicate mutation occurs.
After expiry, a new execution is possible; supplied UUIDs still cannot be reused.
Invalid or rolled-back requests do not reserve the key.

## Atomic transactions

Mutations execute in order and roll back together on any failure. expectedVersion
is checked against the state immediately before that mutation, including earlier
mutations in this transaction. Each effective write increments version once.
New resources start at 1. Missing resources return 404, stale expectedVersion
returns 409 VERSION_CONFLICT, and semantic violations return 422.

Create Entries before referencing them. Explicitly delete relationships before
deleting their endpoints. Swapping positions requires two SET_RELATIONSHIP_POSITION
mutations in one transaction. Gaps/ties are legal and no implicit renumbering occurs.
Results contain each surviving changed resource once, at its final version.
Created-then-deleted resources appear only in deleted IDs. Error pointers identify
the failed mutation, for example /mutations/2/expectedVersion.

## Ordering and retrieval

Entry/Workspace listings order by (createdAt, id) ascending. Relationship listings
order by (sourceEntryId, type, position absent-last, id), all ascending; self-links
are returned once for BOTH. Position groups are (sourceEntryId, type). Setting a
position affects only that relationship; DELETE position removes the ordering hint.

Graph search follows distinct reachable nodes up to maxDepth, excludes the starting
node, and prevents revisiting nodes through cycles. Omitted/empty relationshipTypes
means all types. Missing starting Entry returns 404. Graph traversal must be bounded;
if more than 10,000 distinct nodes must be visited, return 422 GRAPH_LIMIT_EXCEEDED,
never silently return an incomplete graph.

Cursors are opaque, bound to endpoint, Workspace and normalized query/filter/order,
and expire after 15 minutes. Malformed, mismatched or expired cursors return 400.
The first page pins candidate IDs and their order for subsequent pages; new inserts
do not enter that sequence. Deleted resources are skipped, surviving resources use
current representations and must still satisfy exact/graph filters. A page may
therefore contain fewer than limit results and still have nextCursor.

Search ranks by score descending with id as tie-breaker; scores are query-local,
not probabilities or comparable across queries/models. Exact/filter-only matches
have score 1. Highlights are plain text, never trusted HTML. EXACT matches complete
string properties or complete string array elements. TEXT and HYBRID provide
read-after-write visibility for lexical matches, not guaranteed top-k inclusion.
Semantic indexing may lag; embeddings are derived, versioned and rebuildable.
Stale semantic candidates must be checked against live Entries before returning.
If semantic service is unavailable, return 503 for SEMANTIC/HYBRID; do not silently
change the requested mode. TEXT and EXACT remain independently usable.

## Validation and scope

Run `./gradlew clean build` before merging; it includes OpenAPI validation and generation.
YAML parsing and resolved references alone do not prove generated Kotlin compiles
or that anyOf/oneOf models serialize correctly. Do not edit generated DTOs.

Authentication, authorization and append-only audit storage are deferred. Local
operation without them is not a production security boundary. Domain authors and
business history properties are not a trusted technical audit log.
