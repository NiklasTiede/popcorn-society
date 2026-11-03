# Integration, Storage, and Search Review

## Scope

Review cross-system behavior between PostgreSQL, OpenSearch, RustFS, server-session security,
durable scheduled tasks, and infrastructure scripts. Use `agent` for Movie Concierge/MCP behavior,
`ai-search` for embedding/model/vector-search specifics, and `observability` for telemetry contracts.

Primary files:

- `src/main/java/app/popcornsociety/*/internal`
- `src/main/java/app/popcornsociety/catalog/internal/persistence/MovieSearchDao.java`
- `src/main/java/app/popcornsociety/catalog/internal/search`
- `src/main/java/app/popcornsociety/catalog/internal/search/projection`
- `src/main/java/app/popcornsociety/identity/internal/security`
- `src/main/java/app/popcornsociety/media/internal`
- `src/main/java/app/popcornsociety/identity/internal/VerificationTokenCleanupScheduler.java`
- `infrastructure`
- `compose.yaml`

## Checks

### Source of Truth

- PostgreSQL remains the source of truth for transactional data unless documented otherwise
- OpenSearch documents are projections/search indexes with rebuild or repair paths
- embedding vectors are search projection data; review model/version specifics in `ai-search` mode
- RustFS stores binary objects addressed by stable tokens, not business state
- frontend image URLs derive from tokens consistently

### Consistency and Failure Modes

- write flows define what happens when PostgreSQL succeeds but OpenSearch or RustFS fails
- retries, exceptions, and logs preserve enough information to repair state
- delete flows clean up relation rows, search documents, and object references according to ownership rules
- scheduled jobs do not hide core consistency responsibilities

### Security and Access

- Spring Session JDBC, CSRF, password, OAuth2, and WebAuthn behavior align with controller and
  same-origin frontend semantics; browser JWT authentication is not reintroduced accidentally
- session, account profile, and credential lifecycle state keep distinct ownership
- public endpoints do not expose private account data
- object storage access does not bypass application authorization for protected assets
- ingress routing, cookie attributes, forwarded headers, CSRF, and security config match the
  same-origin production deployment
- the protected MCP security chain authenticates only the Agent workload and does not grant
  delegated end-user authority

### Infrastructure

- local dev services match Spring configuration
- seed/import scripts match the current schema
- production and development compose files do not encode contradictory ports, bucket names, or index names
- one-time seed/import jobs remain separate from ordinary application releases and repeated startup
- monitoring/logging configs do not expose secrets; review detailed scrape/dashboard contracts in `observability` mode

## Verification Recommendations

- integration tests with Testcontainers for cross-system write/delete behavior
- focused tests for RustFS object naming and image-token contracts
- search tests proving projection updates after catalog/rating changes
- search projection task tests proving durable repair after indexing failures
- security tests for public/private endpoint access
- authentication tests for session persistence, CSRF, OAuth2/WebAuthn ownership, and MCP workload
  separation
