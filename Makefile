# Command index for local development, seed data, Docker images, and release support.

SEED_IMAGE = niklastiede/imdb-clone-seed
SEED_VERSION ?= local
SEED_LIGHT_TAG = $(SEED_IMAGE):light-$(SEED_VERSION)
SEED_FULL_TAG = $(SEED_IMAGE):full-$(SEED_VERSION)
SEED_CONTEXT_ROOT = build/movie-seed/docker-context
LOCAL_USERS_SQL = src/main/resources/sql/local-users.sql
APP_DOCKER_PLATFORM ?= linux/amd64
APP_DOCKER_BUILD_PLATFORM_FLAG ?= --platform $(APP_DOCKER_PLATFORM)
SEED_DOCKER_BUILD_PLATFORM_FLAG ?=
SEED_PUBLISH_PLATFORMS ?= linux/amd64,linux/arm64
K8S_RENDER_OUTPUT ?= /tmp/popcorn-society-home-apps.yaml
K8S_SEED_RENDER_OUTPUT ?= /tmp/popcorn-society-movie-seed.yaml
K8S_SCHEMA_OUTPUT ?= /tmp/popcorn-society-home-apps-schema.yaml
KUBECONFORM_IMAGE ?= ghcr.io/yannh/kubeconform:v0.6.7
OPENAPI_CHECK_DIR ?= /tmp/popcorn-society-openapi-check
POPCORN_SOCIETY_OPENAPI_BASE_URL ?= http://localhost:8080
AGENT_DIR = agent
AGENT_IMAGE ?= popcorn-society-agent:local
AGENT_APP_VERSION ?= 0.1.0
AGENT_SMOKE_PORT ?= 18090
BACKEND_SMOKE_PORT ?= 18081
FRONTEND_SMOKE_PORT ?= 18080
UV_CACHE_DIR ?= $(CURDIR)/.uv-cache
CLUSTER_ACCESS_SCRIPT = scripts/cluster-access

.DEFAULT_GOAL := help

##@ Prerequisites

.PHONY: check-local-tools check-agent-tools check-seed-tools check-verification-tools check-kubernetes-verification-tools

check-local-tools: ## check tools needed for the README local workflow
	@missing=0; \
	for tool in docker java curl sed node yarn; do \
		if ! command -v $$tool >/dev/null 2>&1; then \
			echo "missing: $$tool"; \
			missing=1; \
		fi; \
	done; \
	if ! docker compose version >/dev/null 2>&1; then \
		echo "missing: docker compose"; \
		missing=1; \
	fi; \
	if [ $$missing -eq 0 ]; then \
		echo "All local development tools are available."; \
	else \
		exit 1; \
	fi

check-agent-tools: ## check tools needed for Python agent development
	@missing=0; \
	for tool in uv docker curl; do \
		if ! command -v $$tool >/dev/null 2>&1; then \
			echo "missing: $$tool"; \
			missing=1; \
		fi; \
	done; \
	if [ $$missing -eq 0 ]; then \
		echo "All agent development tools are available."; \
	else \
		exit 1; \
	fi

check-seed-tools: check-local-tools ## check extra tools needed to build/publish seed images
	@missing=0; \
	for tool in python3; do \
		if ! command -v $$tool >/dev/null 2>&1; then \
			echo "missing: $$tool"; \
			missing=1; \
		fi; \
	done; \
	if ! docker buildx version >/dev/null 2>&1; then \
		echo "missing: docker buildx"; \
		missing=1; \
	fi; \
	if [ $$missing -eq 0 ]; then \
		echo "All seed image tools are available."; \
	else \
		exit 1; \
	fi

check-verification-tools: check-local-tools ## check extra tools needed for verification gates
	@missing=0; \
	for tool in git kubectl diff ruby; do \
		if ! command -v $$tool >/dev/null 2>&1; then \
			echo "missing: $$tool"; \
			missing=1; \
		fi; \
	done; \
	if [ $$missing -eq 0 ]; then \
		echo "All verification tools are available."; \
	else \
		exit 1; \
	fi

check-kubernetes-verification-tools: ## check tools needed for deterministic Kubernetes verification
	@missing=0; \
	for tool in docker kubectl ruby; do \
		if ! command -v $$tool >/dev/null 2>&1; then \
			echo "missing: $$tool"; \
			missing=1; \
		fi; \
	done; \
	if [ $$missing -eq 0 ]; then \
		echo "All Kubernetes verification tools are available."; \
	else \
		exit 1; \
	fi

##@ Local development

.PHONY: docker-compose-dev-up docker-compose-dev-down seed-local-users seed-light seed-full reindex-local-search

docker-compose-dev-up: ## start local backend dependencies, including llama.cpp embeddings
	docker compose up -d

docker-compose-dev-down: ## stop local Docker Compose services
	docker compose down

seed-local-users: ## create local roles and demo accounts without touching movie data
	docker exec -i popcorn-society-postgresql psql -U myroot -d movie_db < $(LOCAL_USERS_SQL)

seed-light: ## run lightweight seed against local Docker Compose services
	docker run --rm --network popcorn-society-network \
		-e POSTGRES_HOST=popcorn-society-postgresql \
		-e POSTGRES_DB=movie_db \
		-e POSTGRES_USER=myroot \
		-e POSTGRES_PASSWORD=secret \
		-e RUSTFS_ENDPOINT=http://popcorn-society-rustfs:9000 \
		-e RUSTFS_ACCESS_KEY=ROOTNAME \
		-e RUSTFS_SECRET_KEY=CHANGEME123 \
		-e RUSTFS_BUCKET=imdb-clone \
		-e SEED_NAME=light \
		-e SEED_VERSION=$(SEED_VERSION) \
		$(SEED_LIGHT_TAG) all

seed-full: ## run full seed against local Docker Compose services
	docker run --rm --network popcorn-society-network \
		-e POSTGRES_HOST=popcorn-society-postgresql \
		-e POSTGRES_DB=movie_db \
		-e POSTGRES_USER=myroot \
		-e POSTGRES_PASSWORD=secret \
		-e RUSTFS_ENDPOINT=http://popcorn-society-rustfs:9000 \
		-e RUSTFS_ACCESS_KEY=ROOTNAME \
		-e RUSTFS_SECRET_KEY=CHANGEME123 \
		-e RUSTFS_BUCKET=imdb-clone \
		-e SEED_NAME=full \
		-e SEED_VERSION=$(SEED_VERSION) \
		$(SEED_FULL_TAG) all

reindex-local-search: ## rebuild local OpenSearch movie index from PostgreSQL
	@TOKEN=$$(curl -fsS -H 'Content-Type: application/json' \
		-d '{"usernameOrEmail":"les_grossman","password":"Encrypted!Pa55worD"}' \
		http://localhost:8080/api/v1/auth/login \
		| sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'); \
	curl -fsS -X POST -H "Authorization: Bearer $$TOKEN" \
		http://localhost:8080/api/v1/search/movies/reindex

##@ Production operator access

.PHONY: cluster-access-start cluster-access-status cluster-access-stop cluster-copy-postgres-password cluster-copy-rustfs-access-key cluster-copy-rustfs-secret-key cluster-copy-grafana-admin-password cluster-copy-grafana-viewer-password

cluster-access-start: ## start private SSH-backed cluster service tunnels
	./$(CLUSTER_ACCESS_SCRIPT) start

cluster-access-status: ## show private cluster tunnel health and endpoints
	./$(CLUSTER_ACCESS_SCRIPT) status

cluster-access-stop: ## stop only private tunnels created by the access script
	./$(CLUSTER_ACCESS_SCRIPT) stop

cluster-copy-postgres-password: ## copy the PostgreSQL application password to the macOS clipboard
	./$(CLUSTER_ACCESS_SCRIPT) copy-postgres-password

cluster-copy-rustfs-access-key: ## copy the RustFS access key to the macOS clipboard
	./$(CLUSTER_ACCESS_SCRIPT) copy-rustfs-access-key

cluster-copy-rustfs-secret-key: ## copy the RustFS secret key to the macOS clipboard
	./$(CLUSTER_ACCESS_SCRIPT) copy-rustfs-secret-key

cluster-copy-grafana-admin-password: ## copy the Grafana admin password to the macOS clipboard
	./$(CLUSTER_ACCESS_SCRIPT) copy-grafana-admin-password

cluster-copy-grafana-viewer-password: ## copy the Grafana viewer password to the macOS clipboard
	./$(CLUSTER_ACCESS_SCRIPT) copy-grafana-viewer-password

##@ Seed images

.PHONY: prepare-seed-light prepare-seed-full build-seed-light build-seed-full publish-seed-light publish-seed-full push-seed-light push-seed-full

prepare-seed-light: ## prepare lightweight seed Docker context
	python3 infrastructure/movie-seed/runtime/prepare_seed_context.py --profile light

prepare-seed-full: ## prepare full seed Docker context
	python3 infrastructure/movie-seed/runtime/prepare_seed_context.py --profile full

build-seed-light: prepare-seed-light ## build lightweight seed image
	docker build $(SEED_DOCKER_BUILD_PLATFORM_FLAG) -t $(SEED_LIGHT_TAG) $(SEED_CONTEXT_ROOT)/light

build-seed-full: prepare-seed-full ## build full seed image
	docker build $(SEED_DOCKER_BUILD_PLATFORM_FLAG) -t $(SEED_FULL_TAG) $(SEED_CONTEXT_ROOT)/full

publish-seed-light: prepare-seed-light ## build and push multi-arch lightweight seed image
	docker buildx build --platform $(SEED_PUBLISH_PLATFORMS) -t $(SEED_LIGHT_TAG) --push $(SEED_CONTEXT_ROOT)/light

publish-seed-full: prepare-seed-full ## build and push multi-arch full seed image
	docker buildx build --platform $(SEED_PUBLISH_PLATFORMS) -t $(SEED_FULL_TAG) --push $(SEED_CONTEXT_ROOT)/full

push-seed-light: publish-seed-light ## build and push multi-arch lightweight seed image

push-seed-full: publish-seed-full ## build and push multi-arch full seed image

##@ Backend

.PHONY: run-backend generate-jar docker-build-backend docker-build-backend-prebuilt docker-run-backend container-smoke-backend

run-backend: ## bootRun java backend
	./gradlew bootRun

generate-jar: ## clean and build jar file (for building docker image)
	./gradlew clean
	./gradlew bootJar

DOCKER_IMG_BACKEND ?= popcorn-society-backend:local

docker-build-backend: ## build backend docker image from Dockerfile
	docker build $(APP_DOCKER_BUILD_PLATFORM_FLAG) -t $(DOCKER_IMG_BACKEND) .

docker-build-backend-prebuilt: ## package the existing backend build as a runtime image
	docker build $(APP_DOCKER_BUILD_PLATFORM_FLAG) -f Dockerfile.runtime -t $(DOCKER_IMG_BACKEND) .

docker-run-backend: ## run backend docker container
	docker run --name popcorn-society-backend -p 8080:8080 $(DOCKER_IMG_BACKEND)

container-smoke-backend: ## smoke-test backend health probes and non-root read-only runtime
	BACKEND_IMAGE=$(DOCKER_IMG_BACKEND) BACKEND_DOCKER_PLATFORM=$(APP_DOCKER_PLATFORM) BACKEND_SMOKE_PORT=$(BACKEND_SMOKE_PORT) bash src/test/container/smoke.sh

##@ Frontend

.PHONY: npm-install generate-client npm-lint run-frontend docker-build-frontend docker-build-frontend-prebuilt docker-run-frontend container-smoke-frontend

npm-install: ## install NPM dependencies
	cd ./frontend; yarn install

generate-client: ## generate client code from openapi spec
	cd ./frontend; yarn run build:moviesGen

npm-lint: ## lint frontend code
	cd ./frontend; yarn run lint

run-frontend: ## run frontend
	cd ./frontend; yarn run start

DOCKER_IMG_FRONTEND ?= popcorn-society-frontend:local

docker-build-frontend: ## build frontend docker image from Dockerfile
	cd ./frontend; docker build $(APP_DOCKER_BUILD_PLATFORM_FLAG) -t $(DOCKER_IMG_FRONTEND) .

docker-build-frontend-prebuilt: ## package the existing frontend build as a runtime image
	cd ./frontend; docker build $(APP_DOCKER_BUILD_PLATFORM_FLAG) -f Dockerfile.runtime -t $(DOCKER_IMG_FRONTEND) .

docker-run-frontend: ## run frontend docker container
	docker run --name popcorn-society-frontend -p 3000:8080 $(DOCKER_IMG_FRONTEND)

container-smoke-frontend: ## smoke-test frontend SPA and non-root read-only runtime
	FRONTEND_IMAGE=$(DOCKER_IMG_FRONTEND) FRONTEND_DOCKER_PLATFORM=$(APP_DOCKER_PLATFORM) FRONTEND_SMOKE_PORT=$(FRONTEND_SMOKE_PORT) bash frontend/tests/container/smoke.sh

##@ Movie Concierge Agent

.PHONY: agent-sync run-agent run-agent-fake eval-agent eval-agent-live verify-agent-format verify-agent-lint verify-agent-types verify-agent-architecture verify-agent-tests verify-agent docker-build-agent container-smoke-agent

AGENT_EVAL_CASE ?=
AGENT_EVAL_CASE_FLAG = $(if $(AGENT_EVAL_CASE),--case $(AGENT_EVAL_CASE),)

.PHONY: probe-agent-voice-live probe-agent-voice-interrupt-live

.PHONY: run-agent-voice
run-agent-voice: ## run local Movie Concierge with bounded English microphone sessions
	POPCORN_SOCIETY_AGENT_VOICE_ENABLED=true $(MAKE) run-agent

.PHONY: run-agent-voice-compare
run-agent-voice-compare: ## enable Grok and GPT-Live 1 for local microphone comparison
	POPCORN_SOCIETY_AGENT_VOICE_LIVE_ENABLED=true $(MAKE) run-agent-voice

probe-agent-voice-live: ## replay synthetic English audio; requires POPCORN_SOCIETY_AGENT_LIVE_EVALS_ENABLED=true
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run --locked popcorn-agent-voice-probe --live

probe-agent-voice-interrupt-live: ## verify cancellation after fixture lookup and 200 ms of audio
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run --locked popcorn-agent-voice-probe --live --interrupt-after-audio-ms 200

agent-sync: ## sync the locked Python agent development environment
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv sync --locked --all-groups

run-agent: ## run the local Luna-powered Movie Concierge on port 8090
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run uvicorn popcorn_society_agent.bootstrap:create_app --factory --host 127.0.0.1 --port 8090 --no-access-log

run-agent-fake: ## run the deterministic Movie Concierge without a model key or Java
	cd $(AGENT_DIR) && POPCORN_SOCIETY_AGENT_MODEL_BACKEND=fake UV_CACHE_DIR=$(UV_CACHE_DIR) uv run uvicorn popcorn_society_agent.bootstrap:create_app --factory --host 127.0.0.1 --port 8090 --no-access-log

eval-agent: ## run the complete deterministic Movie Concierge eval set
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run popcorn-agent-eval $(AGENT_EVAL_CASE_FLAG)

eval-agent-live: ## run opt-in Luna evals; also requires POPCORN_SOCIETY_AGENT_LIVE_EVALS_ENABLED=true
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run popcorn-agent-eval --live $(AGENT_EVAL_CASE_FLAG)

verify-agent-format: ## check Python agent formatting
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run ruff format --check .

verify-agent-lint: ## lint the Python agent
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run ruff check .

verify-agent-types: ## run strict Pyright for the Python agent
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run pyright

verify-agent-architecture: ## verify Python agent import contracts and architecture tests
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run lint-imports
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run pytest tests/test_architecture.py

verify-agent-tests: ## run deterministic Python agent tests
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run pytest
	cd $(AGENT_DIR) && UV_CACHE_DIR=$(UV_CACHE_DIR) uv run popcorn-agent-eval

verify-agent: verify-agent-format verify-agent-lint verify-agent-types verify-agent-architecture verify-agent-tests ## run the complete Python agent gate

docker-build-agent: ## build the Python agent image
	docker build $(APP_DOCKER_BUILD_PLATFORM_FLAG) --build-arg APP_VERSION=$(AGENT_APP_VERSION) -t $(AGENT_IMAGE) $(AGENT_DIR)

container-smoke-agent: ## smoke-test the Python agent image, endpoints, and non-root user
	AGENT_IMAGE=$(AGENT_IMAGE) AGENT_DOCKER_PLATFORM=$(APP_DOCKER_PLATFORM) AGENT_SMOKE_PORT=$(AGENT_SMOKE_PORT) ./$(AGENT_DIR)/tests/container/smoke.sh

##@ Verification

.PHONY: verify-release-workflows verify-kubernetes-render verify-seed-release verify-kubernetes-schema verify-runtime-hardening verify-movie-concierge-production verify-observability-production verify-observability-charts verify-openapi-drift verify-popcorn-migration

verify-popcorn-migration: ## validate the staged Popcorn Society domain migration without deploying
	ruby infrastructure/migrations/popcorn-society/verify.rb

verify-release-workflows: ## verify protected-branch CI and release PR contracts
	ruby infrastructure/clusters/home/tests/verify_release_workflows.rb
	python3 -m unittest discover -s scripts/tests -p 'test_release_manifests.py'
	ruby infrastructure/clusters/home/tests/verify_container_scans.rb
	python3 -m unittest discover -s scripts/tests -p 'test_container_scan_targets.py'

verify-kubernetes-render: check-kubernetes-verification-tools ## render home-cluster Kubernetes manifests
	kubectl kustomize infrastructure/clusters/home/apps > $(K8S_RENDER_OUTPUT)

verify-seed-release: verify-kubernetes-render ## verify normal releases cannot run the manual movie seed
	kubectl kustomize infrastructure/clusters/home/maintenance/movie-seed > $(K8S_SEED_RENDER_OUTPUT)
	ruby infrastructure/clusters/home/tests/verify_seed_release.rb \
		$(K8S_RENDER_OUTPUT) $(K8S_SEED_RENDER_OUTPUT)

verify-kubernetes-schema: verify-metric-branding verify-release-workflows verify-seed-release verify-runtime-hardening verify-movie-concierge-production verify-observability-production ## validate rendered Kubernetes manifests with pinned kubeconform
	ruby -ryaml -e 'ARGV.each { |path| YAML.load_stream(File.read(path)).each { |doc| next if doc.nil?; doc.delete("sops") if doc.is_a?(Hash); puts YAML.dump(doc) } }' \
		$(K8S_RENDER_OUTPUT) $(K8S_SEED_RENDER_OUTPUT) > $(K8S_SCHEMA_OUTPUT)
	docker run --rm -i $(KUBECONFORM_IMAGE) \
		-strict \
		-summary \
		-ignore-missing-schemas \
		< $(K8S_SCHEMA_OUTPUT)

verify-runtime-hardening: verify-kubernetes-render ## verify backend/frontend non-root runtimes and HTTP probes
	ruby infrastructure/clusters/home/tests/verify_runtime_hardening.rb $(K8S_RENDER_OUTPUT)

verify-movie-concierge-production: verify-kubernetes-render ## verify production agent GitOps and guardrail contracts
	ruby infrastructure/clusters/home/tests/verify_movie_concierge.rb $(K8S_RENDER_OUTPUT)

verify-observability-production: verify-kubernetes-render ## verify logging, tracing, storage, and private-access contracts
	ruby infrastructure/clusters/home/tests/verify_observability.rb $(K8S_RENDER_OUTPUT)

verify-observability-charts: check-kubernetes-verification-tools ## render pinned observability charts and validate Alloy
	./infrastructure/clusters/home/tests/verify_observability_charts.sh

verify-openapi-drift: ## compare checked-in OpenAPI/client output with a running backend
	rm -rf $(OPENAPI_CHECK_DIR)
	mkdir -p $(OPENAPI_CHECK_DIR)
	curl -fsS $(POPCORN_SOCIETY_OPENAPI_BASE_URL)/v3/api-docs.yaml > $(OPENAPI_CHECK_DIR)/popcorn-society-backend.yaml
	diff -u frontend/src/client/popcorn-society-backend.yaml $(OPENAPI_CHECK_DIR)/popcorn-society-backend.yaml
	cd ./frontend; yarn openapi-generator-cli generate -i $(OPENAPI_CHECK_DIR)/popcorn-society-backend.yaml -g typescript-axios -t ./openapi-templates/typescript-axios -o $(OPENAPI_CHECK_DIR)/generator-output
	diff -qr --exclude=FILES frontend/src/client/movies/generator-output $(OPENAPI_CHECK_DIR)/generator-output

##@ Docker housekeeping

.PHONY: docker-show docker-clean

docker-show: ## show all images and containers
	docker image ls -a
	docker container ls -a

docker-clean: ## remove local app docker images and containers
	docker rmi -f $(DOCKER_IMG_FRONTEND) $(DOCKER_IMG_BACKEND)
	docker rm -f $(DOCKER_IMG_FRONTEND) $(DOCKER_IMG_BACKEND)

##@ Help

.PHONY: help

help: ## show this command index
	@awk 'BEGIN {FS = ":.*?## "; printf "\nUsage:\n  make \033[36m<target>\033[0m\n"} /^##@ / {printf "\n\033[1m%s\033[0m\n", substr($$0, 5)} /^[a-zA-Z0-9_-]+:.*?## / {printf "  \033[36m%-28s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

##@ Voice reliability
.PHONY: test-voice-stress test-voice-stress-live summarize-voice-stress
VOICE_STRESS_PROFILE ?= stress

test-voice-stress: ## deterministic browser audio chaos; no provider API calls
	cd frontend && yarn playwright test -c playwright.voice-stress.config.ts chaos.spec.ts

test-voice-stress-live: ## paid, bounded real-browser Grok/GPT-Live comparison; local Java required
	cd frontend && VOICE_STRESS_LIVE=1 VOICE_STRESS_PROFILE=$(VOICE_STRESS_PROFILE) yarn playwright test -c playwright.voice-stress.config.ts live.spec.ts

summarize-voice-stress: ## summarize retained live runs, including failures and incomplete sessions
	python3 scripts/summarize-voice-stress.py

.PHONY: verify-metric-branding audit-project-names
verify-metric-branding: ## validate dashboard PromQL and old/new metric transition
	ruby infrastructure/clusters/home/tests/verify_metric_branding_permissions_test.rb
	ruby infrastructure/clusters/home/tests/verify_metric_branding.rb

audit-project-names: ## inventory remaining project-name references without printing file contents
	python3 scripts/audit-project-names.py
