# frozen_string_literal: true

require "json"
require "yaml"

rendered_path = ARGV.fetch(0, "/tmp/popcorn-society-home-apps.yaml")
repository_root = File.expand_path("../../../..", __dir__)
documents = YAML.load_stream(File.read(rendered_path)).compact

def resource(documents, kind, name, namespace = nil)
  match = documents.find do |document|
    document["kind"] == kind &&
      document.dig("metadata", "name") == name &&
      (namespace.nil? || document.dig("metadata", "namespace") == namespace)
  end
  raise "missing #{kind}/#{name}" if match.nil?

  match
end

def assert_contract(condition, message)
  raise message unless condition
end

def container_environment(deployment, container_name)
  container = deployment.dig("spec", "template", "spec", "containers").find do |candidate|
    candidate["name"] == container_name
  end
  raise "missing container #{container_name}" if container.nil?

  container.fetch("env", []).to_h { |entry| [entry["name"], entry["value"]] }
end

def load_dashboard(repository_root, filename, key)
  manifest = YAML.load_file(
    File.join(
      repository_root,
      "infrastructure/clusters/home/apps/observability/dashboards",
      filename
    )
  )
  JSON.parse(manifest.dig("data", key))
end

def dashboard_panel(dashboard, title)
  panel = dashboard.fetch("panels").find { |candidate| candidate["title"] == title }
  raise "missing panel #{title} in #{dashboard.fetch("title")}" if panel.nil?

  panel
end

def panel_expressions(panel)
  panel.fetch("targets", []).map { |target| target["expr"] }.compact
end

loki = resource(documents, "Application", "loki", "argocd")
loki_values = loki.dig("spec", "source", "helm", "valuesObject")
assert_contract(loki.dig("spec", "source", "targetRevision") == "7.3.0", "Loki chart drifted")
assert_contract(loki_values["deploymentMode"] == "SingleBinary", "Loki must stay single binary")
assert_contract(
  loki_values.dig("loki", "limits_config", "retention_period") == "168h",
  "Loki retention drifted"
)
assert_contract(
  loki_values.dig("singleBinary", "persistence", "whenDeleted") == "Retain",
  "Loki PVC must survive Application deletion"
)
assert_contract(
  loki_values.dig("lokiCanary", "extraArgs") == [
    "-streamname=service_name",
    "-streamvalue=loki-canary"
  ],
  "Loki Canary must identify itself in Grafana Logs Drilldown"
)

tempo = resource(documents, "Application", "tempo", "argocd")
tempo_values = tempo.dig("spec", "source", "helm", "valuesObject")
assert_contract(
  tempo.dig("spec", "source", "repoURL") ==
    "https://grafana-community.github.io/helm-charts",
  "Tempo must use the maintained community chart repository"
)
assert_contract(tempo.dig("spec", "source", "targetRevision") == "2.2.4", "Tempo chart drifted")
assert_contract(tempo_values.dig("tempo", "retention") == "72h", "Tempo retention drifted")
assert_contract(tempo_values.dig("persistence", "enabled") == true, "Tempo persistence required")
assert_contract(
  tempo_values.dig("tempo", "overrides", "defaults", "metrics_generator", "processors") ==
    %w[local-blocks service-graphs span-metrics],
  "Tempo TraceQL, service graph, and span metrics must remain enabled"
)
assert_contract(
  tempo_values.dig(
    "tempo",
    "metricsGenerator",
    "processor",
    "local_blocks",
    "filter_server_spans"
  ) == false,
  "Tempo TraceQL metrics must include every span kind"
)

pyroscope = resource(documents, "Application", "pyroscope", "argocd")
pyroscope_values = pyroscope.dig("spec", "source", "helm", "valuesObject")
assert_contract(
  pyroscope.dig("spec", "source", "targetRevision") == "1.20.3",
  "Pyroscope chart drifted"
)
assert_contract(
  pyroscope_values.dig("architecture", "storage") == { "v1" => false, "v2" => true },
  "Pyroscope must use storage v2"
)
assert_contract(
  pyroscope_values.dig("architecture", "microservices", "enabled") == false,
  "Pyroscope must stay single binary"
)
assert_contract(
  pyroscope_values.dig("pyroscope", "persistence", "enabled") == true,
  "Pyroscope persistence required"
)
assert_contract(
  pyroscope_values.dig("pyroscope", "persistence", "storageClassName") == "local-path",
  "Pyroscope must use the home-cluster storage class"
)
assert_contract(
  pyroscope_values.dig("alloy", "enabled") == false,
  "Pyroscope must reuse the existing Alloy deployment"
)
assert_contract(
  pyroscope.dig("spec", "ignoreDifferences") == [
    {
      "group" => "apps",
      "kind" => "StatefulSet",
      "name" => "pyroscope",
      "namespace" => "observability",
      "jsonPointers" => [
        "/spec/volumeClaimTemplates/0/apiVersion",
        "/spec/volumeClaimTemplates/0/kind",
        "/spec/volumeClaimTemplates/0/spec/volumeMode",
        "/spec/volumeClaimTemplates/0/status"
      ]
    }
  ],
  "Pyroscope must ignore only Kubernetes-defaulted StatefulSet PVC fields"
)
pyroscope_policy = resource(
  documents,
  "NetworkPolicy",
  "pyroscope-private-ingress",
  "observability"
)
assert_contract(
  pyroscope_policy.dig("spec", "policyTypes") == ["Ingress"],
  "Pyroscope must remain ingress-isolated"
)
profile_ingress = pyroscope_policy.dig("spec", "ingress", 1)
allowed_profile_sources = profile_ingress.fetch("from").map do |source|
  [
    source.dig("namespaceSelector", "matchLabels", "kubernetes.io/metadata.name") ||
      "observability",
    source.dig("podSelector", "matchLabels", "app.kubernetes.io/name")
  ]
end
assert_contract(
  allowed_profile_sources.sort == [
    ["imdb-clone", "imdb-clone-agent"],
    ["imdb-clone", "imdb-clone-backend"],
    ["observability", "grafana"],
    ["observability", "prometheus"]
  ],
  "Pyroscope ingress allowlist drifted"
)
assert_contract(
  profile_ingress.fetch("ports") == [{ "protocol" => "TCP", "port" => 4040 }],
  "Pyroscope ingestion/query port drifted"
)

alloy = resource(documents, "Application", "alloy", "argocd")
alloy_values = alloy.dig("spec", "source", "helm", "valuesObject")
assert_contract(alloy.dig("spec", "source", "targetRevision") == "1.11.1", "Alloy drifted")
assert_contract(alloy_values.dig("controller", "type") == "daemonset", "Alloy must cover every node")
assert_contract(
  alloy_values.dig("global", "podSecurityContext", "supplementalGroups").include?(999),
  "Alloy needs read-only systemd journal group access"
)
assert_contract(alloy_values.dig("alloy", "mounts", "varlog") == true, "node logs must be mounted")
assert_contract(
  alloy_values.dig("alloy", "extraPorts").any? { |port| port["port"] == 4318 },
  "Alloy OTLP/HTTP port missing"
)
alloy_config = alloy_values.dig("alloy", "configMap", "content")
%w[loki.source.kubernetes loki.source.kubernetes_events loki.source.journal otelcol.receiver.otlp].each do |component|
  assert_contract(alloy_config.include?(component), "Alloy config missing #{component}")
end
assert_contract(
  alloy_config.include?("_SYSTEMD_UNIT=k3s.service"),
  "journal collection must remain limited to the k3s service"
)
[
  'level      = "level"',
  'log_level  = "log_level"',
  'source   = "plain_level"',
  'level = "plain_level"',
  'event_type = "type"',
  'source   = "event_level"',
  'level = "event_level"'
].each do |level_contract|
  assert_contract(
    alloy_config.include?(level_contract),
    "Alloy log-level normalization missing #{level_contract}"
  )
end

{
  'selector = "{app=\"imdb-clone-frontend\"} |~ \" [1-3][0-9]{2} [0-9]+ \""' =>
    'level = "info"',
  'selector = "{app=\"imdb-clone-frontend\"} |~ \" 4[0-9]{2} [0-9]+ \""' =>
    'level = "warn"',
  'selector = "{app=\"imdb-clone-frontend\"} |~ \" 5[0-9]{2} [0-9]+ \""' =>
    'level = "error"',
  'selector = "{app=\"imdb-clone-llama-cpp\"} |~ \"^[^ ]+ +T +\""' =>
    'level = "trace"',
  'selector = "{app=\"imdb-clone-llama-cpp\"} |~ \"^[^ ]+ +D +\""' =>
    'level = "debug"',
  'selector = "{app=\"imdb-clone-llama-cpp\"} |~ \"^[^ ]+ +I +\""' =>
    'level = "info"',
  'selector = "{app=\"imdb-clone-llama-cpp\"} |~ \"^[^ ]+ +W +\""' =>
    'level = "warn"',
  'selector = "{app=\"imdb-clone-llama-cpp\"} |~ \"^[^ ]+ +E +\""' =>
    'level = "error"'
}.each do |selector, expected_level|
  selector_offset = alloy_config.index(selector)
  assert_contract(!selector_offset.nil?, "Alloy log-level selector missing #{selector}")
  match_stage = alloy_config[selector_offset, 280]
  assert_contract(
    match_stage.include?(expected_level),
    "Alloy log-level selector #{selector} must assign #{expected_level}"
  )
end

assert_contract(
  alloy_config.include?(
    'if eq (ToLower .event_type) \"warning\" }}warn{{ else }}info'
  ),
  "Kubernetes event types must map Warning to warn and Normal to info"
)

coredns_custom = resource(documents, "ConfigMap", "coredns-custom", "kube-system")
assert_contract(
  coredns_custom.fetch("data").keys.sort == %w[empty.override empty.server],
  "CoreDNS optional imports must remain matched without custom DNS behavior"
)

observability = resource(documents, "Application", "observability", "argocd")
viewer_job = resource(
  observability.dig("spec", "source", "helm", "valuesObject", "extraManifests"),
  "Job", "observability-grafana-viewer-user", "observability"
)
viewer_container = viewer_job.dig("spec", "template", "spec", "containers").find do |container|
  container["name"] == "configure-viewer"
end
assert_contract(
  viewer_container&.fetch("image", "")&.match?(/\Acurlimages\/curl:8\.11\.1@sha256:[0-9a-f]{64}\z/),
  "Grafana credential hook must keep curl 8.11.1 pinned to an immutable digest"
)

datasources = observability.dig(
  "spec",
  "source",
  "helm",
  "valuesObject",
  "grafana",
  "additionalDataSources"
)
assert_contract(
  datasources.map { |source| source["uid"] }.sort == %w[loki pyroscope tempo],
  "datasources drifted"
)
tempo_datasource = datasources.find { |source| source["uid"] == "tempo" }
assert_contract(
  tempo_datasource.dig("jsonData", "tracesToProfiles", "datasourceUid") == "pyroscope",
  "Tempo must link traces to Pyroscope profiles"
)
pyroscope_datasource = datasources.find { |source| source["uid"] == "pyroscope" }
assert_contract(
  pyroscope_datasource["url"] == "http://pyroscope.observability.svc.cluster.local:4040",
  "Grafana must query the private Pyroscope service"
)
grafana_plugins = observability.dig(
  "spec",
  "source",
  "helm",
  "valuesObject",
  "grafana",
  "grafana.ini",
  "plugins"
)
assert_contract(
  grafana_plugins["preinstall_auto_update"] == false &&
    grafana_plugins["preinstall_sync"].include?("grafana-pyroscope-app@2.3.0"),
  "Grafana drilldown plugins must be installed deterministically"
)
assert_contract(
  observability.dig(
    "spec",
    "source",
    "helm",
    "valuesObject",
    "prometheus",
    "prometheusSpec",
    "enableRemoteWriteReceiver"
  ) == true,
  "Prometheus remote-write receiver is required for Tempo service metrics"
)

rustfs = resource(documents, "Application", "rustfs", "argocd")
rustfs_values = rustfs.dig("spec", "source", "helm", "valuesObject")
assert_contract(
  rustfs_values.dig("config", "rustfs", "console_enable") == "true",
  "RustFS console must be enabled for private tunnel access"
)
assert_contract(
  rustfs_values.dig("config", "rustfs", "log_level") == "warn",
  "RustFS must suppress info-level scanner noise at the source"
)
assert_contract(
  rustfs_values["extraEnv"] == [
    {
      "name" => "RUSTFS_OBS_LOGGER_LEVEL",
      "value" => "warn"
    }
  ],
  "RustFS log-level changes must alter the pod template until the chart adds ConfigMap checksums"
)

postgresql = resource(documents, "Application", "postgresql", "argocd")
postgresql_primary = postgresql.dig(
  "spec",
  "source",
  "helm",
  "valuesObject",
  "primary"
)
assert_contract(
  postgresql_primary["resourcesPreset"] == "none",
  "PostgreSQL must not inherit the throttling-prone nano CPU limit"
)
assert_contract(
  postgresql_primary["resources"] == {
    "requests" => {
      "cpu" => "100m",
      "memory" => "128Mi",
      "ephemeral-storage" => "50Mi"
    },
    "limits" => {
      "memory" => "192Mi",
      "ephemeral-storage" => "2Gi"
    }
  },
  "PostgreSQL must keep resource guarantees without a CFS CPU ceiling"
)

public_ingresses = documents.select { |document| document["kind"] == "Ingress" }
private_service_names = %w[
  imdb-clone-postgresql
  imdb-clone-opensearch
  imdb-clone-rustfs-svc
  loki-gateway
  tempo
  pyroscope
  observability-kube-prometh-prometheus
]
public_backends = public_ingresses.flat_map do |ingress|
  ingress.fetch("spec").fetch("rules", []).flat_map do |rule|
    rule.dig("http", "paths")&.map { |path| path.dig("backend", "service", "name") } || []
  end
end
assert_contract(
  (public_backends & private_service_names).empty?,
  "an operator-only service was exposed through ingress"
)

backend = resource(documents, "Deployment", "imdb-clone-backend", "imdb-clone")
backend_environment = container_environment(backend, "backend")
assert_contract(
  !backend_environment.key?("JAVA_TOOL_OPTIONS"),
  "profiling activation must remain compatible with the previously published backend image"
)
dockerfile = File.read(File.join(repository_root, "Dockerfile"))
backend_entrypoint = File.read(File.join(repository_root, "scripts/backend-entrypoint"))
assert_contract(
  dockerfile.include?('ENTRYPOINT ["/usr/local/bin/backend-entrypoint"]') &&
    backend_entrypoint.include?("-javaagent:/opt/pyroscope/pyroscope.jar"),
  "new backend image must activate its bundled Pyroscope agent through the safe entrypoint"
)
assert_contract(
  backend_environment["PYROSCOPE_SERVER_ADDRESS"] ==
    "http://pyroscope.observability.svc.cluster.local:4040",
  "backend must use the private Pyroscope service"
)
assert_contract(
  backend_environment.values_at(
    "PYROSCOPE_FORMAT",
    "PYROSCOPE_PROFILING_INTERVAL",
    "PYROSCOPE_PROFILER_ALLOC",
    "PYROSCOPE_PROFILER_LOCK",
    "PYROSCOPE_UPLOAD_INTERVAL",
    "PYROSCOPE_PROFILE_EXPORT_TIMEOUT",
    "PYROSCOPE_INGEST_MAX_TRIES"
  ) == ["jfr", "20ms", "512k", "10ms", "15s", "5s", "2"],
  "backend profiling overhead bounds drifted"
)

agent = resource(documents, "Deployment", "imdb-clone-agent", "imdb-clone")
agent_environment = container_environment(agent, "agent")
assert_contract(
  agent_environment.keys.none? { |name| name.start_with?("IMDB_AGENT_PROFILING_") },
  "profiling activation must remain compatible with the previously published agent image"
)

agent_policy = resource(
  documents,
  "NetworkPolicy",
  "imdb-clone-agent-least-privilege",
  "imdb-clone"
)
pyroscope_egress = agent_policy.dig("spec", "egress").any? do |rule|
  rule.fetch("ports", []).any? { |port| port["port"] == 4040 } &&
    rule.fetch("to", []).any? do |destination|
      destination.dig(
        "namespaceSelector",
        "matchLabels",
        "kubernetes.io/metadata.name"
      ) == "observability" &&
        destination.dig("podSelector", "matchLabels", "app.kubernetes.io/name") == "pyroscope"
    end
end
assert_contract(pyroscope_egress, "agent NetworkPolicy must allow only private Pyroscope egress")

rules = resource(documents, "PrometheusRule", "observability-stack-alerts", "observability")
alert_names = rules.dig("spec", "groups").flat_map do |group|
  group.fetch("rules").map { |rule| rule["alert"] }.compact
end
%w[LokiUnavailable TempoUnavailable PyroscopeUnavailable AlloyUnavailable LokiDiscardingLogs].each do |alert_name|
  assert_contract(alert_names.include?(alert_name), "missing alert #{alert_name}")
end

dashboards = {
  "Operations" => load_dashboard(
    repository_root,
    "operations-overview.yaml",
    "operations-overview.json"
  ),
  "Backend" => load_dashboard(repository_root, "backend-overview.yaml", "backend-overview.json"),
  "Frontend" => load_dashboard(
    repository_root,
    "frontend-overview.yaml",
    "frontend-overview.json"
  ),
  "Concierge" => load_dashboard(repository_root, "agent-overview.yaml", "agent-overview.json"),
  "PostgreSQL" => load_dashboard(
    repository_root,
    "postgresql-overview.yaml",
    "postgresql-overview.json"
  ),
  "Infrastructure" => load_dashboard(
    repository_root,
    "system-overview.yaml",
    "system-overview.json"
  ),
  "Logs" => load_dashboard(repository_root, "cluster-logs.yaml", "cluster-logs.json")
}

expected_dashboard_uids = {
  "Operations" => "imdb-operations-overview",
  "Backend" => "imdb-backend-overview",
  "Frontend" => "imdb-frontend-overview",
  "Concierge" => "imdb-agent-overview",
  "PostgreSQL" => "imdb-postgresql-overview",
  "Infrastructure" => "imdb-system-overview",
  "Logs" => "imdb-clone-cluster-logs"
}
expected_navigation = %w[
  Operations Backend Frontend Concierge PostgreSQL Infrastructure Logs Traces Profiles
]
expected_rows = {
  "Operations" => [
    "Is It Working?",
    "Is It Serving Users?",
    "Real User Experience",
    "Agent Usage And Economics",
    "Does It Have Capacity?",
    "Dependency Workload Readiness"
  ],
  "Backend" => [
    "Reliability",
    "HTTP",
    "Database",
    "Runtime",
    "Security And Guardrails",
    "Domain Workloads",
    "Runtime Detail"
  ],
  "Frontend" => ["Reliability", "Core Web Vitals", "Application Experience"],
  "Concierge" => [
    "Reliability And Latency",
    "Java MCP Tools",
    "Model Economics",
    "Transport And Runtime",
    "HTTP Detail"
  ],
  "PostgreSQL" => [
    "Availability And Capacity",
    "Connections And Transactions",
    "Efficiency And Size",
    "Runtime And Storage",
    "Contention And Maintenance"
  ],
  "Infrastructure" => [
    "Cluster Capacity",
    "Resource Trends",
    "Persistent Storage",
    "Workload Health",
    "Saturation And Node Health"
  ]
}

dashboards.each do |name, dashboard|
  assert_contract(
    dashboard["uid"] == expected_dashboard_uids.fetch(name),
    "#{name} dashboard UID drifted"
  )
  assert_contract(dashboard.fetch("panels").any?, "#{name} dashboard is empty")
  assert_contract(
    dashboard.fetch("panels").map { |panel| panel["id"] }.uniq.length ==
      dashboard.fetch("panels").length,
    "#{name} dashboard panel IDs must be unique"
  )
  dashboard.fetch("panels").each do |panel|
    position = panel.fetch("gridPos")
    assert_contract(
      position["x"] >= 0 && position["w"] > 0 && position["x"] + position["w"] <= 24,
      "#{name} dashboard panel #{panel.fetch("title")} exceeds the 24-column grid"
    )
  end
  content_panels = dashboard.fetch("panels").reject { |panel| panel["type"] == "row" }
  content_panels.combination(2).each do |first, second|
    first_position = first.fetch("gridPos")
    second_position = second.fetch("gridPos")
    horizontal_overlap =
      first_position["x"] < second_position["x"] + second_position["w"] &&
      second_position["x"] < first_position["x"] + first_position["w"]
    vertical_overlap =
      first_position["y"] < second_position["y"] + second_position["h"] &&
      second_position["y"] < first_position["y"] + first_position["h"]
    assert_contract(
      !(horizontal_overlap && vertical_overlap),
      "#{name} dashboard panels #{first.fetch("title")} and " \
        "#{second.fetch("title")} overlap"
    )
  end
  links = dashboard.fetch("links")
  assert_contract(
    links.map { |link| link["title"] } == expected_navigation,
    "#{name} dashboard navigation drifted"
  )
  assert_contract(
    links.all? { |link| link["type"] == "link" && link["keepTime"] == true },
    "#{name} dashboard links must retain the active time range"
  )
  next unless expected_rows.key?(name)

  assert_contract(
    dashboard.fetch("panels").select { |panel| panel["type"] == "row" }
      .map { |panel| panel["title"] } == expected_rows.fetch(name),
    "#{name} dashboard information architecture drifted"
  )
end

operations = dashboards.fetch("Operations")
assert_contract(
  operations.fetch("title") == "Popcorn Society / Operations Overview",
  "operations dashboard title drifted"
)
%w[
  Backend
  Movie\ Concierge
  PostgreSQL
  Active\ Alerts
  Not\ Ready
  Pending
  Backend\ RPS
  Backend\ 5xx
  Backend\ p95
  Agent\ Success
  First\ Event\ p95
  Agent\ Run\ p95
  Agent\ Runs
  Cost\ 24h
  Process\ Budget
  Node\ CPU
  Node\ Memory
  Root\ Disk
  Max\ PVC\ FS
  Restarts
  Last\ OOMs
  OpenSearch\ K8s
  RustFS\ K8s
  Local\ Embeddings
  Frontend\ Signals
  Browser\ Errors
  LCP\ p75
  INP\ p75
  CLS\ p75
  Browser\ API\ Failures
].each do |title|
  dashboard_panel(operations, title)
end
local_embeddings = panel_expressions(dashboard_panel(operations, "Local Embeddings")).join(" ")
assert_contract(
  local_embeddings.include?('up{job="imdb-clone-llama-cpp"}'),
  "operations must expose native llama.cpp scrape health"
)
actionable_alerts = panel_expressions(dashboard_panel(operations, "Active Alerts")).join(" ")
assert_contract(
  actionable_alerts.include?('alertstate="firing"') &&
    actionable_alerts.include?("Watchdog|PrometheusNotConnectedToAlertmanagers|InfoInhibitor"),
  "operations dashboard must suppress known non-actionable alerts"
)
%w[OpenSearch\ K8s RustFS\ K8s].each do |title|
  panel = dashboard_panel(operations, title)
  assert_contract(
    panel.fetch("description").include?("Kubernetes") &&
      panel.fetch("description").include?("not scraped yet"),
    "#{title} must distinguish workload readiness from service health"
  )
end

frontend_dashboard = dashboards.fetch("Frontend")
%w[
  Browser\ Errors
  Browser\ API\ Failures
  Browser\ API\ p95
  Signals\ Received
  LCP\ p75
  INP\ p75
  CLS\ p75
  Browser\ API\ Requests
  Browser\ API\ p95\ By\ Operation
  App\ And\ Route\ Timing
].each do |title|
  dashboard_panel(frontend_dashboard, title)
end
frontend_queries = frontend_dashboard.fetch("panels").flat_map do |panel|
  panel_expressions(panel)
end.join(" ")
assert_contract(
  frontend_queries.include?("imdb_frontend_web_vital_duration_seconds_bucket") &&
    frontend_queries.include?("imdb_frontend_browser_errors_total") &&
    !frontend_queries.match?(/user|session|url|message|stack/i),
  "frontend dashboard must use only bounded anonymous browser metrics"
)
%w[
  Browser\ Errors
  Browser\ API\ Failures
  Browser\ API\ p95
  Signals\ Received
  LCP\ p75
  INP\ p75
  CLS\ p75
].each do |title|
  panel = dashboard_panel(frontend_dashboard, title)
  queries = panel_expressions(panel).join(" ")
  assert_contract(
    panel.fetch("description").include?("current backend process") &&
      panel.fetch("targets").all? { |target| target["instant"] == true && target["range"] == false } &&
      !queries.include?("increase("),
    "#{title} must remain visible for low-traffic process-local frontend telemetry"
  )
end
%w[
  Frontend\ Signals
  Browser\ Errors
  LCP\ p75
  INP\ p75
  CLS\ p75
  Browser\ API\ Failures
].each do |title|
  panel = dashboard_panel(operations, title)
  queries = panel_expressions(panel).join(" ")
  assert_contract(
    panel.fetch("description").include?("current backend process") &&
      panel.fetch("targets").all? { |target| target["instant"] == true && target["range"] == false } &&
      !queries.include?("increase("),
    "operations #{title} must remain visible for low-traffic process-local frontend telemetry"
  )
end

backend_dashboard = dashboards.fetch("Backend")
backend_5xx = panel_expressions(dashboard_panel(backend_dashboard, "5xx Error Rate")).join(" ")
assert_contract(
  backend_5xx.include?("or vector(0)") && backend_5xx.include?('status=~"5.."'),
  "backend 5xx rate must return zero when no failures occur"
)
backend_p95 = panel_expressions(dashboard_panel(backend_dashboard, "Request p95")).join(" ")
assert_contract(
  backend_p95.include?("histogram_quantile(0.95") &&
    backend_p95.include?("increase(") &&
    backend_p95.include?("[$__range]") &&
    backend_p95.include?("or vector(0)"),
  "backend primary latency must be a null-safe p95"
)
slow_routes = panel_expressions(dashboard_panel(backend_dashboard, "Top Slow Routes")).join(" ")
assert_contract(
  slow_routes.include?("clamp_min") && slow_routes.include?(" > 0"),
  "slow routes must exclude zero-request NaN rows"
)
pool_saturation = panel_expressions(
  dashboard_panel(backend_dashboard, "Database Pool Saturation")
).join(" ")
pool_connections = panel_expressions(
  dashboard_panel(backend_dashboard, "Database Pool Connections")
)
assert_contract(
  pool_saturation.include?("sum(hikaricp_connections_active") &&
    pool_saturation.include?("sum(hikaricp_connections_max") &&
    pool_connections.all? { |expr| expr.include?("sum(hikaricp_connections_") },
  "backend Hikari metrics must aggregate current replicas"
)
dashboard_panel(backend_dashboard, "Search Requests")
dashboard_panel(backend_dashboard, "Search Mean Latency")
dashboard_panel(backend_dashboard, "Local Embedding Load")
dashboard_panel(backend_dashboard, "Pool Acquisition And Timeouts")
dashboard_panel(backend_dashboard, "JVM GC Pause Time")

agent_dashboard = dashboards.fetch("Concierge")
agent_http = panel_expressions(dashboard_panel(agent_dashboard, "User-Facing HTTP Requests")).join(" ")
assert_contract(
  agent_http.include?('route!~"/healthz|/readyz"'),
  "agent HTTP traffic must exclude Kubernetes probes"
)
dashboard_panel(agent_dashboard, "Model Tokens Per Second")
dashboard_panel(agent_dashboard, "Agent CPU")
dashboard_panel(agent_dashboard, "Agent Memory")
agent_success = panel_expressions(dashboard_panel(agent_dashboard, "Success")).join(" ")
assert_contract(
  agent_success.include?("or vector(-1)"),
  "agent success must distinguish no runs from a zero-percent success rate"
)
%w[First\ Event\ p95 Run\ p95].each do |title|
  expression = panel_expressions(dashboard_panel(agent_dashboard, title)).join(" ")
  assert_contract(
    expression.include?("increase(") && expression.include?("[$__range]"),
    "#{title} must summarize the selected dashboard range"
  )
end
dashboard_panel(agent_dashboard, "Java MCP Mean Latency")
dashboard_panel(agent_dashboard, "User HTTP p95 By Route")
dashboard_panel(agent_dashboard, "HTTP Requests In Flight")

system_dashboard = dashboards.fetch("Infrastructure")
restart_table = panel_expressions(
  dashboard_panel(system_dashboard, "Pod Restarts In Selected Range")
).join(" ")
assert_contract(
  restart_table.include?(" > 0"),
  "system restart table must hide zero-value rows"
)
%w[Not\ Ready Pending PVC\ Backing\ Filesystem\ Usage Last\ OOMs].each do |title|
  dashboard_panel(system_dashboard, title)
end

postgres_dashboard = dashboards.fetch("PostgreSQL")
dashboard_panel(postgres_dashboard, "Restarts")
dashboard_panel(postgres_dashboard, "PVC Usage")
dashboard_panel(postgres_dashboard, "PVC Usage Trend")
dashboard_panel(postgres_dashboard, "Sessions By State")
dashboard_panel(postgres_dashboard, "Longest Transaction")
dashboard_panel(postgres_dashboard, "Locks By Mode")
dashboard_panel(postgres_dashboard, "Temporary Query Data")
dashboard_panel(postgres_dashboard, "WAL Footprint")

operations_resource = resource(
  documents,
  "ConfigMap",
  "observability-dashboard-operations",
  "observability"
)
assert_contract(
  operations_resource.dig("metadata", "annotations", "grafana_folder") == "Popcorn Society",
  "operations dashboard must stay in the primary Popcorn Society folder"
)
primary_dashboard_resources = %w[
  observability-dashboard-agent
  observability-dashboard-backend
  observability-dashboard-cluster-logs
  observability-dashboard-frontend
  observability-dashboard-operations
  observability-dashboard-system
]
primary_dashboard_resources.each do |name|
  config_map = resource(documents, "ConfigMap", name, "observability")
  assert_contract(
    config_map.dig("metadata", "annotations", "grafana_folder") == "Popcorn Society",
    "#{name} must stay in the primary Popcorn Society folder"
  )
end
postgres_dashboard_resource = resource(
  documents,
  "ConfigMap",
  "observability-dashboard-postgresql",
  "observability"
)
assert_contract(
  postgres_dashboard_resource.dig("metadata", "annotations", "grafana_folder") ==
    "Popcorn Society Data",
  "PostgreSQL dashboard must stay in the Popcorn Society Data folder"
)

traefik = resource(documents, "HelmChartConfig", "traefik", "kube-system")
traefik_values = YAML.safe_load(traefik.dig("spec", "valuesContent"))
assert_contract(
  traefik_values.dig("logs", "general", "format") == "json",
  "Traefik logs must be JSON"
)
assert_contract(
  traefik_values.dig("logs", "access", "enabled") == true,
  "access logs must be on"
)
assert_contract(
  traefik_values.dig("logs", "access", "fields", "headers", "defaultmode") == "drop",
  "Traefik must not log request headers"
)
%w[ClientAddr ClientHost ClientPort ClientUsername RequestAddr RequestLine RequestPath].each do |field|
  assert_contract(
    traefik_values.dig("logs", "access", "fields", "general", "names", field) == "drop",
    "Traefik access logs must drop #{field}"
  )
end

puts "Observability production manifest contracts passed."
