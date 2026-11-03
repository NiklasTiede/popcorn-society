# AI Search And Inference Review

## Scope

Review the semantic and hybrid movie search architecture: embedding generation,
llama.cpp/EmbeddingGemma integration, Spring AI adapter usage, OpenSearch vector
projection, lexical search, RRF ranking, and search-quality evaluation readiness.

This inference path is distinct from the OpenAI Movie Concierge. The Agent consumes Java-owned
search behavior through MCP and must not call llama.cpp or OpenSearch directly. Use `agent` mode for
LLM orchestration, grounding, tool policy, and agent evals.

Primary files:

- `compose.yaml`
- `src/main/java/app/popcornsociety/catalog/internal/search`
- `src/main/resources/config/*.properties`
- `src/main/resources/api-calls/llama-cpp`
- `src/test/java/app/popcornsociety/catalog/internal/search`
- `src/test/java/app/popcornsociety/catalog/SearchControllerTest.java`
- `infrastructure/clusters/home/apps`
- `docs/development.md`

## Checks

### Source Of Truth And Projection

- PostgreSQL movie data remains the source of truth
- OpenSearch movie documents are rebuildable projections
- embeddings are projection data, not business source of truth
- reindex can regenerate embeddings from current movie data and current embedding text rules
- projection tasks or repair paths exist when search indexing fails after database writes

### Embedding Contract

- embedding model name is stored with indexed documents
- embedding text version is stored with indexed documents
- vector dimensions match OpenSearch mapping and the selected model
- embedding text construction is deterministic, versioned, and tested
- model/config changes have an explicit reindex requirement
- local and CI tests avoid real model calls unless an opt-in flag is set

### Inference Service

- llama.cpp endpoint, model name, and timeout behavior are externally configurable
- the backend uses a narrow adapter around Spring AI or HTTP details
- the inference service exposes a health/metrics surface when deployed
- failures during embedding creation are logged with enough context and do not silently create corrupt vectors
- resource expectations are documented for local Compose and k3s

### Search Behavior

- semantic search keeps structured filters such as genre, type, year, and runtime
- lexical search supports exact, multi-word, and prefix/title typeahead behavior
- hybrid search fuses lexical and semantic results predictably
- RRF constants and candidate windows are centralized and tested
- blank/filter-only searches do not create unnecessary embeddings
- pagination behavior is explicit for fused results

### Evaluation Readiness

- search-quality tests are separated from deterministic unit/integration tests
- eval fixtures can express query, filters, expected movie IDs, and expected top-k rank
- fixture results can compare lexical-only, semantic-only, and hybrid search
- evaluation data does not require paid external APIs
- tuning changes to embedding text, boosts, candidate windows, or RRF constants have before/after measurements

## Suggested Contract Tests

- mapping test verifies dense vector dimensions and similarity
- reindex test verifies embedding model and embedding text version are stored
- semantic request test verifies kNN filter propagation
- hybrid search test verifies lexical and semantic candidates are fused deterministically
- query builder test verifies title prefix fields and description weighting
- optional integration test against local llama.cpp stays disabled unless an explicit environment flag is set

## Report Guidance

Prefer search failure scenarios:

- "Changing the embedding model dimension would break indexing because the OpenSearch mapping still expects 768 dimensions."
- "A genre filter is applied to lexical search but not semantic search, so RRF can reintroduce out-of-filter movies."
- "Embedding text changed without a version bump, so old and new vectors are mixed in the same index."
- "CI depends on a local model service and will fail on machines without llama.cpp."

Do not turn this into a model-quality review unless the user asks for evaluation.
For relevance tuning work, recommend a dedicated search-evaluation workflow with fixtures.
