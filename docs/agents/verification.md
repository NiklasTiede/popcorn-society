# Verification

This document is the compact verification matrix for agents and humans. Use
`../development.md` for full local setup and troubleshooting, and `README.md` for
agent workflow and review templates.

Prefer the narrowest command that proves the current change, then run the broader check before
reporting completion.

## Backend Checks

Run from the repository root.

| Task | Command |
| --- | --- |
| One fast backend test | `./gradlew test --tests "app.popcornsociety.SomeTest"` |
| Fast backend behavior tests | `./gradlew test` |
| Semantic architecture rules and negative fixtures | `./gradlew architectureTest` |
| All fast backend checks | `./gradlew test architectureTest` |
| Isolated PostgreSQL module tests | `./gradlew integrationTest --tests '*ModuleIntegrationTest'` |
| One backend integration test | `./gradlew integrationTest --tests "app.popcornsociety.SomeIntegrationTest"` |
| All backend integration tests | `./gradlew integrationTest` |
| All backend tests | `./gradlew architectureTest test integrationTest` |
| Format backend code | `./gradlew spotlessApply` |
| Backend CI-equivalent check | `./gradlew build jacocoTestReport` |

Notes:

- `test` excludes the JUnit `integration` and `architecture` tags and does not start Testcontainers.
- `architectureTest` runs Spring Modulith verification, compiled dependency/annotation rules and
  deliberately invalid fixtures. It needs no external infrastructure. Both `check`/`build` and CI
  require it; CI includes it in the same Gradle invocation as the full backend build.
- `integrationTest` selects the `integration` tag and uses Testcontainers where PostgreSQL,
  OpenSearch, or RustFS are needed.
- `@ApplicationModuleTest` tests for Engagement and Notification run in STANDALONE mode through
  module Interfaces/events, with PostgreSQL and narrow mocks for outbound contracts. They use no
  OpenSearch/S3 containers and assert that foreign domain implementations and repositories are absent.
  They also verify the module's exact JPA entity set. HTTP contract
  behavior remains covered by the existing full-context controller tests.
- Docker must be running for `integrationTest`, `check`, and `build`.
- Testcontainers stores its disposable OpenSearch index in a bounded 256 MiB tmpfs, avoiding
  read-only index blocks caused by the Docker disk image being near its capacity.
- `jacocoTestReport` combines coverage from behavior, architecture and integration test tasks.
- Before committing Java, Gradle, or backend test changes, run `./gradlew spotlessApply`.

## Frontend Checks

Run from `frontend`.

| Task | Command |
| --- | --- |
| Install dependencies | `yarn install --frozen-lockfile` |
| Fast native lint and type feedback | `yarn lint:fast` |
| Type-check app, tooling, and e2e | `yarn typecheck` |
| Full Oxlint and ESLint gate | `yarn run lint` |
| Tests once | `yarn test` |
| Production build | `yarn build` |
| Theme token test | `yarn test src/theme/theme.test.ts` |
| Playwright e2e | `yarn e2e` |

Notes:

- The frontend is a Vite app on `http://localhost:3000`.
- `yarn lint:fast` uses native Oxlint and TypeScript 7 for the tight local feedback loop. Run the
  full `yarn lint` gate before completion while ESLint still covers rules without native parity.
- `yarn typecheck` checks browser source, Node-side Vite configuration, and Playwright configuration
  and specs with their respective TypeScript environments.
- Vitest includes `frontend/src/**/*.{test,spec}.{ts,tsx}`.
- Playwright tests live in `frontend/e2e` and use desktop/mobile Chromium projects.

## Movie Concierge Agent Checks

Run aggregate targets from the repository root. Run narrow uv commands from `agent`.

| Task | Command |
| --- | --- |
| Install locked environment | `make agent-sync` |
| One deterministic test | `cd agent && uv run pytest tests/path/test_file.py` |
| Format check | `make verify-agent-format` |
| Ruff lint | `make verify-agent-lint` |
| Strict Pyright | `make verify-agent-types` |
| Import and architecture contracts | `make verify-agent-architecture` |
| All deterministic tests | `make verify-agent-tests` |
| Agent CI-equivalent check | `make verify-agent` |
| Image build | `make docker-build-agent` |
| Image health/non-root smoke | `make container-smoke-agent` |

Notes:

- Deterministic tests and evals require no model key, Java process, database, OpenSearch index, or
  network access.
- `verify-agent` runs formatting, linting, typing, architecture, and tests in fast-failure order.
- Run the image checks when the Dockerfile, dependency graph, startup, settings, health, or runtime
  packaging changes.
- A cross-deployable MCP or SSE contract change requires targeted tests on both sides of that Seam
  and each affected deployable's complete gate.

## Runtime Image Checks

Run the deployable image checks after changing a Dockerfile, entrypoint, Nginx configuration,
container user, writable path, or health probe contract.

| Deployable | Image build | Non-root/read-only smoke |
| --- | --- | --- |
| Backend | `make docker-build-backend` | `make container-smoke-backend` |
| Frontend | `make docker-build-frontend` | `make container-smoke-frontend` |
| Movie Concierge | `make docker-build-agent` | `make container-smoke-agent` |

The backend smoke starts an isolated PostgreSQL container and verifies both Actuator probe
endpoints. The frontend smoke verifies the SPA fallback. Every smoke runs the application image
with a read-only root filesystem, all capabilities dropped, and privilege escalation disabled.

## Generated API Client

If backend API contracts change, start the backend first and then run:

| Task | Command |
| --- | --- |
| Update OpenAPI spec | `cd frontend && yarn run updateOpenApiSpec` |
| Regenerate client | `cd frontend && yarn run build:moviesGen` |
| Validate frontend build | `cd frontend && yarn build` |
| Check spec/client drift without rewriting tracked files | `make verify-openapi-drift` |

Do not manually edit files in `frontend/src/client/movies/generator-output`.

## Local App Smoke Workflow

| Step | Command |
| --- | --- |
| Start services | `make docker-compose-dev-up` |
| Start backend | `./gradlew bootRun` |
| Seed local users | `make seed-local-users` |
| Seed lightweight catalog | `make seed-light SEED_VERSION=2026-05-17` |
| Rebuild search index | `make reindex-local-search` |
| Start frontend | `cd frontend && yarn start` |
| Start Movie Concierge foundation | `make run-agent` |
| Stop services | `make docker-compose-dev-down` |

Expected local URLs:

- Backend: `http://localhost:8080`
- Actuator: `http://localhost:8081/actuator/health`
- OpenAPI: `http://localhost:8080/v3/api-docs.yaml`
- Frontend: `http://localhost:3000`
- Movie Concierge health: `http://localhost:8090/healthz`
- Movie Concierge metrics: `http://localhost:8090/metrics`
- Object storage API: `http://localhost:9000`
- RustFS console: `http://localhost:9001`
- OpenSearch: `http://localhost:9200`

## Docker Checks

| Task | Command |
| --- | --- |
| Backend image | `docker build --platform linux/amd64 -t popcorn-society-backend:local .` |
| Frontend image | `cd frontend && docker build --platform linux/amd64 -t popcorn-society-frontend:local .` |
| Agent image | `make docker-build-agent` |
| Agent container smoke | `make container-smoke-agent` |

## Kubernetes Checks

| Task | Command |
| --- | --- |
| Render home app manifests | `kubectl kustomize infrastructure/clusters/home/apps >/tmp/popcorn-society-home-apps.yaml` |
| Render home app manifests via Make | `make verify-kubernetes-render` |
| Validate rendered manifests with pinned kubeconform | `make verify-kubernetes-schema` |
| Verify Movie Concierge production contracts | `make verify-movie-concierge-production` |
| Check Argo CD apps | `kubectl get applications -n argocd` |
| Check app namespace | `kubectl -n imdb-clone get deploy,svc,ingress` |
| Backend rollout status | `kubectl -n imdb-clone rollout status deploy/imdb-clone-backend` |
| Frontend rollout status | `kubectl -n imdb-clone rollout status deploy/imdb-clone-frontend` |

Do not run cluster mutation commands unless explicitly asked. The usual production app release path is
the version-gated CD workflow triggered by `VERSION` changes.

## Reporting Completion

Final responses should include:

- changed files,
- exact verification commands run,
- command results,
- commands skipped with reasons,
- remaining risk or follow-up work.
