# API Contract Review

## Scope

Review application-owned contracts across Java REST/OpenAPI, the protected Java MCP Interface, the
Python SSE stream, generated frontend clients, and frontend runtime validation. For Agent Module
direction and run safety, also load `movie-concierge-agent.md`.

Primary files:

- `src/main/java/app/popcornsociety/*/web`
- `src/main/java/app/popcornsociety/*/api`
- `src/main/java/app/popcornsociety/shared/api`
- `src/main/java/app/popcornsociety/shared/error`
- `src/main/java/app/popcornsociety/assistant/internal/mcp`
- `agent/src/popcorn_society_agent/concierge/events.py`
- `agent/src/popcorn_society_agent/web`
- `agent/tests/web`
- `frontend/src/client/popcorn-society-backend.yaml`
- `frontend/src/client/movies/MoviesApi.ts`
- `frontend/src/client/movies/generator-output/api.ts`
- `frontend/src/shared/api/moviesApi.ts`
- `frontend/src/client/movies/generator-output`
- `frontend/src/features/**/api`
- `frontend/src/features/**/model`
- `frontend/src/features/concierge/api`
- `frontend/src/features/concierge/model`

## Checks

### Backend Contract

- controllers are thin and delegate business behavior
- request/response records in module `api` packages are explicit API shapes, not accidental entity exposure
- request validation is applied where invalid input would reach services
- pagination shape is consistent
- auth requirements match route semantics and frontend expectations
- ProblemDetail/global exception style is consistent
- endpoint naming is stable and not leaking persistence names unnecessarily
- controllers live in module `web` packages, not a global technical-layer controller package

### OpenAPI and Generated Client

- backend contract changes are reflected in `frontend/src/client/popcorn-society-backend.yaml`
- generated files under `frontend/src/client/movies/generator-output` are not manually edited
- `shared/api/moviesApi.ts` owns generated Axios API class construction and HTTP client wiring
- `MoviesApi.ts` remains a compatibility entrypoint over the shared API wrappers
- generated DTO and enum types are not copied into feature-local duplicates without reason

### Frontend Usage

- feature API wrappers call the shared API wrapper exports rather than constructing raw generated API clients
- session-cookie and CSRF handling are centralized
- frontend does not rely on backend implementation details not present in OpenAPI
- UI limits match backend constraints, especially page size and public/private route behavior
- image token semantics remain consistent with RustFS handling; movie poster contracts use `posterImageToken`, while account/profile image contracts use `imageUrlToken`

### Java MCP Contract

- the `assistant` Module owns protocol mapping, workload authentication, validation, safe errors,
  and compact tool projections while Movie and Recommendation behavior remains in owning Modules
- tool schemas are explicit and versioned where compatibility matters
- tools call narrow named Interfaces and never another Module's internals or persistence Adapter
- tool results contain only validated catalog identifiers and bounded fields required by the Agent
- workload authentication never treats model-supplied account or user identifiers as authority
- transport errors do not leak stack traces, database details, credentials, or unrestricted tool
  payloads
- Agent tool names, arguments, result aliases, and Java schemas evolve atomically with tests on both
  sides of the Seam

### Browser SSE Contract

- Python emits only product-owned `status`, `text`, `movie-card`, `ui-action`, `error`, `usage`, and
  terminal `completion` events
- event sequence is strictly monotonic and completion identifies the expected conversation
- frontend runtime schemas forbid extra fields and reject missing completion or events after it
- status values expose bounded progress rather than private model reasoning
- movie cards are Java-tool-grounded projections rather than model-authored objects
- UI action payloads contain only known action types and required identifiers, never URLs or routes
- React requires same-stream evidence and builds app-owned routes before executing an action

### Drift Sources

Flag these as contract drift:

- DTO field exists in frontend docs but not generated OpenAPI
- frontend expects embedded movie data where backend only returns IDs
- backend changes enum values without regenerating the client
- Java MCP schema or alias changes without matching Agent parser/contract tests
- Python event changes without matching frontend runtime schemas and stream tests
- the model or provider emits raw protocol messages directly to the browser
- an action can navigate without current-run grounding or can carry an arbitrary destination
- docs or `AGENTS.md` list dependency versions that disagree with build metadata

## Verification Recommendations

- start backend and regenerate OpenAPI only during implementation, not in guardian mode
- compare generated OpenAPI to committed spec in a follow-up implementation task
- run `cd frontend && yarn build`
- add focused frontend API wrapper tests when a contract issue is found
- run targeted Java MCP, Agent web/runner, frontend Concierge client/component, and Playwright tests
  for cross-runtime changes
- run `make verify-agent`, the complete affected Java/frontend gates, and
  `make verify-movie-concierge-production` when the Concierge contract changes
