# Persistence, JPA, and PostgreSQL Review

## Scope

Review Flyway migrations, SQL seed/test data, JPA entities, repositories, enum converters, auditing, and integration tests.

Primary files:

- `src/main/resources/db/migration/*.sql`
- `src/test/resources/sql/test-data.sql`
- `src/main/java/app/popcornsociety/*/internal/persistence`
- `src/main/java/app/popcornsociety/shared/persistence`
- `src/test/java/app/popcornsociety/shared/DatabaseSchemaTest.java`

## Checks

### Schema Ownership

- Flyway owns schema evolution. New schema changes should be new `V...__description.sql` migrations.
- Dataset imports, movie seed data, and RustFS image imports are not schema migrations. Check `docs/development.md` and infrastructure seed docs before criticizing this split.
- Existing migrations should not be rewritten after they have been applied unless the user explicitly asks.
- Persistence types and repositories belong inside their owning Spring Modulith module's `internal/persistence` package.

### Entity to Schema Consistency

Compare each entity to the effective Flyway schema:

- table name and column name match the JPA naming strategy or explicit annotations
- Java nullability and database nullability agree for required fields
- `@Column(length, precision, scale, nullable)` matches PostgreSQL column type
- `@Enumerated` and converters match stable database representation
- generated IDs, embedded IDs, and composite primary keys match database keys
- audit fields match mapped superclasses and timestamp defaults
- `@Transient` is intentional and not hiding a broken relationship

Flag dangerous mismatches:

- database allows null but domain assumes required
- Java enum ordinal storage
- missing `nullable = false` for required relations
- precision too small for valid domain values
- string lengths too short for image tokens, email, titles, or generated tokens

### Relations and Constraints

For each association:

- foreign key exists in PostgreSQL unless deliberately absent and documented
- cascade/delete behavior matches JPA cascade/orphan removal and service behavior
- join table names and column names are stable
- many-to-many ownership is explicit enough to avoid accidental table drift
- composite key classes implement correct equality behavior
- deleting parent rows cannot leave orphan rows unless the domain requires history

For lookup performance:

- foreign key columns used independently have indexes
- common query sort/filter columns have supporting indexes
- composite indexes match repository query order where possible
- avoid indexing everything without evidence

### Repositories and Query Shape

- repository method names match real indexes and expected cardinality
- pageable queries have deterministic ordering where the UI expects stable pages
- custom queries do not bypass domain invariants
- write paths update aggregate counters consistently, especially ratings and counts
- cross-module persistence access is a Modulith violation unless it goes through an allowed named interface

### Tests and Hard Checks

Look for the existing `DatabaseSchemaTest`. Suggest extending it before suggesting manual review-only rules.

Good follow-up checks:

- assert Flyway migration list
- assert foreign key delete rules
- assert indexes for relation tables
- assert column precision/scale/length for fragile fields
- add persistence tests for cascade/delete behavior
- add entity mapping tests for composite keys and enum converters
