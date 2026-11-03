# Movie Concierge Agent Review

## Scope

Review the Python Movie Concierge, the Java-owned MCP capability seam, the application-owned browser
stream, deterministic evals, bounded runtime policy, and the current production-pilot constraints.
Do not review semantic catalog search quality here; use `ai-search` for that projection.

Primary files:

- `agent/AGENTS.md`
- `agent/pyproject.toml`
- `agent/src/popcorn_society_agent`
- `agent/tests`
- `agent/evals`
- `src/main/java/app/popcornsociety/assistant`
- `frontend/src/features/concierge`
- `docs/adr/0001-movie-concierge-architecture.md`
- `docs/movie-concierge.md`
- `infrastructure/clusters/home/apps/agent.yaml`
- `infrastructure/clusters/home/apps/agent-network-policy.yaml`
- `infrastructure/clusters/home/tests/verify_movie_concierge.rb`

Treat ADR 0001 and the applicable `AGENTS.md` files as the accepted architecture. Report a conflict
between those documents, executable checks, and implementation as metadata or decision drift.

## Module And Adapter Direction

- `popcorn_society_agent.concierge` owns product policy, orchestration Interfaces, typed events, and errors
- the Concierge Module imports neither FastAPI nor Pydantic AI nor concrete Adapters
- `popcorn_society_agent.web` is an inbound FastAPI/SSE Adapter and does not assemble outbound Adapters
- `popcorn_society_agent.adapters` contains model, MCP, persistence, telemetry, logging, profiling, and fake
  implementations behind product-owned Interfaces
- `bootstrap.py` is the only composition root joining inbound and outbound Adapters
- `settings.py` validates configuration at the process Seam and does not become a service locator
- strict Pyright, Ruff, Import Linter, and architecture tests enforce the direction

Do not demand a new Interface for a single fixed implementation without evidence that behavior
varies. The model runner, conversation store, and cost ledger have real alternate Adapters and are
valid Seams.

## Java Domain Ownership And MCP

- Java remains the source of truth for Movie, Account, Engagement, Search Index, and Recommendation
- Python never reads the Java-owned PostgreSQL schema or OpenSearch index directly
- the `assistant` Spring Modulith Module exposes one protected MCP Adapter and calls only narrow
  named Interfaces such as `catalog::assistant` and `recommendation::assistant`
- MCP tools remain read-only until delegated user authority, approval, idempotency, and audit
  requirements have an accepted design
- workload authentication authorizes the Python workload, not an end user; model-supplied account
  IDs or catalog IDs are never trusted authority
- tool inputs and versioned results are strictly validated, compact, and mapped to safe errors
- the Agent does not duplicate ranking, recommendation, or authorization policy owned by Java

## Provider And Run Isolation

- Pydantic AI, OpenAI, and provider response types remain inside the model Adapter
- product policy, Java tools, browser events, and eval semantics remain reusable by a future
  realtime transport
- model runs have bounded requests, tool calls, tokens, per-run cost, process cost, concurrency,
  provider timeout, MCP timeout, and total duration
- disconnects cancel downstream work and failures settle reservations without exposing internals
- the model cannot emit application actions directly; product policy decides whether an action is
  safe after current-run grounding
- capability help and other deterministic product behavior should not spend model budget when the
  product Module can answer reliably

## Browser Stream And UI Actions

- the browser consumes application-owned typed events, never raw provider or MCP events
- status events expose progress but never private reasoning or chain-of-thought
- sequence numbers are monotonic and `completion` is terminal
- movie cards originate only from Java-tool-grounded results
- strict browser validation rejects unknown fields, malformed identifiers, and provider-supplied
  URLs or routes
- `open_movie` requires explicit intent, exactly one current-run grounded movie, and a matching card
  earlier in the same stream
- React builds the known route and performs navigation; the Agent supplies only the action type and
  positive catalog movie ID
- arbitrary navigation, stale cards, ambiguous results, tool failures, and forged identifiers do
  not execute UI actions

## Conversation State, Cost, And Scaling

The accepted production pilot deliberately uses process-local bounded conversation state and a
process-local cost ledger. While those Adapters are active:

- one Agent replica and a `Recreate` rollout are an intentional invariant, not architectural drift
- restart-driven conversation loss is accepted and must remain documented and safe
- conversation ownership, bounded history, one active turn, eviction, and failure cleanup remain
  deterministic
- the configured project budget is a process guardrail, not a durable provider billing limit

Do not report missing multi-replica support unless the request is about scaling or availability. If
replicas or rolling overlap increase, require a shared Agent-owned store, atomic turn leases,
idempotent requests, a shared cost ledger, retention/privacy rules, and multi-instance tests. A
shared PostgreSQL server is acceptable only through an Agent-owned database or schema and
credentials; Python must still not query Java domain tables.

## Security And Privacy

- local provider credentials come only from the ignored dedicated secrets file
- production provider and MCP credentials are mounted read-only from a SOPS-managed Secret rather
  than exposed as environment values
- logs, traces, metrics, profiles, eval fixtures, and errors exclude secrets, raw prompts,
  completions, unrestricted tool payloads, personal data, and concrete conversation identifiers
- metric and Loki labels stay bounded; trace correlation IDs may be fields but not metric labels
- anonymous and authenticated conversations remain isolated through server-side
  conversation-to-client matching; this client identifier is not end-user authentication or
  authorization
- public ingress exposes only the intended versioned Concierge path and has bounded request size,
  rate, in-flight work, and trusted-host protections
- NetworkPolicy limits Agent ingress and egress to the provider, Java MCP, DNS, Alloy, and
  Pyroscope paths actually required by the deployment

## Evals And Improvement Loop

- unit, architecture, contract, and eval checks are deterministic without a provider key, Java,
  database, OpenSearch, or network
- synthetic eval data covers normal, ambiguous, adversarial, grounding, constraint-refinement,
  tool-error, and UI-action behavior
- live evals remain explicit opt-in and bounded by the project cost policy
- eval assertions cover tool policy and safety rather than exact prose
- changes to prompts, tools, provider, policies, or action semantics update the relevant eval cases
- production feedback becomes a curated, consent-aware synthetic regression case rather than a raw
  transcript copied into the repository
- run metadata may identify bounded model/prompt/policy/tool versions, but observability must not
  depend on storing conversation content

## Observability

- one run is diagnosable across FastAPI, model execution, MCP, and Java through W3C trace context
- metrics cover availability, outcomes, latency, first event, saturation, tool calls, tokens, cost,
  disconnects, and emitted/rejected UI actions with bounded labels
- Java MCP metrics and browser execution metrics make server/action mismatches visible
- structured logs are content-free and correlate by trace ID where available
- Python profiling is optional and must never break the request path
- exporter failures degrade observability, not user requests

Use `observability` mode for the full Prometheus/Loki/Tempo/Pyroscope and dashboard contract review.

## Verification Recommendations

- `make verify-agent-architecture`
- `make verify-agent`
- `make docker-build-agent`
- `make container-smoke-agent`
- `make verify-movie-concierge-production`
- targeted Java MCP tests under `assistant/internal/mcp`
- targeted frontend Concierge client/component tests
- Playwright desktop/mobile tests for route-level UI actions
- complete Java and frontend gates when a cross-runtime contract changes

## Report Guidance

Prefer concrete failure scenarios:

- "Pydantic AI types leaked into the Concierge Module, so changing the provider now changes product
  policy and tests."
- "A second replica would return conversation-not-found because the configured store is
  process-local."
- "The browser accepts a provider-supplied URL, allowing the model to escape app-owned routing."
- "The Agent queries OpenSearch directly, bypassing Java ranking and authorization ownership."
- "A trace exporter records raw prompts even though production telemetry is documented as
  content-free."

Do not report account mutations, durable memory, voice, or multiple replicas as missing features
unless the requested review explicitly targets that readiness milestone.
