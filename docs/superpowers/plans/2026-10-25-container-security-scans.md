# Container Security Scans Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan in this session.

**Goal:** Scan every app image build and rescan production app images every three days, publishing findings in GitHub Security.

**Architecture:** One local composite action runs pinned Grype and publishes SARIF. CI and manual CD call it after each build. A separate workflow scans the immutable backend, frontend and agent image references from the GitOps manifests.

**Tech Stack:** GitHub Actions, Grype, SARIF, Python standard library, Ruby YAML contract checks.

**Spec:** The user approved build-time scans, production rescans every three days, GitHub Security results and a separate PR. The detailed behavior below implements that scope.

## Global Constraints

- No production mutation, deployment, version bump or automatic dependency updates.
- Report all vulnerability severities, including findings without a fix; findings alone do not fail builds.
- Scanner, database, registry and upload failures must fail visibly.
- Pin external scan/upload actions to full commit SHAs and pin the scanner version.
- Scan existing local build images without rebuilding; production scans use registry digests, never `latest`.
- Give `security-events: write` only to jobs that upload SARIF; fork PRs retain downloadable reports without privileged execution.
- Keep separate stable categories for each service and build/production scan.
- Use a daily UTC scheduler with an epoch-day modulo-three gate for a three-day interval across month/year boundaries. Manual and relevant push/PR runs bypass this gate.

## Review Focus

- Month-end and leap-day transitions must preserve three-day spacing.
- Missing, mutable, duplicate or wrong-service production image references must fail before scanning.
- Frontend job working-directory defaults must not break the shared action.
- Manual CD builds must receive the same scans as normal CI builds, before publication.
- Fork PR restrictions must not turn reporting into a privileged `pull_request_target` workflow.

## Task 1: Production target selection

**Files:** `scripts/container-scan-targets.py`, `scripts/tests/test_container_scan_targets.py`.

- [x] Write tests for all three immutable image targets, digest changes, invalid/missing/duplicate references, UTC cadence across month/year/leap boundaries and manual bypass.
- [x] Run tests and observe failure because the selector is missing.
- [x] Implement the dependency-free selector and GitHub job outputs.
- [x] Run tests and inspect its output against the real production manifests.

## Task 2: Scan integration and reporting

**Files:** `.github/actions/container-scan/action.yml`, `.github/workflows/container-security.yaml`, existing CI/CD workflows, `Makefile`, `infrastructure/clusters/home/tests/verify_container_scans.rb`, existing release contract check, `.github/workflows/README.md`.

- [x] Add contract checks for all build paths, stable categories, restricted permissions, immutable action pins and report-only vulnerability policy; observe failure before integration.
- [x] Implement shared scanning/reporting and production workflow, and document cadence, results and limitations.
- [x] Run actionlint, selector tests, release/container contracts and an actual local Grype image scan.
- [x] Request an independent review, address actionable findings and prepare the PR.

## Execution Record

- The current checkout was clean; branch `feature/container-security-scans` starts at `4d8e8259` on `origin/master`.
- The production targets are the three GitOps app images; this is desired deployment state, not a live-cluster inventory.
- No separate design approval is needed: the user's preceding request explicitly approves this implementation and PR.
- Independent review found one important manual-CD gap: grouped builds and smoke checks could suppress scans of already-built images. A new contract failed before the fix; each manual image now has its own build identity and immediate scan guarded by its successful build outcome and `!cancelled()`.
- Selector tests (6), release updater tests (3), both workflow contract suites and actionlint pass. Actual pinned Grype scans of a local backend image and the digest-selected production frontend image produced SARIF successfully, including vulnerability findings. Those findings are not remediated by this infrastructure change.
- The first GitHub run exposed the scan action's requirement for the `v` prefix on the Grype release tag. A failing-then-passing contract now requires that format; the input is `v0.121.0`, pinning both the scanner release and its installer.
