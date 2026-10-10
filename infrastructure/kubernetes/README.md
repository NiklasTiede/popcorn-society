# Kubernetes And k3s

This folder owns the current home-server deployment. For daily operator URLs, private service
tunnels, DBeaver, logs, traces, and incident response, use the
[production operations runbook](../../docs/operations.md).

Current platform:

- Ansible bootstraps Ubuntu 24 LTS on `robotnik@um560`.
- k3s runs as a single-node Kubernetes cluster.
- Ansible raises `fs.inotify.max_user_instances` to `1024` so k3s/containerd can sustain
  cluster-wide log following without exhausting the Ubuntu host default.
- k3s bundled Traefik and local-path storage stay enabled initially.
- Argo CD is installed but not exposed publicly.
- Argo CD watches `infrastructure/clusters/home/apps`.

Bootstrap from the repository root:

```bash
cd infrastructure/ansible
ansible-playbook site.yml
```

Verify the cluster over SSH:

```bash
ssh robotnik@um560
kubectl get nodes
kubectl get pods -A
kubectl get applications -n argocd
```

Open Argo CD privately through SSH port-forwarding:

```bash
ssh -L 8080:localhost:8080 robotnik@um560 \
  "kubectl -n argocd port-forward svc/argocd-server 8080:443"
```

Then open `https://localhost:8080` locally. The initial admin password can be read on the server:

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d
```

## GitOps Secrets

SOPS with age is used for encrypted Kubernetes secrets committed to Git.

- The age private key lives locally at `.secrets/sops/age/keys.txt`.
- `.secrets/` is ignored by Git and must be backed up in a private password/secret manager.
- Ansible uploads that key into the `argocd` namespace as the `sops-age-key` Secret.
- Argo CD repo-server uses the `sops-kustomize` config management plugin to decrypt
  `*.sops.yaml` files during sync.

Generate a local age key if it does not exist:

```bash
mkdir -p .secrets/sops/age
docker run --rm -v "$PWD/.secrets/sops/age:/keys" alpine:3.20 \
  sh -c "apk add --no-cache age >/dev/null && age-keygen -o /keys/keys.txt"
```

After changing the age key or plugin config, rerun:

```bash
cd infrastructure/ansible
ansible-playbook site.yml
```

## Movie Seed Job

Production seeding is deliberately separate from normal app releases. The automated `home-root`
Application owns a child Application named `imdb-clone-seed`, but that child has automated sync
explicitly disabled. Its manifests live under
`infrastructure/clusters/home/maintenance/movie-seed`; changing `VERSION`, backend, frontend, or
agent images cannot execute them.

The seed release is a normal versioned Job rather than an Argo hook. Its immutable name, image tag/digest,
label, and `SEED_VERSION` must change together for a new dataset. A completed Job remains visible as
the applied seed version, and synchronizing the same revision again does not create another pod.
The seed itself remains idempotent: an intentional new run upserts movie rows and uploads media
objects without deleting existing catalog data.

For a deliberate data release:

1. Build and publish the versioned full seed image. Resolve its multi-platform index digest with
   `docker buildx imagetools inspect niklastiede/imdb-clone-seed:full-v<VERSION>` and retain the
   readable tag plus verified `@sha256:` digest.
2. Update the Job name, image, version label, and `SEED_VERSION` in
   `infrastructure/clusters/home/maintenance/movie-seed/job.yaml`.
3. Merge and verify that only the `imdb-clone-seed` Application is `OutOfSync`.
4. In Argo CD, open `imdb-clone-seed`, review its diff, and select **Sync**. Enable pruning when
   replacing an older completed seed Job.
5. Observe the Job to completion, then rebuild the OpenSearch movie index explicitly.

The checked-in `full-v0.2.2` image is pinned to its Docker Hub multi-platform index without changing
the dataset version or Job name. Kubernetes cannot patch an existing Job's pod-template image.
If that Job already exists, manually syncing this pin requires deliberate Job replacement and will
rerun the idempotent seed. Review that operation separately; do not force-replace the Job during a
normal application release. The seed Application's disabled automated sync keeps this change from
executing a seed automatically.

The Grafana viewer-user hook also retains curl 8.11.1 with its verified multi-platform manifest
digest. Refresh these pins only after inspecting the upstream registry manifest, preserving the
version tag for review. Digest verification reads registry metadata and does not execute images.

`seed-job.example.yaml` remains a standalone reference for a new environment. It is not part of the
automated home-cluster render.

After the seed job completes, rebuild the OpenSearch movie index explicitly
through the backend admin endpoint. The backend does not reindex movies as an
application startup side effect.

The home-cluster GitOps tree also contains `rustfs-bucket-job.yaml`, an
idempotent Argo CD hook that creates the `imdb-clone` bucket and makes
`imdb-clone/movies/*` publicly readable before seeded media is served.

### Recovering a stale root sync

If `home-root` is `Synced` and `Healthy`, all workloads are ready, the referenced hook Job no longer
exists, but `operationState.phase` still says `Running`, terminate only that stale Argo operation:

```bash
argocd app terminate-op home-root --app-namespace argocd
```

The same action is available as **Terminate** on the running operation in the Argo CD UI. Confirm
the preconditions first; do not terminate a Job that is genuinely still seeding and do not delete
Deployments, namespaces, databases, PVCs, or seed data. Once this change is deployed, normal root
syncs contain no full-seed hook and cannot recreate that stuck state.

## Public Hosts

The home-cluster ingress exposes these public hostnames:

- `imdb-clone.the-coding-lab.com` for the frontend
- `backend.imdb-clone.the-coding-lab.com` for the backend API
- `object-storage.imdb-clone.the-coding-lab.com` for public movie media
- `grafana.imdb-clone.the-coding-lab.com` for public read-only observability dashboards

These DNS records should point to the current public home IP. The router
must forward TCP ports `80` and `443` to the k3s node at `192.168.178.44`.

The `argocd.imdb-clone.the-coding-lab.com` hostname is also routed through
Traefik, but it is protected by a Traefik IP allowlist for the home LAN
`192.168.178.0/24` and the current public home IP. The public IP entry allows
home Wi-Fi access through router NAT loopback, where Traefik may see the
router's public address instead of the laptop's LAN address. The k3s Traefik
service uses `externalTrafficPolicy: Local` so Traefik can evaluate the client
source IP instead of the k3s service proxy IP.

## HTTPS Certificates

The home cluster uses cert-manager with a Let's Encrypt `ClusterIssuer`.
Certificates are requested from the public ingresses through HTTP-01 challenges
handled by Traefik. Keep both TCP `80` and `443` forwarded from the router to
`192.168.178.44`; port `80` is required for initial issuance and renewal.

## Observability

Grafana and Prometheus run inside the `observability` namespace through the
`observability` Argo CD application. Grafana is exposed publicly at
`https://grafana.imdb-clone.the-coding-lab.com` with a read-only `viewer`
account. Retrieve the generated viewer password from the cluster:

```bash
make cluster-copy-grafana-viewer-password
```

Grafana can still be opened privately through the standard operator tunnels:

```bash
make cluster-access-start
```

Then open `http://localhost:13000`. The Grafana admin user is `admin`. Copy the generated password
without displaying it:

```bash
make cluster-copy-grafana-admin-password
```

Prometheus collects Kubernetes node and workload metrics, kubelet/cAdvisor metrics,
kube-state-metrics, the backend Spring Boot Actuator endpoint at `/actuator/prometheus`, the Python
Concierge endpoint at `/metrics`, and llama.cpp's native embedding metrics. The React application
sends validated anonymous Web Vitals, browser errors, and coarse request timings to a same-origin
backend collector, which publishes them as low-cardinality `imdb_frontend_*` series.

Loki stores seven days of logs from every Kubernetes workload, Kubernetes Events, and the k3s
systemd service on each node. Grafana Alloy runs on every node, forwards logs to Loki, and accepts
internal OTLP traffic on ports `4317` and `4318`. Tempo stores three days of traces from the Python
Concierge and Java backend. Grafana links trace IDs in structured logs to the matching Tempo trace.
Tempo's local-blocks processor powers TraceQL metrics and Traces Drilldown. A private, persistent
single-binary Pyroscope stores continuous profiles from the Spring Boot backend and Python
Concierge; Grafana's Profiles Drilldown and trace-to-profile link query it without exposing its API.
Traefik emits JSON access logs while dropping client addresses, request paths and lines, query
parameters, and every request header.

For the standard private PostgreSQL, OpenSearch, RustFS, Grafana, Prometheus, Loki, Tempo,
Pyroscope, and Argo CD tunnels, use `make cluster-access-start` instead of maintaining individual
forwarding commands.

Profiler activation is image-compatible during GitOps reconciliation: the backend image entrypoint
loads its bundled Java agent only when the existing manifest supplies `PYROSCOPE_SERVER_ADDRESS`,
while the new Python image enables the cluster-local profiler from its production default. The old
`v1.2.0` images ignore the Java settings and receive no unknown Python settings, so merging the
infrastructure cannot break workloads before the versioned image release completes.
See [`docs/operations.md`](../../docs/operations.md) for endpoint inventory, DBeaver setup,
credential handling, and the incident workflow.

## Movie Concierge production pilot

The Movie Concierge runs as one hardened Python pod in the existing `imdb-clone` namespace. It has
no database, PVC, second namespace, or user-specific credentials. Browser traffic stays same-origin:
Traefik routes `/concierge-api/v1` to the agent and strips `/concierge-api`; health and Prometheus
endpoints are cluster-internal only. Its `Recreate` update strategy prevents two independent
in-memory session and budget ledgers from overlapping, at the cost of a short rollout interruption.

`movie-concierge-runtime.sops.yaml` owns exactly two encrypted values:

- the OpenAI project key, projected only into the Python pod;
- one random MCP bearer token, projected into both Python and Java using different filenames.

Edit or rotate this file only through SOPS with the ignored age key:

```bash
SOPS_AGE_KEY_FILE=.secrets/sops/age/keys.txt \
  sops infrastructure/clusters/home/apps/movie-concierge-runtime.sops.yaml
```

Never decrypt it into the repository, shell history, logs, screenshots, or documentation. Set the
dedicated OpenAI project to a hard $20 pilot budget before release; the in-memory agent ledger is a
defense-in-depth limit and resets with the pod.

The Agent NetworkPolicy allows ingress only from Traefik and Prometheus. Egress is limited to
cluster DNS, Java MCP on port 8080, Alloy OTLP/HTTP on port 4318, Pyroscope on port 4040, and public
non-private IPv4 destinations on HTTPS port 443. The pod runs as UID/GID 10001 with no service-account token, no
privilege escalation or Linux capabilities, RuntimeDefault seccomp, a read-only root filesystem,
and a 16 MiB memory-backed `/tmp`.

Prometheus scrapes `/metrics` through an internal ServiceMonitor. The Grafana sidecar loads the
`Popcorn Society / Operations Overview` operator landing page plus Backend, Frontend, Movie Concierge,
Infrastructure, Cluster Logs, and data-service drill-down dashboards. Prometheus alert rules are
installed. Alertmanager remains intentionally disabled, so there is no notification delivery yet.

Validate locally without touching the cluster:

```bash
make verify-movie-concierge-production
make verify-kubernetes-schema
```

Do not use `kubectl apply` for a release. An intentional shared `VERSION` change merged to `master`
starts the version-gated workflow, which tests and publishes backend, frontend, and agent images,
resolves immutable digests, and opens a deployment pull request with the three GitOps image updates.
After its CI run is approved and the digest changes are reviewed, merging the pull request lets Argo
CD reconcile production. Infrastructure-only changes do not rebuild application images; after
pull-request CI and review, Argo CD reconciles their merged manifests directly from Git.

During release preparation, the manifests deliberately remain pinned to the last published image
digests. Pull-request CI validates those immutable references without requiring them to match the
pending `VERSION`. After building the new images, CD enables strict `EXPECTED_APP_VERSION` validation and
updates all three manifests atomically on `release/v<VERSION>-deployment`. This avoids a temporary
reference to an unpublished image and prevents the release workflow from bypassing protected
`master`.

Argo CD is exposed for home LAN access at
`https://argocd.imdb-clone.the-coding-lab.com`. The route is intended for
operator use only and is restricted by Traefik middleware.
