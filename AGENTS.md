# Popcorn Society Agent Guide

Movie application with a Spring Boot domain backend, React frontend, Python Movie Concierge,
PostgreSQL, OpenSearch, RustFS/S3-compatible object storage, and k3s GitOps deployment.

This is the auto-loaded fast-start contract. Keep detailed guidance in linked docs:

- `docs/agents/README.md` - agent workflow, ownership, task templates, review checklist.
- `docs/agents/verification.md` - verification matrix and feedback loop.
- `docs/left-shift-engineering.md` - compiler, type, test, and agent-feedback experiments.
- `docs/development.md` - local setup, env vars, smoke checks, troubleshooting.
- `docs/design.md` - frontend design system, theme tokens, layout primitives.
- `docs/movie-concierge.md` - product vision, delivered contract, and trust model for the Python
  Movie Concierge.
- `docs/movie-concierge-roadmap.md` - ordered Agent capabilities, quality/safety tracks, production
  exit gates, and voice-first target experience.
- `docs/operations.md` - production URLs, private service access, DBeaver, observability, and
  incident workflow.
- `infrastructure/kubernetes/README.md` - k3s, Argo CD, SOPS/age, home-cluster notes.

## Working Directory

Run commands from the repository root unless a command says otherwise.

Before modifying `agent/`, read `agent/AGENTS.md`. For work spanning multiple deployables, read
every applicable nested guide before planning changes.

## Branch Naming

Before creating a branch, classify the work and choose the repository prefix:

- `feature/<short-kebab-case-description>` for features and other planned improvements.
- `bugfix/<short-kebab-case-description>` for bug fixes.
- Never use agent-specific prefixes such as `codex/`.
- `release/v<major>.<minor>.<patch>-deployment` is reserved for the automated CD workflow.

Apply this rule to CLI, API, and worktree branch creation. Check the chosen name before creating
the branch. Do not rename historical branches unless requested. Pull-request CI checks this
convention with the `branch-name` job.

## Terminology

- Movie: primary catalog title.
- Person: actor, director, writer, or crew member; mostly future catalog work.
- CastMember: relation between Movie and Person with role or character metadata.
- Comment: current backend representation of a user-authored movie review.
- Rating: numeric score attached to a movie and account.
- Watchlist: saved or watched movie collection per account.
- Account/User: persisted account data and authenticated frontend/Spring Security principal.
- Media: posters, backdrops, profile photos, and files stored through RustFS/S3.
- Search Index: OpenSearch projection derived from PostgreSQL movie data.
- Deployment: k3s manifests, Argo CD root app, ingress, cert-manager, and SOPS secrets.
- Movie Concierge: conversational discovery surface that orchestrates Java-owned catalog
  and recommendation capabilities; it is not a second search or recommendation engine.

## Project Map

- `src/main/java/app/popcornsociety/` - backend modules.
- `src/main/resources/config/` - Spring configuration.
- `src/main/resources/db/migration/` - Flyway migrations.
- `src/test/java/app/popcornsociety/` - backend tests and Testcontainers support.
- `frontend/src/app/` - React providers and routes.
- `frontend/src/features/` - feature slices.
- `frontend/src/shared/` - shared API, auth, hooks, layout, media, and UI utilities.
- `frontend/src/client/popcorn-society-backend.yaml` - checked-in OpenAPI spec.
- `frontend/src/client/movies/generator-output/` - generated Axios client; do not edit manually.
- `frontend/e2e/` - Playwright tests.
- `agent/` - Python Movie Concierge deployable; read `agent/AGENTS.md` before changing it.
- `compose.yaml` - local PostgreSQL, OpenSearch, RustFS, and seed services.
- `infrastructure/clusters/home/apps/` - k3s GitOps manifests rendered by Kustomize.
- `scripts/cluster-access` - private SSH-backed production service tunnels and safe credential
  clipboard helpers.
- `.agents/skills/` - checked-in, project-specific skills and their supporting references.
- `.codex/config.toml` - shared Codex project settings; keep credentials and personal settings out.
- `docs/agents/` - detailed agent workflow and verification guidance.
- `docs/assets/` - README/docs images.

## Ownership Rules

Backend:

- Public contracts live in `api`; web adapters in `web`; concrete services, repositories, mappers,
  and persistence types under each module's `internal` package.
- Modules are `account`, `assistant`, `catalog`, `engagement`, `identity`, `media`, `notification`,
  `recommendation`, and `shared`.
- Keep Spring AI MCP transport mapping and workload authentication in `assistant`; call Java-owned
  domain behavior only through narrow named interfaces such as `catalog::assistant`.
- Use existing `ProblemDetail` and `GlobalExceptionHandler` style for API errors.
- Flyway owns schema migrations. Do not edit existing migrations unless explicitly asked.

Frontend:

- Read `docs/design.md` before UI, layout, theme, or visual changes.
- Keep feature UI/behavior in `frontend/src/features/<feature>`.
- Keep genuinely shared API/auth/layout/media/hooks under `frontend/src/shared`.
- Use generated API clients through shared wrappers in `frontend/src/shared/api/`.
- Keep server-state fetches/mutations in TanStack Query wrappers under feature `api` folders.
- Prefer existing Material UI and theme patterns.

Agent:

- Read `agent/AGENTS.md` before editing the Python deployable.
- Keep product policy and Interfaces in `popcorn_society_agent.concierge`, inbound FastAPI code in
  `popcorn_society_agent.web`, concrete outbound Adapters in `popcorn_society_agent.adapters`, and assembly in
  `popcorn_society_agent.bootstrap`.
- Access Java-owned domain behavior only through MCP; never query its PostgreSQL database or
  OpenSearch index directly.
- Keep tests and evals deterministic and provider-independent by default.

Infrastructure:

- Local services are owned by `compose.yaml` and Make targets.
- k3s app resources are owned by `infrastructure/clusters/home/apps`.
- App image versions are controlled by `VERSION` and the CD workflow.
- Kubernetes secrets are SOPS-encrypted `*.sops.yaml`; never commit decrypted values.

## Core Commands

```bash
make docker-compose-dev-up
./gradlew bootRun
cd frontend && yarn install && yarn run build:moviesGen && yarn start
make seed-local-users
make seed-light SEED_VERSION=2026-05-17
make reindex-local-search
make agent-sync
make run-agent
make cluster-access-status
make docker-compose-dev-down
```

Verification:

```bash
./gradlew test architectureTest
./gradlew integrationTest
./gradlew build jacocoTestReport
cd frontend && yarn lint:fast
cd frontend && yarn run lint
cd frontend && yarn test
cd frontend && yarn build
make verify-agent
make verify-observability-charts
make verify-kubernetes-schema
kubectl kustomize infrastructure/clusters/home/apps >/tmp/popcorn-society-home-apps.yaml
```

Choose checks from `docs/agents/verification.md` for the changed ownership area; this list is not
a requirement to run every deployable's full gate for every task.

If backend API contracts change:

```bash
./gradlew bootRun
cd frontend && yarn run updateOpenApiSpec && yarn run build:moviesGen
```

## Skill Selection

- Use task-specific skills when they add relevant guidance. For existing frontend work, use
  `popcorn-frontend-design` and preserve `docs/design.md` and the existing theme.
- Superpowers and gstack workflows are opt-in for this repository: invoke them only when the user
  explicitly requests the plugin or a specific workflow. Ordinary tasks follow the inspect, plan,
  implement, verify loop in `docs/agents/README.md` without additional workflow approval stages.
- Keep shared skills under `.agents/skills/` and personal skills in user configuration. Avoid
  duplicate skill names and references to tools that are unavailable in the current environment.

## Safety Rules

- Do not expose secrets in prompts, logs, docs, screenshots, commits, or summaries.
- Do not print `.secrets/sops/age/keys.txt` or decrypted SOPS content.
- Do not commit `.env.local`, `.env.*.local`, private kubeconfigs, age keys, or Docker Hub tokens.
- Do not run destructive database, Docker volume, namespace, PVC, ingress, image tag, or cluster mutation commands without explicit user approval.
- Do not deploy to k3s unless explicitly asked. Read-only cluster checks are fine when relevant.
- Avoid unrelated refactors, formatting churn, and architecture rewrites.

## Definition Of Done

Before claiming completion, verify the relevant commands, protect secrets, respect ownership
boundaries, and report changed files, commands run, results, skipped checks, and remaining risk.
