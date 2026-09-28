# ADR-007: Generic access-control integration contract

- Status: Proposed for the future access-control implementation
- Date: 2026-09-28
- Scope: namespace-aware knowledge mutations, policy lookup, atomic authorization and idempotency

## Context

SKW currently stores generic Workspaces, Entries, Properties and Relationships.
The current application services are framework-independent, but they do not yet
resolve a principal or enforce authorization. The HTTP controllers call the
mutation use cases directly. `IdempotencyScope` already has an optional
`principal` component, while the current controllers leave it unset.

This ADR records the boundary that a future implementation must satisfy. It is
not evidence that authorization is already enforced. In particular, the current
repository must not be described as providing a security guarantee until the
contracts below are implemented and covered by integration tests.

## Decision

The current HTTP integration seam is `X-Principal-Id`. It supplies an opaque
`PrincipalId` to the application and is not authentication or proof of identity.
Authentication remains outside SKW and may replace this resolver later.

Namespaces use `[a-z][a-z0-9]*(?:-[a-z0-9]+)*` (1–64 characters). A qualified
knowledge identifier is `namespace.local`; the namespace is the first segment
before `.`, while the local identifier follows the existing property/type
identifier grammar. Namespace strings themselves never contain `.`.

Access Control is a separate domain concern from Knowledge. The authorization
kernel receives an authenticated `PrincipalId` and a requested operation; it does
not know about users, applications, agents, products, or other consumer types.
Authentication establishes the principal outside the domain model.

The minimum policy vocabulary is:

```text
Permission = READ | CREATE | UPDATE | DELETE
Scope      = Workspace(workspaceId)
           | WorkspaceNamespace(workspaceId, namespace)
Policy     = PrincipalId + Scope + set<Permission>
```

There are no roles, groups, explicit denies, inheritance, conditions, or
resource-level ACLs in this version. Missing policy matches deny.

`Namespace` is vocabulary, not ownership. A qualified property name or
relationship type is split deterministically into `namespace` and local name
using the namespace identifier contract. The Entry remains a shared structural
node and is never owned by a namespace. A relationship is authorized from its
type namespace, not from either endpoint's content.

## Mutation-path inventory

Every supported mutation path must carry the principal to the application
boundary and must pass authorization before performing its write.

| Current path | Mutation | Required authorization scope |
| --- | --- | --- |
| `POST /api/v1/workspaces` → `CreateWorkspaceService` | Create Workspace | Workspace structural permission |
| `DELETE /api/v1/workspaces/{workspaceId}` → `DeleteWorkspaceService` | Delete Workspace | Workspace structural permission |
| `PUT /api/v1/workspaces/{workspaceId}/properties/{propertyName}` → `SetWorkspacePropertyService` | Create/update workspace property | Namespace of `propertyName`; workspace fallback only if explicitly defined by the namespace migration contract |
| `DELETE /api/v1/workspaces/{workspaceId}/properties/{propertyName}` → `DeleteWorkspacePropertyService` | Delete workspace property | Namespace of `propertyName` |
| `POST /api/v1/workspaces/{workspaceId}/entries` → `CreateEntryService` | Create Entry, including initial properties and relationships | Workspace structural permission, plus CREATE for every property namespace and every initial relationship-type namespace |
| `DELETE /api/v1/workspaces/{workspaceId}/entries/{entryId}` → `DeleteEntryService` | Delete Entry | Workspace structural permission; namespace permission alone is insufficient |
| `PUT /api/v1/workspaces/{workspaceId}/entries/{entryId}/properties/{propertyName}` → `SetEntryPropertyService` | Create/update Entry property | Namespace of `propertyName` |
| `DELETE /api/v1/workspaces/{workspaceId}/entries/{entryId}/properties/{propertyName}` → `DeleteEntryPropertyService` | Delete Entry property | Namespace of `propertyName` |
| `POST /api/v1/workspaces/{workspaceId}/relationships` → `CreateRelationshipService` | Create Relationship | CREATE for the relationship type namespace only |
| `DELETE /api/v1/workspaces/{workspaceId}/relationships/{relationshipId}` → `DeleteRelationshipService` | Delete Relationship | DELETE for the persisted relationship type namespace |
| `POST /api/v1/workspaces/{workspaceId}/transactions` → `ExecuteTransactionService` | Any combination above | Complete pre-authorization of every mutation before the first write |

Reads are not granted by write permission. The future implementation must also
define whether READ filtering is applied per namespaced knowledge item or only at
the resource boundary; it must not accidentally expose this unresolved choice as
an authorization side effect.

## Atomic authorization contract

`ExecuteTransactionService` currently executes mutations sequentially inside a
database transaction. Authorization must be inserted as a separate preflight
phase inside the same transaction boundary:

1. Resolve all persisted resources needed to identify relationship namespaces.
2. Resolve all local references and validate every mutation shape.
3. Produce the complete set of required `(operation, scope)` checks, including
   properties and initial relationships embedded in `CREATE_ENTRY`.
4. Evaluate every check for the same `PrincipalId`.
5. If any check denies, abort before invoking any mutation repository or write
   use case.
6. Only then execute the existing ordered mutation plan.

A denied transaction must therefore leave no Entry, Property, Relationship,
version, idempotency record, or other observable mutation behind. The existing
version checks, relationship endpoint checks, no-cascade deletion rules and
transaction rollback semantics remain in force after authorization succeeds.

The authorization port should be narrow enough to support a pure in-memory
policy implementation and a persistence-backed policy lookup. Persistence
adapters may load policies and enforce database constraints, but they do not
decide namespace or permission semantics.

## Structural and namespace rules

- CREATE Entry and DELETE Entry are structural Workspace operations.
- CREATE/Delete Workspace and namespace registration are structural Workspace
  operations.
- Workspace structural authority does not imply authority to mutate any
  namespace.
- Namespace authority does not imply authority to delete an Entry or Workspace.
- A principal with `alpha` CREATE/UPDATE/DELETE may modify `alpha.*` on an Entry
  that also contains `beta.*` without permission for `beta`.
- `alpha.references` may connect Entries whose properties and other
  relationships use unrelated namespaces.
- Relationship deletion uses the stored relationship type; endpoint namespaces
  are irrelevant.
- Entry deletion continues to reject connected Entries; authorization must not
  turn that conflict into a cascade.

## Principal and HTTP boundary

The transport boundary is responsible for obtaining the authenticated principal
and passing only a `PrincipalId` inward. Controllers must not contain policy
matching logic, inspect policy tables, or infer identity from an HTTP header in
the domain/application layer.

There is no anonymous principal. Once authorization is enabled, a mutation with
no authenticated principal must fail at the boundary and must not be treated as
a principal with an empty policy. Authentication and its HTTP status mapping are
outside this ADR; the application contract still requires that a principal be
present for every mutation command.

## Idempotency and retries

The idempotency scope for every mutation includes method, canonical route,
workspace where applicable, and the authenticated principal. This is required so
that one principal cannot replay or consume another principal's idempotency key.
The current `IdempotencyScope` shape supports this, but all mutation controllers
must populate `principal` consistently before access control is enabled.

Authorization must run inside the existing idempotent transaction, after the
scope/key lock is acquired and before the mutation action. A denied request must
not store a completed response. A successful replay returns the original result
for the same principal and request hash without re-running authorization or the
mutation; a different principal is a different idempotency scope.

The existing canonical request hashing, 24-hour retention, same-key
serialization, ETag/version semantics and rollback behavior remain unchanged.

## Compatibility decision

This docs-only change does not alter the current API or silently reinterpret
existing identifiers. Existing clients continue to observe the current behavior
until an API version or explicitly documented rollout enables authorization.

The implementation that enables authorization must make identifier compatibility
explicit at the API boundary: qualified names must have one deterministic
namespace extraction rule, and any pre-existing unqualified property/type names
must either be migrated or assigned a documented compatibility policy. They must
not be granted access by an accidental empty namespace or by a namespace chosen
from an Entry. New code must not hard-code consumer vocabularies.

When enforcement is enabled, mutation requests without a principal are rejected
as unauthenticated rather than being implicitly authorized for compatibility.

## Verification required before accepting this ADR

The implementation must add tests proving, at minimum:

- namespace CREATE/UPDATE/DELETE permissions and deny-by-default;
- cross-namespace Entry enrichment and cross-vocabulary Relationships;
- structural permissions do not grant namespace mutation and namespace
  permissions do not grant Entry deletion;
- connected Entry deletion remains a conflict;
- a transaction requiring both `alpha` and `beta` is rejected before any write
  when either permission is missing;
- initial Entry properties and initial Relationships are pre-authorized;
- Relationship deletion checks the stored type namespace;
- duplicate namespace registration and invalid identifiers have deterministic
  domain/API/database results;
- idempotency scope differs by principal and denied requests leave no stored
  result;
- concurrent policy/knowledge operations cannot bypass the policy decision.

Architecture tests must continue to prove that domain and application code do
not depend on Spring, JDBC, PostgreSQL, generated OpenAPI classes, HTTP headers,
or authentication providers.

## Deferred

Authentication, policy administration endpoints, READ projection/filtering,
roles, groups, explicit deny rules, inheritance, conditions, per-resource ACLs,
and audit-log design are intentionally deferred. They must not be smuggled into
the first authorization implementation through a broad policy abstraction.
