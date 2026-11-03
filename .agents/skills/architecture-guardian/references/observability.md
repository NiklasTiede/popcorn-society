# Observability Review

## Scope

Review whether frontend, Java, Python Agent, data-service, inference, infrastructure, and k3s
observability surfaces are coherent enough to debug production behavior without leaking content,
creating high-cardinality/noisy telemetry, or depending on one telemetry backend for correctness.

Primary files:

- `frontend/src/shared/observability`
- `frontend/src/app`
- `agent/src/popcorn_society_agent/adapters`
- `agent/src/popcorn_society_agent/web`
- `agent/tests/adapters`
- `src/main/resources/config/*.properties`
- `src/main/java/app/popcornsociety/identity/internal/security`
- `src/main/java/app/popcornsociety/shared/logging`
- `src/main/resources/api-calls/Actuator.http`
- `src/main/resources/api-calls/llama-cpp/LlamaCpp.http`
- `infrastructure/clusters/home/apps/observability.yaml`
- `infrastructure/clusters/home/apps/loki.yaml`
- `infrastructure/clusters/home/apps/observability/agent-alerts.yaml`
- `infrastructure/clusters/home/apps/observability/stack-alerts.yaml`
- `infrastructure/clusters/home/apps/observability/dashboards`
- `infrastructure/clusters/home/tests/verify_observability.rb`
- `infrastructure/clusters/home/tests/verify_observability_charts.sh`
- `infrastructure/monitoring`
- `infrastructure/kubernetes/README.md`

## Checks

### Java And Agent Metrics And Health

- Actuator exposes the intended endpoints only
- `/actuator/prometheus` is scrapeable by Prometheus without exposing private app data
- management port, service port, and ServiceMonitor endpoint names match
- health probes distinguish liveness from readiness where Kubernetes needs that distinction
- custom metrics, if added, avoid user identifiers, raw URLs with query strings, or unbounded labels
- the Agent health/metrics service, ServiceMonitor, and Prometheus job labels agree
- Agent metrics cover bounded run outcomes, latency, first event, saturation, MCP/tool activity,
  tokens, estimated cost, disconnects, budget, and emitted/rejected UI actions
- Java MCP metrics and frontend execution/rejection metrics can expose cross-runtime action/tool
  mismatches without movie, account, conversation, or trace identifiers as labels

### Frontend Telemetry

- observability initializes once near app boot
- route metrics are mounted under the router
- URL/query sanitization prevents sensitive query params from entering telemetry
- browser errors and Web Vitals go through a small shared facade instead of feature-local ad hoc reporting
- feature components stay free of observability transport details unless a custom event is intentionally defined

### Logs

- Java, Python, frontend ingress, inference, data-service, Kubernetes Event, and platform logs reach
  Loki through Alloy with useful `service_name`, namespace, pod, and normalized level fields
- workloads log to stdout/stderr and preserve bounded structured context plus trace correlation
- log parsing handles JSON and known plaintext formats without collapsing workloads into
  `unknown_service` or misclassifying ordinary lines as warnings/errors
- logs do not include session cookies, CSRF values, OAuth tokens, provider/MCP keys, passwords,
  SOPS values, object-storage credentials, raw prompts/completions, unrestricted tool payloads, or
  personal data
- errors around search projection, embedding generation, storage, and scheduled tasks include enough context for repair

### Traces

- Alloy receives OTLP and Tempo stores traces with documented retention
- W3C context links FastAPI, provider/model spans, outbound MCP, and Java MCP/domain work
- HTTP routes and span attributes redact concrete conversation IDs, query strings, client
  addresses, ports, and user agents where they create sensitive or high-cardinality data
- Pydantic AI instrumentation excludes prompts, completions, binary content, tool
  arguments/results, and serialized model requests
- TraceQL metrics, local-block processing, service graphs, and span metrics match the Grafana
  drilldown features that dashboards/documentation promise
- exporter failure never breaks the user request path

### Continuous Profiles

- Java JFR and Python CPU/allocation profiling identify service, namespace, pod, and version with
  bounded tags
- Pyroscope is private, persistent according to the documented home-cluster policy, and receives
  traffic only from intended workloads/Alloy paths
- Tempo-to-Pyroscope links use compatible service identity and time ranges
- profiling overhead and sampling are bounded, and profiler failure does not fail app startup or a
  request unless production policy explicitly requires profiling availability

### Prometheus, Grafana, And Data Services

- Prometheus scrape contracts exist for backend and intended platform services
- ServiceMonitor selectors match the target services and namespaces
- Grafana dashboards are GitOps-managed when intended, and dashboard names are stable
- dashboards query real metric names exposed by the current app and chart versions
- operations, backend, frontend, Agent, infrastructure, logs, and data-service dashboards use stable
  navigation and put availability/error/latency/saturation before deep runtime detail
- PostgreSQL, OpenSearch, RustFS, llama.cpp, Loki, Tempo, Pyroscope, Alloy, and Argo CD surfaces are
  either directly observable or have an explicit documented gap
- alerts cover app/Agent availability and real dependency/telemetry failures without firing on
  expected low traffic
- public Grafana access is read-only and documented
- Prometheus/Grafana persistence, resource limits, and retention fit the home-cluster constraints
- Loki/Tempo/Pyroscope persistence and retention fit the home-cluster constraints and remain
  private even when Grafana is public read-only

### Live Smoke Checks

- static CI should not depend on the home server
- optional manual smoke checks may query Argo CD health, pod readiness, Prometheus `up`, and Grafana datasource health
- external uptime checks cover public availability only, not internal scrape or dashboard quality
- live acceptance follows a known request through metrics, logs, traces, and profiles but does not
  turn cluster access into a CI dependency

## Suggested Contract Tests

- backend `ActuatorSecurityTest` verifies anonymous Prometheus scrape access
- rendered manifests contain a backend `ServiceMonitor` with `/actuator/prometheus`
- rendered backend service exposes the management port expected by the ServiceMonitor
- rendered Agent service/ServiceMonitor and Java MCP metrics agree with the Agent dashboard
- dashboard ConfigMaps are included in the observability kustomization
- frontend tests verify URL sanitization, route metrics, browser error reporting, and app boot observability setup
- Agent tests verify bounded metric labels, trace redaction, safe log configuration, and profiling
  degradation
- chart checks validate Alloy configuration and pinned Loki/Tempo/Pyroscope contracts
- dashboard contract checks verify important panel titles and emitted metric names

## Report Guidance

Prefer debugging failure scenarios:

- "Prometheus cannot scrape backend metrics because the ServiceMonitor endpoint name no longer matches the service."
- "The frontend reporter would emit raw query strings."
- "A Pydantic AI span records a prompt even though production traces are documented as content-free."
- "Agent UI actions are emitted, but Java MCP or browser execution metrics show no matching work."
- "Alloy classifies Agent JSON logs without a level, so real errors disappear from Logs Drilldown."
- "Tempo traces cannot link to profiles because Java and Python use different service identities."
- "A dashboard panel queries a metric that is not emitted by the current backend."
- "Grafana is public but configured with an admin-style account."

Do not require every technology to emit every signal. Require clear contracts for the signals the
project already promises, and keep operational telemetry separate from optional semantic LLM
analytics that would retain user/model content.
