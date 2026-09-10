# PostgreSQL and Flyway

The bootstrap provides database connectivity, four tables, integrity constraints
and indexes. Business repositories, HTTP handlers, idempotency orchestration,
search and cleanup jobs remain to be implemented. Spring auto-configures
NamedParameterJdbcTemplate and the JDBC transaction manager; no ORM owns DDL.

## Schema

Flyway creates the skw schema and its flyway_schema_history table.
V1 defines workspaces, entries and immutable relationships.
V2 adds completed idempotency responses, including headers and expiration.
V3 aligns relationship pagination indexes and restricts idempotency keys to visible
ASCII. It validates existing rows and fails if incompatible keys are present.
Use schema-qualified SQL in adapters; do not rely on a caller's search_path.

IDs default to gen_random_uuid(), provided by PostgreSQL without an extension.
Repository INSERTs must omit id and use RETURNING; clients cannot choose IDs.
Workspace/Entry versions start at 1. Future repository updates must atomically
check the expected version, increment it and set updated_at only on an effective
change. No trigger silently manages versions. Treat missing rows separately from
stale versions. Relationship updates are prohibited by a database trigger.

Composite foreign keys enforce same-workspace links. RESTRICT prevents implicit
deletion. The unique edge index protects duplicate connections even under
concurrent writes. Self-links and cycles remain allowed.
The properties validator enforces map shape, keys, scalar types, homogeneous
arrays and the contract's size limits. PostgreSQL numeric/Unicode limits also
apply. Avoid converting JSON numbers through Double in future adapters.

List indexes cover created_at/id within each Workspace. The unique edge index
supports outgoing traversal; per-source and per-target indexes include created_at/id
for keyset pagination and foreign-key checks. Optional type filters are residual
filters; tune additional indexes only with measured queries.
GIN jsonb_ops supports property containment/existence candidates; equality checks
must additionally preserve whole-array order. Text and vector indexes will be
chosen with their actual search queries, rather than materializing embeddings now.

## Migration rules

- Flyway is the only schema initializer; spring.sql.init.mode=never.
- Versioned SQL uses V<number>__description.sql. Commit migrations with the change.
- Never edit a migration already applied to a shared database; add a new version.
- Do not use IF NOT EXISTS to hide unexpected schema drift.
- Keep baseline-on-migrate and out-of-order disabled. Never repair checksums blindly.
- clean is disabled. Migrations do not drop or truncate user data.
- These migrations execute transactionally on PostgreSQL. Do not add manual
  BEGIN/COMMIT inside them.
- Initial indexes are created on empty tables. For large existing tables, plan
  lock impact; CREATE INDEX CONCURRENTLY needs a separate nontransactional migration.
- Changes to valid_properties must account for existing CHECK constraints and
  explicitly revalidate existing data; do not silently change the function alone.

Flyway validates migration history/checksums, not arbitrary live-schema drift.
Integration tests check the resulting database behavior.

## Local operation

Run docker compose up -d --wait postgres, then start Spring with the local profile.
The database binds only to loopback and uses a named volume. Local credentials
are explicit development values; do not activate that profile in production.
docker compose down stops containers and preserves the volume. Removing a volume
would delete local data and is not part of the normal workflow.

For other environments set SKW_DB_URL, SKW_DB_USER and SKW_DB_PASSWORD.
Supply TLS options in the JDBC URL according to the deployment.
Use a runtime database role with data privileges only and a separate migration
owner. Spring supports SPRING_FLYWAY_URL, SPRING_FLYWAY_USER and
SPRING_FLYWAY_PASSWORD for those migration credentials. Provision roles and grants
outside application migrations; do not embed production passwords or CREATE ROLE.
If migrations run as a deployment job, explicitly disable in-app Flyway there.

## Idempotency implementation contract

Only completed successful responses are stored. Acquire a transaction-scoped lock
derived from scope/key before reading or writing, then compare request_hash,
execute the mutation and persist the response in the same transaction.
The primary key alone cannot prevent duplicated side effects before its insert.
Canonicalize request JSON before SHA-256 hashing. Scope includes method, route,
Workspace and eventually principal; keep its UTF-8 representation within 1024 bytes
and keys to 1–255 visible ASCII characters without spaces to bound B-tree entry size.
Header JSON must preserve replay-relevant headers, including multiple values.
Cleanup deletes expired rows in bounded batches; no scheduler is included yet.
Do not retain transaction locks across remote embedding or other network calls.

## Verification

Run ./gradlew clean build with JDK 21 and Docker. Tests run the actual migrations
on PostgreSQL 17.11, verify repeat execution, shape constraints, uniqueness,
cross-workspace rejection, restricted deletion, optimistic writes and rollback.
The health smoke test also starts with a migrated PostgreSQL database.
An H2 database is not a substitute for these PostgreSQL constraints.
