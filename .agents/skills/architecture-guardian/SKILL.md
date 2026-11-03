---
name: architecture-guardian
description: Review Popcorn Society for architectural drift across Spring Modulith, the Python Movie Concierge, React feature modules, application contracts, persistence/search/storage, Kubernetes/GitOps, observability, and AI search. Use for read-only architecture audits, not implementation work.
---

# Architecture Guardian

## Purpose

Review this repository for architectural drift and produce a read-only report. Focus on evidence, risk, and verifiable follow-up work. Do not edit source files, generated clients, migrations, config, tests, or commits while acting as guardian.

Build outputs from checks are allowed. Source changes are not.

## Repository Context

- Backend: Spring Boot, Java, Spring Modulith, JPA, Flyway, PostgreSQL, OpenSearch, RustFS,
  Spring Session JDBC, CSRF, OAuth2, WebAuthn, Spring AI, and llama.cpp embeddings.
- Agent: Python 3.14, FastAPI/SSE, Pydantic AI, OpenAI, protected Java MCP tools, bounded
  in-memory conversations, deterministic evals, and product-owned UI actions.
- Frontend: React, TypeScript, Material UI, TanStack Query, generated Axios client, and a
  feature-owned Movie Concierge client.
- Deployment: local Compose plus k3s GitOps manifests under `infrastructure/clusters/home/apps`.
- Observability: frontend, Java, Python, and platform signals through Prometheus, Grafana, Alloy,
  Loki, Tempo, and Pyroscope.
- Backend source: `src/main/java/app/popcornsociety`.
- Backend modules are Spring Modulith application modules under the root package, with `api`, `web`, and `internal` package roles.
- Agent source: `agent/src/popcorn_society_agent`; its nested guide and ADR 0001 define the accepted
  Module/Adapter direction and cross-runtime ownership.
- Flyway migrations: `src/main/resources/db/migration`.
- Test data: `src/test/resources/sql/test-data.sql`.
- Frontend source: `frontend/src`.
- Frontend feature modules mirror backend domain vocabulary where practical.
- Generated frontend client: `frontend/src/client/movies/generator-output`.

Respect `AGENTS.md`. If repo metadata disagrees with implementation files, report it as project metadata drift instead of silently choosing one source.

## Modes

Choose the narrowest mode that satisfies the request:

| Mode | Use for | Load |
| --- | --- | --- |
| `quick` | Fast drift scan before or after a change | This file, then relevant references only if a concern appears |
| `persistence` | PostgreSQL, Flyway, JPA entities, repositories, constraints, indexes | `references/persistence-jpa-postgresql.md` |
| `backend-modulith` | Spring Modulith module model, named interfaces, dependency rules, and architecture tests | `references/spring-modulith.md` |
| `api-contract` | REST/OpenAPI/generated client plus MCP/SSE/UI-action compatibility | `references/api-contract.md`; also load `references/movie-concierge-agent.md` for Concierge contracts |
| `frontend` | React feature architecture, shared modules, state/data ownership | `references/frontend-architecture.md` |
| `agent` | Python Module/Adapter direction, bounded orchestration, MCP/SSE/UI actions, state, evals, cost, and safety | `references/movie-concierge-agent.md` |
| `integration` | PostgreSQL, OpenSearch, RustFS, security, jobs, and source-of-truth flows | `references/integration-storage-search.md` |
| `kubernetes` | Runtime-state and replica safety, probes, config/secrets, shutdown, and rollout behavior | `references/kubernetes-readiness.md` |
| `gitops` | Argo CD app tree, kustomization inclusion, SOPS secrets, ingress/certs, image/version flow | `references/gitops-k3s.md` |
| `observability` | Frontend/Java/Python metrics, logs, traces, profiles, alerts, and Grafana contracts | `references/observability.md` |
| `ai-search` | Embedding pipeline, llama.cpp service contract, OpenSearch vector projection, hybrid search, eval readiness | `references/ai-search-inference.md` |
| `full` | Broad architecture review across the whole system | All references, but summarize aggressively |

If the user does not specify a mode, default to `quick`. If they name a concern, choose the matching focused mode.

## Workflow

1. Identify the requested mode and state it.
2. Read only the reference files needed for that mode.
3. Inspect the repo before forming conclusions. Read the root `AGENTS.md`; for Agent scope also
   read `agent/AGENTS.md` and `docs/adr/0001-movie-concierge-architecture.md`. Prefer `rg`, `find`,
   `sed`, build metadata, and existing tests.
4. Run narrow non-mutating checks when they add confidence. Examples:
   - `./gradlew integrationTest --tests "app.popcornsociety.shared.DatabaseSchemaTest"`
   - `./gradlew architectureTest`
   - `./gradlew test`
   - `cd frontend && yarn run lint`
   - `cd frontend && yarn test frontendArchitecture.test.ts`
   - `cd frontend && yarn test`
   - `cd frontend && yarn build`
   - `make verify-agent-architecture`
   - `make verify-agent`
   - `make verify-movie-concierge-production`
   - `make verify-observability-charts`
   - `make verify-kubernetes-schema`
5. Produce a report using `references/report-format.md`.
6. Keep findings evidence-backed. Include file links, line numbers when available, risk, suggested fix, and verification.
7. Separate confirmed findings from hypotheses and readiness gaps.

## Subagents

Use subagents only when the user explicitly asks for parallel/delegated review and delegation is
available. For `full` mode, use at most one read-only specialist per independent surface and respect
the environment's concurrency limit:

- Persistence specialist
- Backend Modulith specialist
- API contract specialist
- Frontend specialist
- Movie Concierge Agent specialist
- Integration specialist
- Kubernetes readiness specialist
- GitOps specialist
- Observability specialist
- AI search specialist

Specialists return findings only. The coordinator owns severity, deduplication, cross-system conclusions, and the final report.

## Severity

- `Critical`: production data loss, broken auth boundary, irreversible schema drift, or severe security exposure.
- `High`: real architectural drift that will cause bugs, migration failures, module erosion, or contract breakage.
- `Medium`: maintainability or testability risk with clear evidence.
- `Low`: cleanup, documentation drift, naming inconsistency, or early warning.
- `Readiness gap`: missing prerequisite, hard check, or explicit decision needed before the architecture can be trusted.

## Guardrails

- Do not change code while reviewing.
- Do not regenerate OpenAPI clients.
- Do not create migrations.
- Do not weaken existing architecture tests to make drift disappear.
- Do not suggest broad rewrites when a focused seam, test, or rule would protect the architecture.
- Do not report generic best practices without evidence from this repo.
- Prefer deterministic follow-up checks when possible: Spring Modulith verification, Python import
  contracts, deterministic evals, schema integration tests, OpenAPI/MCP/SSE contract checks,
  frontend lint/build/tests, Kustomize rendering, kubeconform, and focused manifest/dashboard
  contract checks.
