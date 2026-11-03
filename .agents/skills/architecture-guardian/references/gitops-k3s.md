# GitOps And k3s Review

## Scope

Review the home-cluster GitOps tree, Argo CD application model, SOPS secret flow,
ingress/cert-manager resources, image/version deployment path, and Kubernetes
manifest verification.

Primary files:

- `infrastructure/clusters/home/apps`
- `infrastructure/clusters/home/root-app.yaml`
- `infrastructure/ansible`
- `.github/workflows/continuous-deployment.yaml`
- `.github/workflows/continuous-integration.yaml`
- `.github/workflows/README.md`
- `Makefile`
- `VERSION`
- `.sops.yaml`
- `infrastructure/kubernetes/README.md`
- `docs/development.md`
- `docs/agents/verification.md`
- `infrastructure/clusters/home/tests`

## Checks

### GitOps Ownership

- the root Argo CD app points at the expected repo revision and app path
- app resources under `infrastructure/clusters/home/apps` are included in `kustomization.yaml`
- each app has a clear namespace owner and does not rely on manual post-apply changes
- generated or runtime-only resources are not committed unless intentionally GitOps-owned
- local Compose configuration and k3s manifests do not drift on ports, service names, bucket names, or index names
- the Agent, its least-privilege NetworkPolicy, public ingress path, runtime Secret, ServiceMonitor,
  alerts, and dashboards remain in the rendered app tree

### Secrets And Config

- Kubernetes secrets remain SOPS-encrypted as `*.sops.yaml`
- plaintext secret values are not committed in manifests, docs, scripts, dashboards, or examples
- SOPS decryption path is documented for Argo CD and does not require application source changes
- ConfigMaps hold non-secret runtime config only
- frontend public config is separated from backend secrets
- Movie Concierge provider and MCP credentials are SOPS-encrypted, mounted read-only into the Java
  and Python workloads that need them, and not exposed as environment values

### Images And Releases

- backend, frontend, and Agent image tags/digests are updated together by the intended release path
- manifest image references are pinned enough for repeatable deploys
- `VERSION` and CD workflow behavior match the documented release model: pull requests verify
  changes, protected `master` accepts merges, and only the intended merged version change publishes
  a release
- the automated digest commit cannot recursively trigger another release
- app manifests do not point to stale local-only images
- ordinary releases do not block on or rerun the one-time production seed job

### Ingress, Certificates, And Exposure

- public hosts are intentional and documented
- cert-manager issuers, TLS secrets, ingress class, and Traefik middleware names are consistent
- internal-only services are not exposed through public ingress by accident
- the public Concierge path remains versioned and protected by request-size, rate, in-flight, host,
  and security-header controls
- Java MCP, PostgreSQL, OpenSearch, RustFS, Loki, Tempo, Pyroscope, and Alloy internals are not made
  public merely for operator access
- LAN-only routes such as Argo CD keep their access controls

### Verification

- `kubectl kustomize infrastructure/clusters/home/apps` renders without applying
- `make verify-kubernetes-schema` or an equivalent kubeconform check validates rendered manifests
- manifest contract checks assert required resources by `(apiVersion, kind, namespace, name)`
- live-cluster checks, when requested, are kept separate from normal CI
- `make verify-movie-concierge-production` and `make verify-observability-charts` validate their
  focused rendered/chart contracts without mutating the cluster

## Suggested Contract Tests

- required namespaces exist in rendered output
- required Argo CD `Application` resources exist for app workloads, data services, and observability
- each `*.sops.yaml` resource listed in the app tree remains encrypted
- backend and frontend ingress hosts match documented public URLs
- the versioned Concierge ingress routes only to the Agent HTTP service and never exposes Java MCP
- observability resources are included in kustomization before dependent apps use them
- backend service exposes the expected app and management ports
- the Agent stays one replica with `Recreate` while production uses process-local conversation and
  cost Adapters
- CI verifies pull requests and merged `master`; CD rejects non-master or unchanged-version release
  attempts

## Report Guidance

Prefer GitOps failure scenarios:

- "Argo CD cannot deploy this resource because it is not included in the app kustomization."
- "This secret would be plaintext in Git."
- "The rendered manifest points at an image tag that the release workflow no longer updates."
- "The public ingress exposes an operator-only service."

Separate static manifest concerns from live-cluster operational status. Do not require
live cluster access unless the user explicitly asks for smoke checks.
