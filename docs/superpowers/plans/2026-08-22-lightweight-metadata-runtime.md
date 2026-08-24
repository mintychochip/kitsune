# Lightweight Metadata Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make lexical SQLite FTS metadata search the smallest default runtime while retaining optional offline sparse and remote ranking profiles.

**Architecture:** The core projects canonical `ItemDescriptor` metadata into SQLite FTS and retrieves a bounded candidate set with persisted world/chunk/radius constraints. Existing live-root and protection checks run before an optional ranking extension. Lexical mode never creates or loads embeddings; sparse and OpenAI-compatible implementations move into optional modules behind one provider mechanism.

**Tech Stack:** Java 25, Gradle 9.5.1, SQLite JDBC/FTS5, JUnit 5, Paper/Bukkit.

## Global Constraints

- Default profile: lexical SQLite FTS; no model, credentials, HTTP provider, embedding generation, vector decode, or embedding cache.
- Player text is tokenized and quoted; raw text is never accepted as SQLite `MATCH` syntax.
- Persisted world/chunk/radius constraints bound candidate retrieval; live-root and protection checks remain mandatory before exposure or reranking.
- Optional reranking improves ordering only and cannot recover candidates omitted by lexical retrieval.
- Low-overlap fallback is explicitly bounded by candidate count and nearby roots.
- Existing snapshot threading, reconciliation, sessions, markers, platform boundaries, and protection behavior remain intact.
- Vector-profile databases rebuild transactionally; vector data is retired only after the lexical projection verifies successfully.
- Tests are deterministic and never contact an external service.

---

### Task 1: Safe lexical query and metadata projection

**Files:**
- Create: `common/src/main/java/dev/jlo/kitsune/search/LexicalQuery.java`
- Create: `common/src/main/java/dev/jlo/kitsune/search/LexicalQueryParser.java`
- Create: `common/src/main/java/dev/jlo/kitsune/index/LexicalDocument.java`
- Create: `common/src/main/java/dev/jlo/kitsune/index/LexicalDocumentProjector.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/embedding/FeatureVocabulary.java`
- Test: `common/src/test/java/dev/jlo/kitsune/search/LexicalQueryParserTest.java`
- Test: `common/src/test/java/dev/jlo/kitsune/index/LexicalDocumentProjectorTest.java`

**Interfaces:**
- Produces `LexicalQuery(List<String> exactTerms, List<String> expandedTerms, String matchExpression)`.
- Produces `LexicalQueryParser.parse(String): LexicalQuery`.
- Produces `LexicalDocumentProjector.project(ItemDescriptor): LexicalDocument` with separate material/name, enchantment/trait/tag, and lore fields.

- [ ] Write failing tokenizer tests for case folding, Unicode, punctuation, repeated terms, quoted input, empty input, and literal `MATCH`, `OR`, quotes, parentheses, dashes, and wildcard characters.
- [ ] Run `./gradlew :common:test --tests '*LexicalQueryParserTest'`; expect compilation failure because the parser does not exist.
- [ ] Implement token normalization and controlled query generation. Construct the FTS expression only from normalized tokens, double embedded quotes, and quote every term; never preserve user operators.
- [ ] Run the tokenizer test and confirm PASS.
- [ ] Write failing projection tests proving material/name, enchantments, tags/traits/attributes, and lore remain separate and deterministic.
- [ ] Implement `LexicalDocumentProjector`, reusing `FeatureVocabulary` alias data through a package-neutral read-only API rather than duplicating vocabulary.
- [ ] Run both targeted test classes and confirm PASS.

### Task 2: Remove mandatory embeddings from core item models

**Files:**
- Modify: `api/src/main/java/dev/jlo/kitsune/model/IndexedItem.java`
- Modify: `api/src/main/java/dev/jlo/kitsune/model/ContainerSnapshot.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/IndexRepository.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/ContainerIndex.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/search/SearchService.java`
- Test: existing API, index repository, container index, and search service contract tests

**Interfaces:**
- `IndexedItem(ItemPath path, int amount, ItemDescriptor descriptor)` is the embedding-free persisted/searchable item model.
- `ContainerSnapshot.items()` remains `List<IndexedItem>` but no element can carry an `Embedding`.
- `IndexRepository.replaceRoot`, `loadDocuments`, and repository open/startup contracts no longer require an `EmbeddingProvider`.
- Sparse/remote vector resolution moves out of core persistence and operates only on bounded immutable `RankingCandidate` descriptors.

- [ ] Change API and boundary tests first so compilation fails wherever core code still constructs or reads `IndexedItem.embedding()`.
- [ ] Run `./gradlew :api:test :common:test :platform:bukkit:test`; confirm failure identifies embedding-bearing call sites.
- [ ] Remove `Embedding` from `IndexedItem` and migrate `ContainerSnapshot`, index queue, repository, and platform snapshot callers to the three-field model.
- [ ] Remove core `IndexRepository.findEmbeddings`, `putEmbeddings`, `reembedAll`, provider-parameterized `loadDocuments`, and provider-dependent open/startup contracts.
- [ ] Change core SQLite reads/writes to persist canonical descriptors without constructing vectors; keep the old vector schema readable until Task 6 performs verified profile migration.
- [ ] Compile and run all existing API/common/Bukkit tests, updating only expectations whose contract intentionally changed; confirm PASS before adding FTS.

### Task 2: FTS schema and atomic index maintenance

**Files:**
- Create: `common/src/main/resources/db/migration/V3__lexical_fts.sql`
- Create: `common/src/main/java/dev/jlo/kitsune/index/LexicalSearchBounds.java`
- Create: `common/src/main/java/dev/jlo/kitsune/index/LexicalCandidate.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/IndexRepository.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java`
- Test: `common/src/test/java/dev/jlo/kitsune/index/SqliteLexicalIndexRepositoryTest.java`

**Interfaces:**
- Adds `IndexRepository.findLexicalCandidates(LexicalQuery, LexicalSearchBounds, int): List<LexicalCandidate>`.
- `LexicalSearchBounds` carries `UUID worldId`, minimum/maximum chunk coordinates, center block coordinates, and squared radius.
- `LexicalCandidate` carries `RootIdentity`, path, amount, descriptor, and SQLite relevance score without an `Embedding`.

- [ ] Write failing repository tests for FTS insertion through `replaceRoot`, exact and prefix retrieval, field weighting, world/chunk/radius exclusion, hard result limits, restart persistence, replacement cleanup, and deletion cleanup.
- [ ] Run `./gradlew :common:test --tests '*SqliteLexicalIndexRepositoryTest'`; expect failure because the lexical repository contract does not exist.
- [ ] Add the FTS5 schema and profile metadata. Keep FTS rows keyed to existing container/path identities so root replacement and foreign-key deletion cannot leave stale searchable rows.
- [ ] Extend `replaceRoot` and `deleteRoot` transactions to maintain lexical rows atomically with canonical descriptors.
- [ ] Implement candidate SQL that joins `containers` and `chunks`, filters world/chunk availability and squared block distance before `ORDER BY bm25(...) LIMIT ?`, and decodes only bounded winners.
- [ ] Run the targeted repository tests and confirm PASS.

### Task 3: Lexical search path with authorization boundaries

**Files:**
- Create: `common/src/main/java/dev/jlo/kitsune/search/LexicalRanker.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/search/SearchService.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/search/SearchPolicy.java`
- Test: `common/src/test/java/dev/jlo/kitsune/search/LexicalSearchServiceTest.java`

**Interfaces:**
- Produces `LexicalRanker.rank(List<LexicalCandidate>, int maxResults, int maxPathsPerRoot): List<RootMatch>`.
- Search retains existing `SearchContext`, `SearchGuard`, `AllowedRoot`, and live-validation contracts.
- Adds explicit `lexicalCandidateLimit` and `lowOverlapRootLimit` policy bounds.

- [ ] Write failing tests proving exact material/name matches outrank secondary metadata, per-root paths and total roots are bounded, and punctuation-only queries return deterministically without repository work.
- [ ] Add tests proving radius, unavailable/deleted/stale roots, and denied protection are excluded before results are rendered.
- [ ] Add a recording optional ranker in tests and prove it receives only radius- and protection-authorized candidates.
- [ ] Add low-overlap tests proving fallback inspects no more than `lowOverlapRootLimit` nearby authorized roots and never scans another world or unavailable chunks.
- [ ] Run `./gradlew :common:test --tests '*LexicalSearchServiceTest'`; expect failure before the lexical path exists.
- [ ] Implement bounded lexical retrieval/ranking while preserving current pagination, readiness, session cancellation, marker, and live-root behavior.
- [ ] Run targeted search tests and existing `SearchServiceTest`; confirm PASS.

### Task 4: One optional ranking-provider boundary

**Files:**
- Create: `api/src/main/java/dev/jlo/kitsune/api/ranking/RankingProvider.java`
- Create: `api/src/main/java/dev/jlo/kitsune/api/ranking/RankingCandidate.java`
- Create: `common/src/main/java/dev/jlo/kitsune/search/RankingProviderRegistry.java`
- Remove after migration: `common/src/main/java/dev/jlo/kitsune/embedding/EmbeddingRegistry.java`
- Remove after migration: `common/src/main/java/dev/jlo/kitsune/embedding/EmbeddingProviderCatalog.java`
- Test: `common/src/test/java/dev/jlo/kitsune/search/RankingProviderRegistryTest.java`

**Interfaces:**
- `RankingProvider.id(): String`.
- `RankingProvider.rerank(String query, List<RankingCandidate>): List<RankingCandidate>`.
- `RankingCandidate` contains immutable `RootIdentity`, `ItemPath`, amount, canonical `ItemDescriptor`, and local score. Providers may reorder or return a subset but may not introduce or mutate candidates.
- Registry always supports an absent provider for lexical mode and rejects blank/duplicate IDs.

- [ ] Write failing contract tests for nonblank/unique provider IDs, immutable candidate descriptors and identity/path metadata, subset-only output, duplicate candidate rejection, introduced/mutated candidate rejection, and provider exceptions falling back to unchanged local order.
- [ ] Run the targeted tests and confirm failure before the new API exists.
- [ ] Implement the API and single registry without dependencies on Bukkit, SQLite, HTTP, or embedding types.
- [ ] Migrate `SearchService` to call the registry only after local authorization and bounded candidate selection.
- [ ] Remove both legacy provider-selection paths after every caller is migrated.
- [ ] Run API/common ranking and search tests; confirm PASS.

### Task 5: Profile-aware migration and vector retirement

**Files:**
- Create: `common/src/main/java/dev/jlo/kitsune/index/IndexProfile.java`
- Create: `common/src/main/java/dev/jlo/kitsune/index/LexicalProfileMigrator.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java`
- Create: `common/src/main/resources/db/migration/V4__lexical_profile.sql`
- Test: `common/src/test/java/dev/jlo/kitsune/index/LexicalProfileMigrationTest.java`

**Interfaces:**
- `IndexProfile` distinguishes `LEXICAL`, `SPARSE`, and `REMOTE` persisted representations.
- `LexicalProfileMigrator.rebuild(Connection): void` completes atomically or leaves the old profile recoverable.
- Repository readiness remains false until lexical row counts and container/path references verify.

- [ ] Write failing tests for a fresh lexical database, V2 vector database rebuild, interrupted/retried rebuild, row-count mismatch rollback, stale FTS cleanup, profile-marker switch, and post-verification vector-table retirement.
- [ ] Run `./gradlew :common:test --tests '*LexicalProfileMigrationTest'`; expect failure before profile migration exists.
- [ ] Implement transactional projection from stored canonical descriptor blobs into FTS rows and verify counts/references before switching profile metadata.
- [ ] Retire `items.provider_id`, `provider_version`, `vector`, `vector_norm`, and `embeddings` only in the verified lexical replacement schema; preserve the original database transaction on failure.
- [ ] Ensure a fresh lexical database never creates vector cache tables.
- [ ] Run migration and repository lifecycle tests; confirm PASS.

### Task 6: Split sparse and remote implementations into addons

**Files:**
- Modify: `settings.gradle.kts`
- Create: `ranking/sparse/build.gradle.kts`
- Create: `ranking/remote-openai/build.gradle.kts`
- Move sparse implementation files from `common/src/main/java/dev/jlo/kitsune/embedding/` to `ranking/sparse/src/main/java/dev/jlo/kitsune/ranking/sparse/`.
- Move remote implementation files from `common/src/main/java/dev/jlo/kitsune/embedding/remote/` to `ranking/remote-openai/src/main/java/dev/jlo/kitsune/ranking/remote/`.
- Add service registration files under each addon’s `src/main/resources/META-INF/services/`.
- Modify: `common/build.gradle.kts`
- Modify: `paper/build.gradle.kts`
- Test: addon-local provider tests and `common/src/test/java/dev/jlo/kitsune/common/CommonDependencyBoundaryTest.java`

**Interfaces:**
- Both addons implement `RankingProvider` and register through `ServiceLoader`.
- Sparse and remote implementations consume `RankingCandidate.descriptor()` directly; they do not reload Bukkit state or query the repository.
- The core artifact has no compile/runtime dependency on either addon.
- Remote addon alone owns HTTP transport, credentials, dense vectors, retries, and response parsing.

- [ ] Add failing dependency-boundary tests proving `common` has no references to OpenAI, credential, dense-vector, sparse-vector, or embedding-provider implementation packages.
- [ ] Move sparse code/tests and adapt it to rerank only the bounded local candidate list while retaining deterministic offline behavior.
- [ ] Move remote code/tests and adapt batching so it embeds the query and bounded candidate descriptors only at search time.
- [ ] Remove `jackson-databind` from `common` if no remaining core use exists; add it to the remote addon.
- [ ] Configure the default Paper shadow JAR without addon dependencies; publish addon JARs separately.
- [ ] Run `./gradlew :common:test :ranking:sparse:test :ranking:remote-openai:test :paper:test`; confirm PASS.
- [ ] Inspect the Paper JAR and confirm no remote/sparse provider implementation classes are present.

### Task 7: Lexical-default configuration and runtime wiring

**Files:**
- Modify: `common/src/main/java/dev/jlo/kitsune/config/KitsuneConfig.java`
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/config/ConfigLoader.java`
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/BukkitRuntime.java`
- Modify: `paper/src/main/resources/config.yml`
- Test: `common/src/test/java/dev/jlo/kitsune/config/KitsuneConfigTest.java`
- Test: `platform/bukkit/src/test/java/dev/jlo/kitsune/config/ConfigLoaderTest.java`
- Test: `platform/bukkit/src/test/java/dev/jlo/kitsune/bukkit/BukkitRuntimeLifecycleTest.java`

**Interfaces:**
- Replace `embedding.provider` with `search.profile`, defaulting to `lexical`.
- Optional profiles select a `RankingProvider` by ID; missing addons produce a clear startup/configuration error only when selected.
- Lexical startup supplies no embedding provider to repository/index/search constructors.

- [ ] Update failing config tests for lexical default, valid optional provider ID, blank/unknown profile, and selected-but-missing addon.
- [ ] Run targeted config/runtime tests and confirm expected failure before wiring changes.
- [ ] Update the immutable config model and loader with exact validation messages.
- [ ] Refactor `BukkitRuntime.start()` and bootstrap state so lexical mode opens the lexical repository without discovering or constructing embedding providers.
- [ ] Migrate `ContainerIndex` and `SearchService` constructors; remove obsolete embedding arguments from every caller.
- [ ] Run config and runtime lifecycle tests; confirm PASS.

### Task 8: Documentation, behavioral smoke test, and artifact evidence

**Files:**
- Modify: `README.md`
- Modify: `paper/src/main/resources/config.yml`
- Modify tests only where the approved public contract changed.

**Interfaces:**
- Documentation promises metadata-aware lexical search, not unrestricted semantic understanding.
- Remote documentation states reranking improves ordering, not retrieval recall.

- [ ] Document lexical default, searchable fields, quality tradeoff, optional addon installation, and zero-model/zero-network default behavior.
- [ ] Run `./gradlew test`; expect all project tests PASS.
- [ ] Build with `./gradlew :paper:build :ranking:sparse:build :ranking:remote-openai:build`; expect successful artifacts.
- [ ] Start the actual Paper test server in lexical mode, index representative named/enchanted/tagged items, execute `/kitsune` exact, alias, punctuation-heavy, radius-excluded, and protection-denied searches, and record observed results.
- [ ] Inspect the default Paper JAR for absence of OpenAI/HTTP-provider, dense/sparse-vector, and addon service classes.
- [ ] Measure the default JAR and compare it with the current 15 MB baseline; separately report SQLite native-library contribution because artifact size and runtime heap are distinct costs.
