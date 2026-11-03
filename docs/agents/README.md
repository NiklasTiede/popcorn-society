# Agentic Engineering

This repo should make coding agents faster without making them careless. Agents are useful for
inspection, planning, implementation, review, summarization, and test selection. Deterministic tools
should perform generated-code updates, schema migrations, Docker builds, Kubernetes rendering, secret
encryption, and live cluster changes.

Related docs:

- `../../agent/AGENTS.md` - Python Movie Concierge architecture and commands.
- `verification.md` - compact verification matrix.
- `../left-shift-engineering.md` - experiments for earlier, deterministic defect feedback.
- `../development.md` - local development and troubleshooting.
- `../design.md` - frontend design system and visual consistency rules.
- `../movie-concierge.md` - Movie Concierge product and architecture boundaries.
- `../operations.md` - production access, observability, and incident workflow.

## Codex Setup

Shared skills live in `.agents/skills/` and are checked in with their references. Codex discovers
them when working in this repository. The project frontend skill is named `popcorn-frontend-design`
to distinguish it from generic user-installed design skills.

The trusted project configuration in `.codex/config.toml` disables the generic `frontend-design`
skill and the automatic Superpowers dispatcher (`using-superpowers`). Individual Superpowers and
gstack workflows remain available, but are opt-in under the root `AGENTS.md`: use them when the user
explicitly requests the plugin or workflow. Routine work follows the operating model below.

Keep Context7 and OpenAI Docs MCP in user configuration; they need no additional project server.
The unused project `caveman-shrink` server has been removed. If a task needs an additional server,
choose a tested version and document its purpose before adding it. Never check credentials,
absolute user paths, or app-managed browser runtime configuration into project settings.

Reload Codex after changing project configuration or moving skills so the instruction chain and
skill catalog are rebuilt. Personal plugin choices and model/reasoning settings remain in user
configuration.

## Operating Model

Use this loop for every non-trivial task:

1. Inspect: read the files, docs, tests, commands, and ownership area before changing anything.
2. Plan: name the intended files/modules, expected behavior, tests, and risk.
3. Implement: keep the change scoped to one ownership slice where practical.
4. Verify: run the smallest relevant check first, then the broader check that proves the change.
5. Summarize: list changed files, commands run, results, and any skipped checks.

Do not use the repo as a place to demonstrate generic AI patterns. Preserve the existing Spring
Modulith-style backend packages, feature-oriented frontend, generated API client workflow, Flyway
migration ownership, frontend design system, and k3s GitOps structure.

## Scope Discipline

Prefer slices that can be reviewed independently:

- Frontend UI: components, hooks, route behavior, styling, design tokens, and frontend tests.
- Frontend API: query/mutation wrappers, auth/session behavior, generated client usage.
- Backend endpoint: DTOs, controller, module service, validation, tests, generated OpenAPI impact.
- Persistence: entity/repository/query, Flyway migration, schema/test data, integration tests.
- Search/media: OpenSearch projection or RustFS/S3 behavior with focused integration coverage.
- Agent: product/eval policy, FastAPI/SSE Adapter, MCP/model Adapters, and deterministic tests.
- Deployment: Dockerfile, workflow, Kustomize manifest, SOPS secret shape, rollout validation.
- Docs: guidance, troubleshooting, command matrix, ADR or architecture notes.

Small scoped changes beat broad rewrites. If a task spans multiple slices, finish and verify one slice
before starting another unless the user explicitly asks for a larger coordinated change.

## Judgment Versus Deterministic Work

Agents may:

- explain architecture and code paths,
- propose plans and review risks,
- draft code and tests,
- summarize command output,
- identify likely verification commands,
- compare current code with project conventions.

Deterministic commands must perform:

- generated frontend client updates with `yarn run build:moviesGen`,
- OpenAPI spec refresh with `yarn run updateOpenApiSpec`,
- Java formatting with `./gradlew spotlessApply`,
- backend test/build execution with Gradle,
- frontend lint/test/build execution with Yarn,
- Python formatting, linting, strict typing, import contracts, and tests through `make verify-agent`,
- Docker image builds,
- Kustomize rendering,
- SOPS encryption/decryption,
- live Kubernetes rollout or mutation commands.

Any destructive or production-affecting command needs explicit user approval first. This includes
volume deletion, database wipes, namespace deletion, ingress changes, image tag changes, persistent
volume edits, secret replacement, or `kubectl apply` against the home cluster.

## Backend Task Template

Before editing:

- Identify the backend module: `account`, `catalog`, `engagement`, `identity`, `media`,
  `notification`, `recommendation`, or `shared`.
- Check existing controller, API contract, internal service, persistence, mapper, and tests in that
  module.
- Decide whether the change affects OpenAPI and the generated frontend client.

Implementation checklist:

- Put public request/response records and service contracts in `api` when other modules need them.
- Keep controller behavior in `web` thin and delegate to module services.
- Keep implementation and repositories under `internal`.
- Use Bean Validation annotations for request validation.
- Use existing `ProblemDetail`/`GlobalExceptionHandler` patterns for errors.
- Add or update tests near the affected behavior.
- Add a new Flyway migration for schema changes; do not edit old migrations without explicit request.

Verification options:

```bash
./gradlew test --tests "app.popcornsociety.SomeTest"
./gradlew test architectureTest
./gradlew integrationTest --tests "app.popcornsociety.SomeIntegrationTest"
./gradlew integrationTest
./gradlew spotlessApply
./gradlew build jacocoTestReport
```

If API contracts change:

```bash
./gradlew bootRun
cd frontend
yarn run updateOpenApiSpec
yarn run build:moviesGen
```

## Frontend Task Template

Before editing:

- Read `../design.md` for visual-system rules.
- Identify the feature slice under `frontend/src/features` or shared area under `frontend/src/shared`.
- Inspect `frontend/src/theme/`, `frontend/src/theme/theme.test.ts`, nearby components, route definitions,
  query wrappers, and generated-client wrappers.
- Confirm whether the change is UI, design token, server state, auth/session behavior, routing, or
  generated API usage.

Implementation checklist:

- Keep server-state fetches and mutations in feature `api` folders with TanStack Query.
- Use `frontend/src/shared/api/moviesApi.ts` and generated models instead of ad hoc HTTP clients.
- Keep feature-specific state and UI inside the feature slice.
- Move reusable behavior to focused hooks or shared components only when it is genuinely shared.
- Use `frontend/src/theme/` semantic tokens and shared layout/media primitives before adding local styles.
- Update `frontend/src/theme/theme.test.ts` when theme tokens or palette behavior change.
- Add or update Vitest/React Testing Library tests for behavior changes.
- Use Playwright for end-to-end route, auth, or responsive workflow changes when practical.

Verification options:

```bash
cd frontend
yarn test src/theme/theme.test.ts
yarn lint:fast
yarn run lint
yarn test
yarn build
yarn e2e
```

## Movie Concierge Agent Task Template

Before editing:

- Read `../../agent/AGENTS.md`, `../movie-concierge.md`, and the active implementation plan.
- Identify whether behavior belongs to the Concierge product Module, web Adapter, outbound Adapter,
  settings Seam, eval dataset, or composition root.
- Confirm whether a change affects the typed browser event contract, Java MCP tool contract, model
  behavior, or production telemetry.

Implementation checklist:

- Keep `popcorn_society_agent.concierge` independent of FastAPI, Pydantic AI, and concrete Adapters.
- Keep provider, MCP, persistence, and telemetry types behind their Adapters.
- Add strict validation at external Seams and keep secret values out of errors, logs, and traces.
- Add deterministic tests or synthetic eval cases before live-provider checks.
- Use bounded Prometheus labels and safe correlation fields.
- Update the lock file only through uv.

Verification options:

```bash
cd agent && uv run pytest tests/path/test_file.py
cd agent && uv run ruff check src/popcorn_society_agent/path tests/path
cd agent && uv run pyright src/popcorn_society_agent/path tests/path
make verify-agent
make docker-build-agent
make container-smoke-agent
```

## Deployment Task Template

Before editing:

- Identify whether the task changes local Docker Compose, image build, CI/CD, k3s manifests, Argo CD,
  SOPS secrets, ingress, certificates, or persistent data.
- Read `infrastructure/kubernetes/README.md`, `.github/workflows/README.md`, and the target manifest.
- Check whether a version bump in `VERSION` is required or intentionally avoided.

Implementation checklist:

- Keep home-cluster app manifests in `infrastructure/clusters/home/apps` renderable by Kustomize.
- Keep encrypted secret manifests as `*.sops.yaml` and plaintext-free in Git.
- Do not casually edit image tags or digests; the CD workflow normally owns app image digest updates.
- Keep namespace, ingress, certificate, and persistent volume changes small and explicit.
- Do not run `kubectl apply`, `argocd app sync`, or rollout restarts unless the user explicitly asks.

Verification options:

```bash
kubectl kustomize infrastructure/clusters/home/apps >/tmp/popcorn-society-home-apps.yaml
docker build --platform linux/amd64 -t popcorn-society-backend:local .
cd frontend && docker build --platform linux/amd64 -t popcorn-society-frontend:local .
kubectl get applications -n argocd
kubectl -n imdb-clone rollout status deploy/imdb-clone-backend
kubectl -n imdb-clone rollout status deploy/imdb-clone-frontend
```

Use cluster read commands only when kube access is configured and relevant. Use rollout status only to
observe an already-requested or already-running rollout.

## Verification

Use `verification.md` for the compact command matrix. Keep task-specific final summaries tied to exact
commands run, their results, and any skipped checks.

## Review Checklist

When reviewing agent changes, lead with findings before summary:

- Does the change respect backend module boundaries and frontend feature ownership?
- Does Agent code preserve the Concierge Module, Adapter, and composition-root dependency direction?
- Are agent tests and evals deterministic without provider credentials or Java infrastructure?
- Are API request and response shapes typed and validated?
- Was generated code produced by the generator rather than edited manually?
- Are frontend UI changes consistent with `docs/design.md`, `frontend/src/theme/`, and shared layout primitives?
- Are migrations additive and owned by Flyway?
- Are secrets, local credentials, and decrypted SOPS values absent from the diff and logs?
- Are deployment changes limited to the intended namespace, image, service, ingress, or secret?
- Are tests or validation commands appropriate for the blast radius?
- Does the final summary include exact commands and results?

## Final Response Contract

Every completed agent task should report:

- changed files,
- behavior or documentation changed,
- verification commands run and their results,
- commands not run with reasons,
- remaining risks or follow-up work if any.

Never claim a check passed unless it was actually run and completed successfully in the current work.
