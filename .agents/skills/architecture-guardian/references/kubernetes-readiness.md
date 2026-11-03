# Kubernetes Readiness Review

## Scope

Review whether the Java backend, React frontend, Python Agent, inference runtime, and supporting
jobs match their documented Kubernetes state and scaling contracts. Focus on runtime state,
repeated startup, rollout overlap, graceful shutdown, probes, external dependencies, and
configuration. Do not assume every deployable must be stateless or multi-replica; compare it with
its accepted ADR and current state Adapter.

Primary files:

- `Dockerfile`
- `agent/Dockerfile`
- `frontend/Dockerfile`
- `compose.yaml`
- `Makefile`
- `src/main/resources/config/*.properties`
- `src/main/java/app/popcornsociety/Application.java`
- `src/main/java/app/popcornsociety/*/internal`
- `src/main/java/app/popcornsociety/*/api`
- `src/main/java/app/popcornsociety/catalog/internal/search`
- `src/main/java/app/popcornsociety/identity/internal`
- `src/main/java/app/popcornsociety/media/internal`
- `src/main/resources/db/migration`
- `src/main/resources/sql`
- `agent/src/popcorn_society_agent`
- `agent/AGENTS.md`
- `docs/adr/0001-movie-concierge-architecture.md`
- `infrastructure/clusters/home/apps/agent.yaml`
- `infrastructure/clusters/home/apps/agent-network-policy.yaml`
- `docs`
- infrastructure or deployment folders, if present

## Checks

### Runtime State

- backend instances do not rely on durable local filesystem writes
- uploaded images and generated media are stored in RustFS or another external object store
- database, OpenSearch, RustFS, mail, and external APIs are treated as external services
- browser authentication sessions are shared through Spring Session JDBC rather than pod memory
- startup can run repeatedly without mutating shared state outside controlled migrations or seed jobs
- production image releases do not rerun one-time catalog/database seed work
- the current Agent's bounded conversation and cost state is intentionally process-local; while
  that Adapter remains configured, `replicas: 1` plus `Recreate` is the accepted pilot contract

### Multi-Replica Safety

- scheduled/background work is cluster-safe, idempotent, or protected by a distributed lock/queue
- task execution is at-least-once safe: retries and duplicate execution do not corrupt source-of-truth data
- cleanup jobs, token expiration, projection repair, import jobs, and email jobs behave correctly with replica count greater than one
- unique constraints protect externally visible idempotency where duplicate requests/jobs are plausible
- concurrent writes use database constraints, optimistic locking, or atomic update queries where needed
- a request for multiple Agent replicas or rolling overlap is a readiness gap until shared
  Agent-owned conversation/cost storage, atomic turn leases, idempotency, retention, and
  multi-instance tests exist
- a future Agent PostgreSQL Adapter owns a separate database/schema and credentials and never reads
  Java domain tables directly

### Migrations and Startup

- Flyway is the only schema owner
- migrations are repeatable in a clean environment and deterministic across replicas
- application startup does not require manual ordering beyond dependency readiness
- non-schema imports or seed data are not hidden in migrations unless intentionally documented
- multiple pods starting together cannot race on initialization work outside Flyway's own locking
- seed/import jobs are explicit, repeatable where intended, and decoupled from ordinary releases

### Probes and Shutdown

- readiness probes reflect critical dependencies needed to serve traffic
- liveness probes do not fail on transient downstream outages that readiness should handle
- actuator health groups are configured intentionally for Kubernetes probes
- FastAPI health and readiness endpoints distinguish process health from provider/MCP degradation
- shutdown gives HTTP requests and background jobs time to stop or release locks
- SSE disconnect and termination cancel or bound downstream model/MCP work
- workers can recover from pods killed during task execution

### Configuration and Secrets

- environment-specific values can be supplied through environment variables, Kubernetes Secrets, or ConfigMaps
- secrets are not baked into images, source files, frontend bundles, or default config
- public frontend config is separated from backend secrets
- resource limits, JVM/Python/inference memory behavior, ports, and management endpoints are
  deployable without source changes

### Storage, Search, and Consistency

- PostgreSQL remains the transactional source of truth
- OpenSearch is treated as rebuildable projection state
- RustFS object lifecycle is tied to DB tokens or durable owner events
- cross-system updates have repair paths when downstream services are unavailable
- queue/task tables are observable enough to diagnose stuck or failing work

### Container Build and Runtime

- image build does not depend on local developer state
- runtime image does not need build tools or writable source folders
- container runs with predictable ports and profile/config selection
- logs go to stdout/stderr and contain enough structured context for aggregation
- no development-only services or credentials are required in production profile
- Java and Python containers run as non-root with dropped capabilities and read-only roots where
  their runtime permits it
- production provider/MCP credentials are mounted read-only and are not exposed as environment
  values or image layers

## Evidence Patterns

Look for:

- local file writes: `Files.write`, `new File`, `Path.of`, `MultipartFile.transferTo`
- schedulers and async work: `@Scheduled`, `@Async`, db-scheduler tasks, cleanup services
- startup hooks: `CommandLineRunner`, `ApplicationRunner`, `@PostConstruct`, Flyway callbacks
- profiles/config: `application*.properties`, `@Profile`, `${...}` placeholders
- probes/management: `management.endpoint.health`, health groups, actuator exposure
- external clients: `DataSource`, OpenSearch clients/repositories, RustFS, mail, REST clients
- Python state and concurrency: `InMemoryConversationStore`, `InMemoryCostLedger`, `asyncio.Lock`,
  active run state, provider/MCP clients
- rollout/state coupling: `replicas`, `strategy`, termination grace, mounted secrets, and
  NetworkPolicy

## Report Guidance

Prefer concrete Kubernetes failure scenarios:

- "With two replicas, both pods can process the same cleanup job."
- "A pod restart loses state because the token is only held in memory."
- "Readiness can go green before required indexes/tables exist."
- "A failed OpenSearch write is repairable because a durable task remains."
- "Two Agent replicas can receive consecutive turns but do not share the configured conversation
  store."
- "Rolling overlap doubles the process-local project budget guard and splits conversation state."

Do not require Helm or Kubernetes manifests unless the user asks for deployment implementation.
Use `gitops` mode for manifest ownership and `observability` mode for metrics, logs,
Prometheus, and Grafana contracts. Report missing manifests as a readiness gap only when
the request is explicitly about deployment assets.
