# CI/CD Workflows

The repository has three independently verified deployables: the Spring Boot backend, React
frontend, and Python Movie Concierge. Production infrastructure is reconciled from Git by Argo CD.

## Continuous integration

The `branch-name` job checks pull-request source branches: use `feature/<kebab-case-name>` for
features and planned improvements, or `bugfix/<kebab-case-name>` for fixes. The CD workflow's
`release/v<major>.<minor>.<patch>-deployment` branches and `dependabot/*` branches authored by
`dependabot[bot]` are exceptions. Merge queue and manual
runs skip this check because they do not have a pull-request source branch.

Add `branch-name` as a required status check in the protection rule or ruleset for `master` to
block merges with invalid branch names. CI reports violations but does not prevent local branch
creation; `AGENTS.md` instructs coding agents to choose the correct prefix before creating branches.

Dependabot security updates are grouped per deployable/package ecosystem in
[`../dependabot.yml`](../dependabot.yml). Version-only update PRs are disabled with
`open-pull-requests-limit: 0`; this does not disable security update PRs. Security updates must
remain enabled in GitHub repository settings. These PRs run the same CI gates and require a merge;
the configuration does not enable automatic merging or release new application images.

Every pull request targeting `master`, every merged `master` commit, and a deliberate manual run
starts separate jobs for:

- Java 25 backend architecture gate, build, behavior/module/integration tests, JaCoCo, compiler
  checks, and dependency safety. `architectureTest` runs first and is also required by Gradle `check`.
- React client generation, linting, tests, strict TypeScript, and production build.
- Python 3.14 locked dependency sync, Ruff, Pyright, architecture contracts, deterministic tests and
  evals, non-root image build, and container smoke test without provider credentials.
- Home-cluster manifest contracts, pinned observability Helm rendering, Alloy validation, and
  Kubernetes schema validation.

The manual Playwright workflow starts PostgreSQL, OpenSearch, RustFS, the backend, and the frontend.
Concierge browser behavior uses deterministic intercepted SSE responses there; the agent itself is
covered by its Python contract tests and container smoke test. Live model evals remain explicitly
opt-in and never receive credentials in ordinary CI.

## Version-gated application releases

`VERSION` is the shared backend/frontend/agent release version. A semantic-version change on
`master`, or a deliberate manual CD dispatch, performs the release:

1. Re-run backend, frontend, agent, and GitOps validation.
2. Build Linux AMD64 images for all three deployables.
3. Push `v<VERSION>` and `latest` tags to Docker Hub.
4. Resolve immutable image digests.
5. Update only `backend.yaml`, `frontend.yaml`, and `agent.yaml` in the home-cluster GitOps tree.
6. Strictly verify that all three manifests use the requested release version and immutable digest.
7. Commit the digest update to `release/v<VERSION>-deployment` and create the annotated release tag.
8. Open a deployment pull request instead of pushing directly to protected `master`.
9. Approve the bot-created PR's CI run, review the three digest changes, and merge it.
10. Let Argo CD reconcile the cluster from the merged Git state.

Pull-request CI permits manifests to keep referencing the last released digests while `VERSION`
prepares the next release. The strict version match is enabled only inside CD after the new images
exist, preventing Argo CD from attempting to pull a not-yet-published tag.

The repository must allow GitHub Actions to create pull requests under **Settings → Actions →
General → Workflow permissions**. Pull-request CI created with the repository `GITHUB_TOKEN` needs
one manual approval from a user with write access. No personal access token or protected-branch
bypass is required.

Provider and MCP credentials are not available to GitHub Actions. The agent build and deterministic
verification path requires neither an OpenAI key nor a running Java service.

Infrastructure-only changes under `infrastructure/clusters/home` do not require a `VERSION` bump or
new application images. After pull-request CI and review, a merge to `master` lets Argo CD reconcile
those manifests directly.

Movie seed images are independent data releases. Their Argo CD Application has automated sync
disabled, so normal releases never reseed PostgreSQL or RustFS.

## Production topology

There is one production application namespace plus dedicated `databases`, `observability`,
`argocd`, and supporting system namespaces. There is no duplicated canary database environment.
See the [k3s guide](../../infrastructure/kubernetes/README.md) for GitOps details and the
[operations runbook](../../docs/operations.md) for URLs, private access, and incident handling.

## Popcorn Society image repositories

New app releases publish `popcorn-society-backend`, `popcorn-society-frontend` and
`popcorn-society-agent` under the configured Docker Hub account. Prepare public repositories and
token push permissions before the first renamed release. The checked-in 1.6.1 image references
remain under their existing repositories until replacement images have been published.

`scripts/update-release-manifests.py` validates all three digests and source references before
editing manifests. The generated deployment PR pins the new images and switches runtime env keys
and backend telemetry identities together. It accepts both legacy and renamed source image refs,
so subsequent releases follow the same path. Namespace, services, PVCs and secrets stay stable.
`make verify-release-workflows` includes updater tests; strict production verification with
`EXPECTED_APP_VERSION` requires the new repositories and environment keys.
