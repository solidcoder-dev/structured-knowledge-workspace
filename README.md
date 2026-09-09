# Structured Knowledge Workspace

Structured Knowledge Workspace is a framework-independent tool for people and agents to manage, structure, connect, find, and govern information. Its future vision includes:

- Manage Entries
- Structure Information
- Connect Information
- Find Information
- Govern Changes

The project uses an API-first and contract-first approach. The OpenAPI document is the source of truth for the HTTP contract; endpoints are not generated from code annotations.

## Current bootstrap

This repository is a local bootstrap. Business capabilities are pending; the current implementation only exposes the initial process availability endpoint.

Stack and versions:

- JDK 21
- Kotlin 2.2.20
- Spring Boot 3.5.16 with Spring MVC
- Gradle 8.14.3 using the Kotlin DSL
- OpenAPI Generator Gradle Plugin 7.19.0

The authoritative contract is [`api/openapi.yaml`](api/openapi.yaml). Kotlin API interfaces and transport models are generated under `build/generated/openapi/` and are intentionally not versioned because they are reproducible build outputs.

## Local development

The first execution needs Internet access to download Gradle and project dependencies.

```bash
./gradlew openApiValidate
./gradlew openApiGenerate
./gradlew clean build
./gradlew bootRun
```

The application listens on `127.0.0.1:8080` by default. Check process availability with:

```bash
curl http://localhost:8080/api/v1/health
```

Expected response:

```json
{"status":"UP"}
```

This endpoint checks process availability only; it does not check external dependencies. Entries, properties, collections, relationships, search, and change governance remain future product capabilities.
