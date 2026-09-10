# Structured Knowledge Workspace

Structured Knowledge Workspace is a framework-independent tool for people and agents to manage, structure, connect, find, and govern information. Its future vision includes:

- Manage Entries
- Structure Information
- Connect Information
- Find Information
- Govern Changes

The project uses an API-first and contract-first approach. The OpenAPI document is the source of truth for the HTTP contract; endpoints are not generated from code annotations.

## Current contract

The API contract defines the framework-independent kernel:

- Workspaces isolate knowledge and search.
- Entries store generic scalar Properties and homogeneous scalar arrays.
- Relationships connect Entries directionally inside one Workspace.
- Search combines exact, full-text, semantic and bounded graph retrieval.
- Transactions let clients and agents change several resources atomically.

Domain concepts such as goals, capabilities, behaviours, comments, statuses and authors are not built-in resource types. A consuming framework represents them with Entries, Properties and Relationships and owns their semantic validation.

Stack and versions:

- JDK 21
- Kotlin 2.2.20
- Spring Boot 3.5.16 with Spring MVC
- Gradle 8.14.3 using the Kotlin DSL
- OpenAPI Generator Gradle Plugin 7.19.0
- PostgreSQL 17.11 for local development and integration tests
- Spring JDBC and Flyway (versions managed by Spring Boot)

The authoritative contract starts at [`api/openapi.yaml`](api/openapi.yaml) and is split into `paths/` and `components/` files for readability. Kotlin API interfaces and transport models are generated under `build/generated/openapi/` and are intentionally not versioned because they are reproducible build outputs.

## Local development

See [HTTP contract conventions](api/README.md) for module ownership, mutation
preconditions, ordering, retry guarantees and pagination semantics.

Install JDK 21 and Docker with Compose. The first execution needs Internet access
for Gradle, dependencies and the PostgreSQL image. Tests use an isolated
Testcontainers database and require Docker; no test uses your local data.

```bash
./gradlew openApiValidate
./gradlew openApiGenerate
./gradlew clean build
docker compose up -d --wait postgres
./gradlew bootRun --args='--spring.profiles.active=local'
```

Flyway applies migrations on startup and refuses invalid migration history.
See [persistence conventions](docs/persistence.md) for schema ownership, operational
configuration and migration rules. The app requires a database to start.
It listens on `127.0.0.1:8080` by default. Check process availability with:

```bash
curl http://localhost:8080/api/v1/health
```

Expected response:

```json
{"status":"UP"}
```

This endpoint checks process availability only; it does not check external dependencies. The business endpoints are contract definitions and do not have implementations yet. Authentication, authorization and a trustworthy append-only technical audit log remain future cross-cutting capabilities; business history can already be represented as Entries.
