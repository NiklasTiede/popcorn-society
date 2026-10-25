# Container Security Updates Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans for this approved remediation task.

**Goal:** Fix available runtime-image security updates and document findings without a supported patch.

**Architecture:** Upgrade the existing Spring Boot 4.1 patch line and regenerate its managed Gradle locks. Apply Alpine package updates in both frontend runtime build paths. Preserve the current distributions, non-root users, startup contracts and production manifests.

**Tech Stack:** Spring Boot/Gradle, Docker, Alpine packages, Grype.

**Spec:** The user approved assessing and fixing the newly reported container findings in a separate PR; this does not authorize a deployment or broad architecture changes.

## Global Constraints

- No production deployment, manifest image update, application VERSION bump or scanner suppression.
- Prefer Spring Boot's tested dependency BOM over individual Spring/Tomcat/Netty overrides.
- Update both standalone and prebuilt frontend Dockerfiles.
- Keep unpatched distro findings visible and distinguish vendor severity from scanner severity.
- Treat before/after real-image scans as regression evidence; use existing application tests for compatibility.

## Review Focus

- Both Jackson major versions and all locked Gradle configurations must resolve consistently.
- Package upgrades must preserve Nginx UID 101, read-only filesystem behavior and SPA fallback.
- A fresh image tag alone does not refresh packages retained in the upstream base image.
- Missing Debian/Alpine fixes must not be hidden or replaced by unsupported distribution packages.
- The scan PR (#125) is still open; this fix branch starts independently from master.

## Task 1: Available patches

**Files:** `gradle.properties`, generated `gradle.lockfile` and `gradle/verification-metadata.xml`, `frontend/Dockerfile`, `frontend/Dockerfile.runtime`, notification cipher and its compatibility tests.

- [x] Read all three build-image reports and reproduce affected installed versions.
- [x] Verify Spring Boot 4.1.2's curated versions in Maven Central and Alpine's Brotli/curl patch availability.
- [x] Update the Spring Boot patch and regenerate locks with Gradle.
- [x] Apply Alpine package upgrades in both frontend runtime stages.
- [x] Run backend tests/build, both frontend image builds and container smoke checks, then rescan the resulting images.

## Task 2: Residual findings and PR

**Files:** a dated triage note under `docs/security/`, `docs/development.md`.

- [x] Record before/after results, vendor-backed residual findings, rebuild/cache guidance and production limitations.
- [x] Run applicable formatting/runtime/workflow checks, request independent review and fix actionable issues.
- [x] Prepare the reviewed remediation and verification evidence for commit and PR.

## Investigation Record

- Baseline built-image reports: backend 50 findings (6 critical), frontend 5 (1 critical), agent 161 (3 critical). These are scanner matches, not proof of exploitation.
- Spring Boot 4.1.2 manages Spring 7.0.10, Tomcat 11.0.27, Netty 4.2.19.Final, Jackson 2.21.7, HttpCore 5.4.4 and PostgreSQL 42.7.14.
- Fresh Nginx 1.30.6 still contains Brotli 1.2.0-r1/curl 8.22.0-r0, while Alpine 3.24 offers 1.2.1-r0/8.23.0-r0.
- Debian stable currently offers no newer libc6/tar/util-linux/SQLite package than the agent already contains. The glibc critical match is classified by Debian as a minor/no-DSA issue; the tar critical match is classified unimportant/intended behavior. No unsupported package replacement or scanner ignore is planned.
- The patch deprecates `Encryptors.stronger`, breaking the existing warnings-as-errors build. A direct `withPassword` replacement changes the KDF and breaks persisted data. Use the new AES-GCM secret-key API with format-one key derivation preserved, since the configuration requires random 32-byte secrets. Tests use an independently generated Spring Security 7.1.0 fixture and an independent legacy reader to prove both upgrade and rollback compatibility.
- Keep all existing verification hashes and policy intact; append hashes for new artifacts and compare key updated JARs directly against Maven Central.
- Strict full Gradle build passes (274 unit, 27 architecture, 242 integration tests). Seven cipher tests pass. Frontend standalone and prebuilt builds/smokes and backend prebuilt build/smoke pass.
- Real rescans: backend 50 -> 7, with zero critical/Java-archive matches; frontend 5 -> 3, with zero critical/high matches. Residual findings have no supported package patch and remain visible.
- Independent review found no actionable important findings and confirmed all 1,486 existing verification records/policy are unchanged; 511 records were added.
