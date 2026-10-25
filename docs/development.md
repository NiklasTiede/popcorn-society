# Development Guide

This guide documents the local and validation workflows for Popcorn Society. Run commands from the
repository root unless a command says otherwise.

## Prerequisites

- Java 25.0.5, Amazon Corretto (pinned in the root `.java-version`)
- Docker with Compose
- Node.js 24.22.1 LTS (pinned in the root `.node-version`)
- Yarn
- Python 3.14
- uv
- Make
- Optional for deployment validation: `kubectl`, cluster access, and SOPS/age tooling

CI, manual E2E runs, and manual releases read `.java-version` for Amazon Corretto. Both backend
Dockerfiles use the published `amazoncorretto:25.0.5-alpine3.24` image, including the Java runtime
and JDK tools; the standalone build uses the checked-in Gradle wrapper. Update the Java version
file and both Dockerfiles together for security releases. A backend image update reaches production
only after the normal versioned release and deployment PR.

CI, manual E2E runs, and manual releases read `.node-version`; the frontend Docker build pins the
same version in `frontend/Dockerfile`. Update both pins and `frontend/package.json`'s `engines.node`
together when applying Node.js security releases. The engine requirement rejects releases older
than the October 2026 security fixes; patched Node.js 26 releases (26.12.1 or newer) are also
accepted for local development.

Use a version manager that reads `.node-version`, or install the LTS runtime with Homebrew and
select it for the current terminal:

```bash
brew install node@24
export PATH="$(brew --prefix node@24)/bin:$PATH"
node --version
```

Check local tools:

```bash
make check-local-tools
make check-agent-tools
```

## Repository Layout

- Backend source: `src/main/java/app/popcornsociety`
- Backend config: `src/main/resources/config`
- Backend migrations: `src/main/resources/db/migration`
- Backend tests: `src/test/java/app/popcornsociety`
- Frontend source: `frontend/src`
- Frontend e2e tests: `frontend/e2e`
- Movie Concierge source: `agent/src/popcorn_society_agent`
- Movie Concierge tests and evals: `agent/tests` and `agent/evals`
- Local stateful services: `compose.yaml`
- k3s GitOps manifests: `infrastructure/clusters/home/apps`
- Seed pipeline: `infrastructure/movie-seed`

## Run Stateful Services

Start PostgreSQL, OpenSearch, and RustFS:

```bash
make docker-compose-dev-up
```

Equivalent direct command:

```bash
docker compose up -d
```

Stop services:

```bash
make docker-compose-dev-down
```

The Compose setup creates these local services:

| Service | URL or port | Notes |
| --- | --- | --- |
| PostgreSQL | `localhost:5432` | Database `movie_db`; local demo credentials only. |
| OpenSearch | `http://localhost:9200` | Security disabled for local development. |
| RustFS API | `http://localhost:9000` | S3-compatible object storage. |
| RustFS console | `http://localhost:9001` | Local console for object storage. |

The `popcorn-society-rustfs-init` container creates the `imdb-clone` bucket and makes
`imdb-clone/movies/*` and `imdb-clone/profile-photos/*` publicly readable for local media.
It uses a version/digest-pinned MinIO client from Quay; the former Docker Hub image is unavailable.
The shell script is passed as one argument and the helper stays alive after initialization.
Its healthcheck succeeds only after the bucket and policies are ready, so Spring Boot's
`docker compose up --wait` also succeeds on repeated starts.

## Run Backend

### Configure local social login

Create separate development OAuth applications in Google and GitHub, then copy the ignored local
credentials file:

```bash
cp .env.example .env.local
```

Replace all four values in `.env.local`. The default `dev,local-secrets` Spring profiles import this
file when the backend is started from the repository root. Environment variables with the same names
still take precedence. Tests activate only the `dev` profile and use inert test credentials, so local
secrets never affect automated test results.

Use these provider settings:

| Provider | Setting | Local value |
| --- | --- | --- |
| GitHub | Homepage URL | `http://localhost:3000` |
| GitHub | Authorization callback URL | `http://localhost:3000/login/oauth2/code/github` |
| Google | Application type | Web application |
| Google | Authorized JavaScript origin | `http://localhost:3000` |
| Google | Authorized redirect URI | `http://localhost:3000/login/oauth2/code/google` |

Keep GitHub device flow disabled; this app uses the browser-based authorization-code flow. If the
Google consent screen is in testing mode, add the developers who need local access as test users.
Port `3000` is intentional: OAuth starts on the Vite origin and its `/oauth2` and `/login/oauth2`
routes are proxied to the backend.

Start the backend after local services are running:

```bash
./gradlew bootRun
```

Useful URLs:

- Backend API: `http://localhost:8080`
- Health: `http://localhost:8081/actuator/health`
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`
- Swagger UI: `http://localhost:8080/v3/swagger-ui.html`

Flyway runs on startup and applies migrations from `src/main/resources/db/migration`.

## Seed Local Data

After the backend and stateful services are running, seed local users and a lightweight movie/media
catalog:

```bash
make seed-local-users
make seed-light SEED_VERSION=2026-05-17
make reindex-local-search
```

Notes:

- `seed-local-users` creates roles and demo accounts from `src/main/resources/sql/local-users.sql`.
- `seed-light` runs the versioned lightweight seed image against local PostgreSQL and RustFS.
- `reindex-local-search` logs in with a local demo admin account and rebuilds the OpenSearch movie
  index from PostgreSQL. Reindex job status and per-film progress survive application restarts.
  Storage failures keep the job RUNNING with a retry message and retry after one minute. The existing
  database scheduler must be enabled to process accepted jobs. Rebuild is in place, so search results
  can be incomplete until it finishes; ordinary movie updates remain available and are projected
  through the same coordinated writer. Pending/failed attempts are visible through the
  `catalog.reindex.*` metrics and the Catalog-owned `movie_search_reindex_job` table.
- The seed pipeline is intended to be idempotent for movie/media upserts and should not wipe local user
  data.

## Run Frontend

Use Yarn Classic 1.22.22 for the frontend, matching CI and Docker. `yarn.lock` is the
authoritative dependency lockfile. Configure the IDE's package manager to Yarn as well;
`npm install` creates a competing `package-lock.json` and does not apply the security
pins in Yarn's `resolutions`.

Install dependencies and start Vite:

```bash
cd frontend
yarn install
yarn run build:moviesGen
yarn start
```

The frontend runs on `http://localhost:3000` and expects:

- `VITE_POPCORN_SOCIETY_BACKEND_ADDRESS`
- `VITE_POPCORN_SOCIETY_CONCIERGE_ADDRESS` (optional; local Vite uses `/concierge-api`)
- `VITE_POPCORN_SOCIETY_OBJECT_STORAGE_ADDRESS`

These are defined in `frontend/.env.development` for local development and in
`frontend/.env.production` for production builds. Use `.env.local` or `.env.*.local` for private local
overrides; those files are ignored by Git.

Browser observability is best effort and never blocks the UI. Development defaults to a console
reporter. To exercise the same anonymous batching endpoint used in production, put
`VITE_OBSERVABILITY_CONSOLE=false` in ignored `frontend/.env.local`, run the backend, and reload the
frontend. The backend accepts at most 20 validated events per request and exports only bounded
Prometheus labels; URLs, route parameters, messages, stacks, account/session IDs, search text, and
browser fingerprints are discarded before transport.

## Run The Movie Concierge

Create the locked Python 3.14 environment, then copy the safe key template to the one exact ignored
path read by the service:

```bash
make agent-sync
mkdir -p .secrets
cp agent/movie-concierge.local.env.example .secrets/movie-concierge.local.env
chmod 600 .secrets/movie-concierge.local.env
```

Edit only `.secrets/movie-concierge.local.env` and replace the placeholder `OPENAI_API_KEY`. Do not
source, print, log, screenshot, or commit this file. A shell `OPENAI_API_KEY`, `.env`, or
`agent/.env` is not read. Configure a hard `$20` budget on the dedicated OpenAI project because it
is the authoritative limit across process restarts.

With the Java backend running, start the Luna-powered service:

```bash
make run-agent
```

The service listens on `http://localhost:8090`. Check:

```bash
curl -fsS http://localhost:8090/healthz
curl -fsS http://localhost:8090/readyz
curl -fsS http://localhost:8090/metrics
```

### Voice agent profile

#### Compare Grok and GPT-Live 1 locally

Run `make run-agent-voice-compare`, or the IntelliJ **Movie Concierge (voice comparison)**
configuration, instead of the normal voice launcher. Both use port 8090; stop the previous
agent process first. Reload the frontend, then select **Grok** or **GPT-Live 1** under the lens.
The selection is fixed while connected. End voice before switching; switching clears the local
conversation display and starts a fresh provider conversation on the next click.

The comparison launcher enables `POPCORN_SOCIETY_AGENT_VOICE_LIVE_ENABLED=true` in addition to ordinary
voice. GPT-Live reads `OPENAI_API_KEY` from `.secrets/movie-concierge-realtime.local.env`.
That key needs Live access to **gpt-live-1** and Responses access to the configured text backend
(`POPCORN_SOCIETY_AGENT_MODEL_NAME`, currently `gpt-5.6-luna`). Grok still uses its separate key and hosted
profile below. Ordinary `make run-agent-voice` remains Grok-only. The browser gets the enabled
choices from `/v1/voice/models`; it never receives API keys or arbitrary provider URLs.

GPT-Live uses `/v1/live/sessions`, not the Realtime API. Its continuous conversation delegates
tasks to the existing `PydanticAIConciergeRunner`, which retains the normal application/MCP
tools, delegated permissions, per-request budgets and committed action receipts. Greetings
need no backend request. Login promotes the current voice session after Java verification;
queued anonymous requests retain their original credentials. Logout closes the session.

Both options currently use the same browser PCM/WebSocket relay, 24kHz audio and 120ms initial
playback buffer. Live does not use the Grok turn/VAD protocol: both speakers can have overlapping
caption rows, output may contain silence continuously, and there is no audio-done event. The lens
therefore derives speaking feedback from audible output rather than treating queued silence as
speech. Spoken captions come only from Live; backend text is sent back as commentary, not displayed
as if it had already been spoken. UI actions and tool activity remain separately inspectable.
Each delegated backend request gets a separate result group for cards, tool activity and UI actions;
caption grouping must not deduplicate separate navigation requests.

Client delegation metadata has no task text. The adapter retains timestamped transcript fragments
and briefly collects late fragments (200ms quiet, at most one second) before requesting backend
work. Display grouping is only a heuristic and never triggers tools or cancels work. Backend work
is serialized and bounded to 12 delegations per session; spoken interruption asks Live to listen,
but does not imply that an already executing mutation was canceled. This behavior needs continued
human testing with corrections and overlapping requests before public rollout.

Safe logs include `voice_model`, `live_delegation_finished` duration and `live_voice_usage` with
cumulative provider seconds, final-usage confirmation and backend request/token counts. Backend
inference is billed separately. No dollar total is inferred from audio bytes or missing usage.
Delegations have a session-local `delegation_sequence` and log `started`, `skipped` (missing
transcript or cached result), or `finished` with an outcome/error code. `live_tool_activity`
records tool names and status only. The finished event counts tool starts and emitted `ui_actions`.
`text_only` means backend text without domain tool calls or navigation, not confirmed task success.
`live_delegation_context` records transcript/history character counts, transcript age and whether
the queued caption row changed, without recording content. On a token-budget failure,
`agent_run_failed` includes cumulative input/output tokens, requests and the configured input limit.
The text backend omits verbose MCP output schemas from model prompts; typed result and permission
checks still run. The input-token budget is cumulative across model requests within each run.
Emission does not prove that the browser completed navigation. Conversation text, movie IDs,
credentials and tool arguments stay out of these logs.
The existing five-minute and concurrency limits apply across both choices. Admission counts starts
in a rolling 24-hour window shared by both models (20 locally by default).

Production activation requires mounted voice secrets, explicit HTTPS origins and a persistent
quota database; see the [rollout runbook](operations.md#public-voice-rollout). Public microphone
and tool-action smoke checks remain part of deployment verification. OpenAI recommends WebRTC
for browser media; a direct WebRTC connection plus server-side controls should be evaluated for
the home-server rollout separately from this comparison's shared relay.

References: [GPT-Live](https://developers.openai.com/api/docs/guides/live),
[client delegation](https://developers.openai.com/api/docs/guides/live-delegation),
[session lifecycle](https://developers.openai.com/api/docs/guides/live-conversations).

#### Hosted Grok profile

The Movie Concierge connects to the hosted xAI profile `agent_ur8m5egTlRE3E8zu`.
Override it with `POPCORN_SOCIETY_AGENT_VOICE_AGENT_ID` and restart the Python agent. The existing
`.secrets/movie-concierge-voice.local.env` still supplies `XAI_API_KEY`.

The profile selects the voice and model. The app supplies its own Movie Concierge instructions,
authorized tools, PCM 24kHz audio, server VAD, and 1.15 output speed. Console instructions/tools
are therefore not the source of the application's behavior. Frontend capture/playback settings
are unchanged. Hosted profiles currently use fresh sessions after a disconnect; automatic
resumption is not configured. The adapter rejects enabling it until hosted-profile resumption
has been implemented and verified.

### Diagnose a voice session ending

A passkey or password login during an anonymous voice session keeps its microphone, WebSocket,
conversation, and original time/usage limits. The browser sends a fresh delegation on that socket;
the agent verifies it through Java while audio keeps flowing, then enables personal tools. These
tools are registered from session start but reject access without verified delegation. Unfinished
anonymous changes are not replayed automatically. Logout or an account switch closes and clears
the session. Social login still navigates away from the app and does not preserve a live socket.

Local voice currently has a hard 300-second (five-minute) lifetime, including connection setup.
This deadline does not reset when the user speaks or types. `POPCORN_SOCIETY_AGENT_VOICE_SESSION_SECONDS`
configures it (15-900 seconds). The UI names the configured time limit when reached. A separate
45-second inactivity deadline and model/tool usage limits can also end a session. Inactivity sends
`standby`: the browser releases the microphone and closes the lens without opening history. Click
it to start again. Automatic local speech wake-up is not enabled.

The normal app has only the bottom-center lens. Open `http://localhost:3000/?conciergeDebug=1`
to inspect the current session's transcript, tool activity, and preferences. Debug access survives
route navigation until reload; reload a URL without this parameter to return to voice-only UI.

The agent run console emits `voice_session_started`, `voice_session_ready`, and exactly one
`voice_session_ended` for each admitted connection. A random `request_id` correlates these events
with tool/audio timing logs from that session. End events include `duration_ms`,
`session_limit_seconds`, `phase`, received audio/control counts, output audio bytes, and turn count.
They contain no audio, transcripts, account identifiers, credentials, or provider exception messages.

Read `outcome` to identify the cause:

- `time_limit`: the configured total lifetime elapsed; the UI names that limit explicitly.
- `idle_timeout`: no user input for 45 seconds.
- `start_timeout` / `delegation_timeout`: browser startup or sign-in verification timed out.
- `connection_timeout`: a provider or transport operation timed out before the total deadline.
- `usage_limit`: the model or tool budget was exhausted.
- `user_end` / `client_disconnect`: the user stopped voice or the browser connection closed.
- `invalid_message` / `provider_error` / `server_shutdown`: invalid input, a runtime failure, or
  application shutdown. Runtime failures include only the exception class in `error_type`.

Restart the Python agent after changing this code to activate the diagnostics. Earlier session
reasons cannot be reconstructed from logs that were never emitted.

Production uses a separate file boundary. It never reuses a shell key: the SOPS-encrypted
`movie-concierge-runtime` Secret is mounted read-only at `/run/secrets/movie-concierge`, while local
development continues to read only `.secrets/movie-concierge.local.env`.

For deterministic UI/API development without a model key, Java, database, or search index:

```bash
make run-agent-fake
```

This fake mode must be selected explicitly. It exercises the same typed browser contract and
in-memory session behavior without pretending to validate model quality or MCP interoperability.

## Run Full Stack Locally

Use four terminals when developing all deployables:

1. Stateful services:

   ```bash
   make docker-compose-dev-up
   ```

2. Backend:

   ```bash
   ./gradlew bootRun
   ```

3. Frontend:

   ```bash
   cd frontend
   yarn start
   ```

4. Movie Concierge:

   ```bash
   make run-agent
   ```

Vite proxies `/concierge-api` to the Python service. The public launcher is available to anonymous
and authenticated users; frontend client IDs combine a stable browser ID with the current account
ID so switching accounts starts an isolated conversation. Python holds bounded history only in
memory, and a Python restart intentionally clears it.

Optional initial seed after backend startup:

```bash
make seed-local-users
make seed-light SEED_VERSION=2026-05-17
make reindex-local-search
```

Smoke checks:

```bash
curl -fsS http://localhost:8081/actuator/health
curl -fsS http://localhost:8080/v3/api-docs.yaml >/tmp/popcorn-society-openapi.yaml
curl -fsS http://localhost:9200/_cluster/health
```

Then open `http://localhost:3000`.

### Local Java MCP seam

The development profile enables the stateless Spring AI MCP endpoint at `http://localhost:8080/mcp`
with the local-only bearer value from `application-dev.properties`. The home-cluster production
endpoint is enabled only on the internal Java Service, protected by an encrypted workload token and
NetworkPolicy, and has no public ingress.

Each MCP operation is an independent JSON-RPC request. After the backend is running, inspect the
server and its generated tool schema with:

```bash
MCP_TOKEN=local-development-mcp-token

curl -fsS http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer ${MCP_TOKEN}" \
  --data '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"local-debugger","version":"1.0.0"}}}'

curl -fsS http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer ${MCP_TOKEN}" \
  --data '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

With PostgreSQL, OpenSearch, embeddings, seed data, and the backend ready, call a grounded tool
directly:

```bash
curl -fsS http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H "Authorization: Bearer ${MCP_TOKEN}" \
  --data '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_movies","arguments":{"query":"thoughtful science fiction under two hours","maxRuntimeMinutes":120,"movieType":"MOVIE","limit":3}}}'
```

The result includes MCP text content for broad client compatibility and a versioned
`structuredContent` object for the Python agent. The tool returns only compact catalog data and
never contacts PostgreSQL or OpenSearch outside the owning Java module. `tools/list` exposes exactly
`search_movies`, `get_movie_details`, `get_similar_movies`, and `get_tonight_picks`; their
annotations declare them read-only, idempotent, non-destructive, and closed-world.

## Backend Checks

Run from the repository root:

```bash
./gradlew test --tests "app.popcornsociety.SomeTest"
./gradlew test
./gradlew integrationTest --tests "app.popcornsociety.SomeIntegrationTest"
./gradlew integrationTest
./gradlew spotlessApply
./gradlew build jacocoTestReport
```

Use targeted tests while developing, then run the broader check before reporting backend work complete.
Before committing Java, Gradle, or backend test changes, run `./gradlew spotlessApply`.

The `test` task is the fast lane and excludes tests tagged `integration`. The `integrationTest` task
uses Testcontainers for PostgreSQL, OpenSearch, and RustFS where needed. Docker must be available for
`integrationTest`, `check`, and `build`. The JaCoCo report combines both test tasks.

## Frontend Checks

Run from `frontend`:

```bash
yarn install --frozen-lockfile
yarn run lint
yarn test
yarn build
yarn e2e
```

The Vite dev server is configured for port `3000` with `strictPort: true`. If port `3000` is busy,
stop the existing process or adjust the command intentionally.

Playwright tests live in `frontend/e2e` and can run by project:

```bash
cd frontend
yarn playwright test --project=desktop-chromium
yarn playwright test --project=mobile-chromium
```

## Movie Concierge Agent Checks

Run the stable full gate from the repository root:

```bash
make verify-agent
```

This includes the executable 27-case deterministic Pydantic Evals report. It checks the
machine-readable tool, argument, text, error, grounding, action, and execution expectations while
keeping qualitative review criteria explicit. Dataset-owned scenarios require no key, provider
network, Java process, or data service. Run one case while iterating with:

```bash
make eval-agent AGENT_EVAL_CASE=tonight-mode-refinement
```

Live Luna evals are deliberately absent from CI and need both the environment opt-in and the
`--live` path selected by the Make target:

```bash
POPCORN_SOCIETY_AGENT_LIVE_EVALS_ENABLED=true \
  make eval-agent-live AGENT_EVAL_CASE=exact-title-search
```

Fault-injection-only cases are excluded from live runs. Start with one case, check its `usage`
event and OpenAI project usage, and stay inside the `$20` project budget.

Use narrow checks while developing:

```bash
cd agent
uv run pytest tests/web/test_health.py
uv run pytest tests/concierge/test_eval_dataset.py
uv run ruff check src/popcorn_society_agent/web tests/web
uv run pyright src/popcorn_society_agent/web tests/web
uv run lint-imports
```

The full gate checks Ruff formatting and linting, strict Pyright, import contracts, architecture
tests, and all deterministic pytest tests. Live-provider evals will be separate opt-in checks.

## Generated API Client

The frontend API client is generated from the backend OpenAPI spec.

When backend API contracts change:

1. Start local services and backend.
2. Refresh the OpenAPI spec.
3. Regenerate the client.
4. Run frontend checks.

Commands:

```bash
cd frontend
yarn run updateOpenApiSpec
yarn run build:moviesGen
yarn build
```

Do not manually edit files in `frontend/src/client/movies/generator-output`.

## Docker Builds

Backend image:

```bash
make docker-build-backend
make container-smoke-backend
```

Frontend image:

```bash
make docker-build-frontend
make container-smoke-frontend
```

The frontend Dockerfile installs Java because `openapi-generator-cli` runs during the image build.
Both frontend runtime stages apply `apk upgrade --no-cache`: upstream Nginx image rebuilds can lag
behind Alpine security fixes. For a deliberate security rebuild, refresh the base and rerun package
installation rather than reusing an old Docker layer:

```bash
docker build --pull --no-cache --platform linux/amd64 -t popcorn-society-frontend:local frontend
```

For prebuilt frontend assets, add `-f frontend/Dockerfile.runtime` to that command. Normal GitHub
builds use fresh hosted runners; the automatic release promotes the verified image without rebuilding.
Backend and frontend runtime images use numeric non-root users and support a read-only root
filesystem. Their smoke tests run with dropped Linux capabilities and exercise the same HTTP health
paths used by Kubernetes; the backend smoke also starts the Pyroscope Java agent.

Movie Concierge image and smoke test:

```bash
make docker-build-agent
make container-smoke-agent
```

The agent image contains runtime dependencies only and runs as numeric non-root user `10001`.

Agent Prometheus metrics include bounded run outcomes/duration, first-event latency, active runs,
configured guardrail limits, known tool names, provider input/cache-read/cache-write/output tokens,
estimated USD cost, process-budget commitment, and SSE disconnects. Logs record stable failure
codes only; they do not contain prompts, bodies, tool payloads, client/conversation IDs,
authorization headers, or keys.

The backend also publishes anonymous frontend real-user metrics under `imdb_frontend_*`, while a
cluster-only ServiceMonitor scrapes llama.cpp's native embedding throughput and queue metrics. The
Grafana Frontend dashboard uses p75 for Web Vitals and the Backend, PostgreSQL, and Infrastructure
dashboards expose deeper search, pool, runtime, contention, throttling, network, and inode signals.

Production traces are sent over OTLP/HTTP from Python and Spring Boot to Alloy, then stored in
Tempo. Pydantic AI content capture and model-request serialization are disabled. Trace context is
propagated through the Python MCP HTTP client so a single trace can correlate FastAPI, model/tool,
and Java MCP work. Concrete conversation paths, query strings, client network data, and user agents
are redacted from inbound spans. Loki receives logs from all Kubernetes namespaces plus Kubernetes
Events. Pyroscope continuously samples Python CPU/allocations and Java JFR CPU/allocations/locks in
production; it is disabled by default for local runs. Use the private endpoints and DBeaver settings
in [`operations.md`](operations.md); do not expose the database or observability APIs with an ingress.

## Kubernetes And k3s Validation

Home-cluster manifests are rendered from:

```text
infrastructure/clusters/home/apps
```

The production movie seed is a separate, manually synchronized Argo Application. Its versioned Job
lives under `infrastructure/clusters/home/maintenance/movie-seed` and is never executed by an
ordinary `home-root` application release.

Render without applying:

```bash
kubectl kustomize infrastructure/clusters/home/apps >/tmp/popcorn-society-home-apps.yaml
make verify-seed-release
make verify-movie-concierge-production
make verify-observability-production
make verify-observability-charts
make verify-kubernetes-schema
```

Read-only cluster checks, when kube access is available:

```bash
kubectl get applications -n argocd
kubectl get pods -A
kubectl -n imdb-clone get deploy,svc,ingress
kubectl -n imdb-clone rollout status deploy/imdb-clone-backend
kubectl -n imdb-clone rollout status deploy/imdb-clone-frontend
```

Do not run `kubectl apply`, `kubectl delete`, `argocd app sync`, rollout restarts, or namespace/PVC
mutations unless explicitly asked. The normal app release path is the version-gated CD workflow, which
builds images and commits digest-pinned manifest updates.

## Live Search Relevance Evaluation

The ordinary unit test verifies the metric calculations. The opt-in live evaluation calls the running
backend and measures the actual OpenSearch and embedding rankings against the versioned judgements in
`src/test/resources/search/relevance-live-v1.json`.

Prepare the versioned local corpus and start the backend in a separate terminal:

```bash
make seed-light SEED_VERSION=2026-05-17
make reindex-local-search
./gradlew bootRun
```

Then run:

```bash
./gradlew liveSearchEvaluation
```

Set `POPCORN_SOCIETY_SEARCH_BASE_URL` when evaluating a backend other than `http://localhost:8080`.
The live evaluation is intentionally not part of `check` because it depends on the seeded corpus and
the local embedding service.

Search operations expose `imdb.search.requests` and `imdb.search.duration` through Micrometer. The
metrics use bounded `mode` and `result` tags and never record the user's query text.

Incremental projection work is tracked in Catalog's `movie_projection_work` table. Movie changes and
the desired projection revision commit together. A running projection cannot consume a newer
revision: the `movie-search-projection-recovery` scheduler task rediscovers pending work every five
seconds, even when the previous one-time wake-up has already finished. An index failure retains
work and retries after one minute; a new domain change makes that movie eligible again immediately.
Inspect `movie_projection_work` attempts/available_at and the `catalog.projection.pending`,
`catalog.projection.failures`, and `catalog.projection.completed` metrics. Do not delete pending work
to silence failures: restore the index/embedding dependency and let recovery process current state.
The bulk reindex workflow remains a separate operation; its durability and concurrency coordination
are documented in [ADR 0002](adr/0002-backend-consistency-and-module-contracts.md).

## Environment Variables And Secrets

Backend configuration keys live in:

- `src/main/resources/config/application.properties`
- `src/main/resources/config/application-dev.properties`
- `src/main/resources/config/application-prod.properties`

Important backend configuration areas:

- `spring.datasource.*`
- `opensearch.*`
- `spring.mail.*`
- `popcorn-society.identity.*`
- `popcorn-society.media.storage.*`
- `popcorn-society.notification.*`
- `popcorn-society.recommendation.*`

Durable email delivery requires `NOTIFICATION_OUTBOX_KEY`: a cryptographically random, base64-encoded
32-byte secret, shared by all backend replicas and preserved across restarts. It is bound as
`popcorn-society.notification.outbox.keys.primary`; `active-key` selects the key for new messages.
The dev profile and automated tests have an explicitly public fixture key. Deployed profiles have
no default and fail startup when a valid key is missing. Provision the real key through the existing
secret/configuration mechanism before deploying this version; do not commit or log it.

For rotation, add a new named entry under `popcorn-society.notification.outbox.keys`, retain the previous
entry, and change `active-key` to the new name. Pending rows carry their key ID. Remove an old key
only after no PENDING rows reference it. Ciphertext cannot be recovered if its key is lost.
All replicas must receive both keys before any replica begins writing with the new one.

Notification payload format 1 preserves the original salt, PBKDF2 derivation and AES-GCM framing.
The cipher uses Spring Security's `AesGcmBytesEncryptor.withSecretKey` API with the format's existing
key derivation from the required random 32-byte configuration secret. Do not replace it directly
with `withPassword`: that factory uses different derivation parameters and cannot read existing
queued messages. Any future format change needs an explicit compatibility/migration design.
Regression tests cover a Spring Security 7.1.0 ciphertext fixture and old-reader compatibility for
newly written payloads; upgrading Spring Boot does not require re-encrypting the outbox.

The `notification-delivery` db-scheduler task polls every five seconds. SMTP failures retain encrypted
work and retry after one minute, until the link's original expiry. The worker never extends token
validity. SENT and EXPIRED rows retain only deduplication metadata; mail content is erased.
That metadata is removed 30 days after both completion and original link expiry by default.
See [PostgreSQL retention](database-retention.md) for all policies, configuration and cleanup behavior.
Observe `notification.delivery.pending`, `notification.delivery.failures`,
`notification.delivery.sent`, and `notification.delivery.expired` in Actuator metrics; inspect
`notification_delivery` state, attempts, available_at and expires_at for persistent failure status.
Do not print decrypted payloads. SMTP may accept a message before a database failure: the retry uses
the same Message-ID, but recipient-side deduplication is not guaranteed.

Frontend build/runtime variables:

- `VITE_POPCORN_SOCIETY_BACKEND_ADDRESS`
- `VITE_POPCORN_SOCIETY_CONCIERGE_ADDRESS` (optional; Vite proxies `/concierge-api` to port `8090` by
  default)
- `VITE_POPCORN_SOCIETY_OBJECT_STORAGE_ADDRESS`

Movie Concierge variables:

- `POPCORN_SOCIETY_AGENT_ENVIRONMENT` (`local`, `test`, or `production`)
- `POPCORN_SOCIETY_AGENT_VERSION`
- `POPCORN_SOCIETY_AGENT_HOST`
- `POPCORN_SOCIETY_AGENT_PORT`
- `POPCORN_SOCIETY_AGENT_MODEL_BACKEND` (`openai` by default; use `fake` only for deterministic development)
- `POPCORN_SOCIETY_AGENT_MODEL_NAME` (`gpt-5.6-luna` by default)
- `POPCORN_SOCIETY_AGENT_SECRETS_DIRECTORY` (required in production; points to read-only projected files)
- `POPCORN_SOCIETY_AGENT_MCP_BEARER_TOKEN` (local/test compatibility only; production ignores it in favor of
  the mounted file)
- `POPCORN_SOCIETY_AGENT_ALLOWED_HOSTS` (JSON list; production forbids wildcard hosts)
- `POPCORN_SOCIETY_AGENT_MAX_CONCURRENT_RUNS`, `POPCORN_SOCIETY_AGENT_MAX_CONVERSATIONS`, and
  `POPCORN_SOCIETY_AGENT_MAX_REQUEST_BODY_BYTES`
- `POPCORN_SOCIETY_AGENT_PROJECT_COST_LIMIT_USD` (cannot exceed `$20`)
- `POPCORN_SOCIETY_AGENT_RUN_COST_LIMIT_USD`, model/tool/token limits, and timeout settings
- `POPCORN_SOCIETY_AGENT_LIVE_EVALS_ENABLED` (defaults to `false` and still requires the `--live` CLI flag)

Java MCP production variables/config-tree entries:

- `movie_concierge_mcp_enabled` (defaults to `false`; the production GitOps Deployment sets it to
  `true` only with the mounted workload token)
- `movie_concierge_mcp_bearer_token` (required and non-empty whenever MCP is enabled)

Secret handling:

- Local override files such as `.env.local` and `.env.*.local` are ignored.
- `.secrets/` is ignored and must not be printed or committed.
- The Movie Concierge reads `OPENAI_API_KEY` only from the exact ignored
  `.secrets/movie-concierge.local.env` file. A shell variable with that name is intentionally
  ignored.
- Production reads the OpenAI and MCP credentials only from the SOPS-backed files beneath
  `/run/secrets/movie-concierge`; neither credential is a Python environment variable.
- Kubernetes secret manifests use SOPS/age as `*.sops.yaml`.
- `.sops.yaml` defines encryption rules for `infrastructure/clusters/home/*.sops.yaml` files.
- Do not replace encrypted SOPS content with plaintext.

## Common Troubleshooting

Port already in use:

- Frontend needs `3000` because Vite uses `strictPort: true`.
- Backend uses `8080`; management/health uses `8081`.
- Movie Concierge uses `8090`.
- PostgreSQL uses `5432`, OpenSearch uses `9200`, RustFS uses `9000` and `9001`.

Backend cannot connect to services:

- Confirm `docker compose ps` shows PostgreSQL, OpenSearch, and RustFS running.
- Confirm the backend is using the `dev` profile or default local profile behavior.
- Check `curl -fsS http://localhost:9200/_cluster/health` for OpenSearch readiness.

Frontend API calls fail:

- Confirm backend is reachable at `http://localhost:8080`.
- Confirm `frontend/.env.development` points to the expected backend and object-storage hosts.
- Regenerate the frontend client if backend API contracts changed.

Movie search returns stale or empty results:

- Seed movie data first.
- Run `make reindex-local-search` after seeding or changing catalog data.

Object images do not load:

- Confirm RustFS is running on `9000`.
- Confirm the bucket init container completed.
- Re-run the seed if media objects were not uploaded.

Testcontainers tests fail:

- Ensure Docker is running.
- Re-run the narrow failing test first.
- If containers are stale, stop Compose services only if they conflict with the test; do not delete
  volumes unless explicitly approved.

Movie Concierge environment or dependency setup fails:

- Run `make check-agent-tools` and confirm `python3 --version` and `uv run python --version` report
  Python 3.14.
- Run `make agent-sync`; do not edit `uv.lock` manually.
- Invalid settings intentionally fail with a redacted `invalid Movie Concierge configuration`
  message. Check only the relevant `POPCORN_SOCIETY_AGENT_*` values without printing secrets.

## Verification Matrix

| Area | Commands |
| --- | --- |
| Frontend install | `cd frontend && yarn install --frozen-lockfile` |
| Frontend lint | `cd frontend && yarn run lint` |
| Frontend tests | `cd frontend && yarn test` |
| Frontend build | `cd frontend && yarn build` |
| Frontend e2e | `cd frontend && yarn e2e` |
| Agent locked install | `make agent-sync` |
| Agent format/lint/type/architecture/tests | `make verify-agent` |
| Agent targeted test | `cd agent && uv run pytest tests/path/test_file.py` |
| Backend targeted fast test | `./gradlew test --tests "app.popcornsociety.SomeTest"` |
| Backend fast tests | `./gradlew test` |
| Backend targeted integration test | `./gradlew integrationTest --tests "app.popcornsociety.SomeIntegrationTest"` |
| Backend integration tests | `./gradlew integrationTest` |
| All backend tests | `./gradlew test integrationTest` |
| Backend package/check | `./gradlew build jacocoTestReport` |
| Backend formatting | `./gradlew spotlessApply` |
| Backend image | `docker build --platform linux/amd64 -t popcorn-society-backend:local .` |
| Frontend image | `cd frontend && docker build --platform linux/amd64 -t popcorn-society-frontend:local .` |
| Agent image | `make docker-build-agent` |
| Agent image smoke | `make container-smoke-agent` |
| k3s manifest render | `kubectl kustomize infrastructure/clusters/home/apps >/tmp/popcorn-society-home-apps.yaml` |
| k3s namespace status | `kubectl -n imdb-clone get deploy,svc,ingress` |
| k3s rollout status | `kubectl -n imdb-clone rollout status deploy/imdb-clone-backend` and frontend equivalent |
| API smoke | `curl -fsS http://localhost:8081/actuator/health` and `curl -fsS http://localhost:8080/v3/api-docs.yaml` |

### Backend consistency upgrade notes

V13 corrects historical movie rating aggregates and queues changed movies for search projection in
one transaction. It preserves source ratings and metadata. NOWAIT table locks reject active writers;
if migration reports a lock conflict, retry startup after those transactions finish. This avoids
partially applying corrections or waiting in a conflicting lifecycle lock order.

V14 retains Media retirement tombstones. They reject reattachment of recorded retired tokens and
trigger periodic idempotent cleanup for late storage writes. Do not prune them while relying on
those guarantees. `media.retirement.audit_due` shows the recheck backlog; the normal cleanup metrics
show pending work and failed attempts. Recovery runs through the existing database scheduler.

Media removes database references immediately and deletes stored variants through the recurring
worker after commit. Former object URLs may remain usable until that cleanup succeeds. Ordinary
uploads use sequential journal/write transactions. If application code composes uploads inside an
existing transaction, allow spare connections for the independent journal commit; exhausting the
pool rejects the upload before object writes. Flyway bootstrap has its own connection requirements.

### TMDB movie enrichment

Create an application API credential in [TMDB account settings](https://www.themoviedb.org/settings/api)
and use its **API Read Access Token** (Bearer token), not a user login/session credential.
See [application authentication](https://developer.themoviedb.org/docs/authentication-application).

Append this entry to the repository-root `.env.local`, which is already ignored by Git:

```properties
TMDB_READ_ACCESS_TOKEN=your-read-access-token
```

The default `dev,local-secrets` Spring profiles import the file. If using explicit profiles, include
`local-secrets` or supply `TMDB_READ_ACCESS_TOKEN` in the backend process environment. Restart Spring
Boot after changing it. Do not place the token in frontend VITE variables, Python agent env files,
URLs, shell command history, logs or committed configuration. Empty/missing tokens disable outbound
TMDB requests. Existing catalog and personal tools remain available.

With backend and agent running, open a fresh voice session and try:

- “Who directed Forrest Gump, and who plays the lead?”
- “What was its budget and how much did it earn?”
- “Which production companies made it?”

The movie needs an existing correct `tmdbId` and matching `imdbId` when present. No provider title
search or mapping changes occur. Check the answer's TMDB attribution; missing budgets are unknown,
not zero. The Data sources & credits section identifies the provider. Review TMDB's
[usage and attribution requirements](https://developer.themoviedb.org/docs/faq) before production use.
CI uses fake responses and requires no TMDB account or key.

The same token enables regional streaming offers. The Concierge defaults to **Streaming in:
Switzerland**. Change that selector for a different browser-saved region, or ask “Where can I
stream Forrest Gump in Germany?” for a one-off override. Try “Where can I stream Forrest Gump?”
and then “Can I rent it instead?” in text or a fresh voice session. Answers should distinguish
subscription/rent/buy and credit JustWatch via TMDB. No recorded offers are not proof of worldwide
unavailability; source links lead to the regional TMDB watch page. The country selection is stored
per account/guest in this browser, with no automatic geolocation or cross-device synchronization.

### Avoiding backend reloads during voice tests

The development profile excludes generated `META-INF/spring-configuration-metadata.json` from
Spring DevTools restart triggers. IntelliJ may regenerate that IDE metadata during unrelated
frontend/Python work; restarting Spring interrupts catalog HTTP requests and active MCP calls.
Java class and runtime configuration changes still trigger normal restarts. After changing the
exclusion, rebuild resources or restart the backend once. Voice stress tests record local catalog
availability and stop further tasks if a reload makes it unavailable.
