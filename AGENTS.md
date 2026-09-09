# Contribution guidance

- Modify `api/openapi.yaml` first when the HTTP API changes.
- Never edit generated code under `build/generated/openapi/`.
- Keep transport DTOs outside the future domain model.
- Prefer small changes and avoid speculative abstractions.
- Run `./gradlew clean build` before finishing.
- Use Angular Commit Convention for commit messages.
- Do not disable checks to hide failures.
