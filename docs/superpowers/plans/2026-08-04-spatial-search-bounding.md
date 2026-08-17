# Spatial Search Bounding and Indexing Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bound spatial-search candidate and validation work without changing search correctness, and skip expensive replacement work when a persisted root snapshot is unchanged.

**Architecture:** Replace the all-at-once candidate lookup with a keyset-paged repository API ordered by the existing deterministic `(world_uuid, x, y, z)` order. `SearchService` validates and loads one page at a time, but keeps a bounded global top-results heap/list plus exact total counts so later pages cannot change the contract. The SQLite query explicitly uses the existing `(world_uuid, chunk_x, chunk_z)` index and receives an `EXPLAIN QUERY PLAN` regression test. Before embedding a completed live snapshot, `ContainerIndex` reads the stored root identity and skips embedding and replacement when root coordinate, block type, and fingerprint are unchanged.

**Tech Stack:** Java 21, Gradle, SQLite JDBC, JUnit 5, existing `IndexRepository`/`IndexWorker`/`SearchService` pipeline, Bukkit test doubles.

## Global Constraints

- Preserve the existing deterministic candidate order `ORDER BY c.world_uuid, c.x, c.y, c.z`; keyset cursors must use that same order and must not use offset pagination.
- Preserve exact live access validation, exact cosine ranking, `maxResults`, `maxPathsPerRoot`, `totalAccessibleMatchingRoots`, and `totalMatchingStacks` semantics across all candidate pages.
- Never load chunks or access Bukkit objects from the worker thread; each live-validation batch remains on `ServerThreadBridge` and each SQLite/vector operation remains on `IndexWorker`.
- Bound one candidate page and one document-load/rank batch to a fixed internal page size of 128 roots; do not add configuration for this implementation.
- Preserve the existing uncommitted Bukkit mutation-policy changes in `IndexListener.java`, its test, and the related design specification.
- Do not add an R-tree or a new migration: the nearby query remains chunk-grid based, and the existing indexed schema is sufficient for the bounded radius contract.
- Use test-first changes: write each new behavioral test, run it red, then implement the smallest production change and run it green.
- Do not commit unrelated existing work; leave the checkout’s pre-existing modifications intact.

---

### Task 1: Add the paged candidate repository contract

**Files:**
- Modify: `common/src/main/java/dev/jlo/kitsune/index/IndexRepository.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryTest.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/index/IndexWorkerTest.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/search/SearchServiceTest.java`
- Modify: `platform/bukkit/src/test/java/dev/jlo/kitsune/bukkit/BukkitRuntimeLifecycleTest.java`
- Modify: `platform/bukkit/src/test/java/dev/jlo/kitsune/index/ContainerIndexLoadedRootRestorationTest.java`

**Interfaces:**
- Produces the following `IndexRepository` nested records and methods for later tasks:

```java
record CandidateCursor(int x, int y, int z) {}

record CandidatePage(List<RootIdentity> roots, CandidateCursor next) {
    CandidatePage {
        roots = List.copyOf(roots);
    }
}

CandidatePage findCandidates(
    UUID worldId,
    int minChunkX,
    int maxChunkX,
    int minChunkZ,
    int maxChunkZ,
    CandidateCursor after,
    int limit
) throws SQLException;
```

- Remove the old unpaged `findCandidates` method rather than retaining an alias.

- [ ] **Step 1: Write failing SQLite paging tests**

Add tests to `SqliteIndexRepositoryTest` that insert roots in several coordinates and assert:

```java
IndexRepository.CandidatePage first = fixture.repository().findCandidates(
    worldId, -10, 10, -10, 10, null, 2);
IndexRepository.CandidatePage second = fixture.repository().findCandidates(
    worldId, -10, 10, -10, 10, first.next(), 2);

assertEquals(List.of(firstRoot, secondRoot), first.roots());
assertEquals(List.of(thirdRoot), second.roots());
assertEquals(null, second.next());
assertEquals(List.of(firstRoot, secondRoot, thirdRoot),
    Stream.concat(first.roots().stream(), second.roots().stream()).toList());
```

Also add a test that an unavailable chunk is absent from every page, a non-positive limit is rejected with `IllegalArgumentException`, and a page query plan mentions `containers_by_chunk`. Run only the new repository tests:

```bash
./gradlew :common:test --tests dev.jlo.kitsune.index.SqliteIndexRepositoryTest
```

Expected result before implementation: compilation failure because the new records/method do not exist.

- [ ] **Step 2: Implement the API and SQLite keyset query**

In `IndexRepository`, add the records and replace the old method with the signature above. In `SqliteIndexRepository`, use one SQL shape for both first and subsequent pages:

```sql
SELECT c.world_uuid, c.x, c.y, c.z, c.block_type, c.fingerprint, c.revision
FROM chunks ch
JOIN containers c INDEXED BY containers_by_chunk
  ON ch.world_uuid = c.world_uuid
 AND ch.chunk_x = c.chunk_x
 AND ch.chunk_z = c.chunk_z
WHERE ch.available = 1
  AND c.world_uuid = ?
  AND c.chunk_x BETWEEN ? AND ?
  AND c.chunk_z BETWEEN ? AND ?
  -- when after is non-null:
  AND (
       c.x > ?
       OR (c.x = ? AND c.y > ?)
       OR (c.x = ? AND c.y = ? AND c.z > ?)
  )
ORDER BY c.world_uuid, c.x, c.y, c.z
LIMIT ?
```

Bind the cursor predicate only when `after` is non-null. Fetch `limit + 1` rows; return at most `limit` roots and set `next` to the last returned root only when the extra row exists. Keep the returned list immutable and reject `limit <= 0`. Use `EXPLAIN QUERY PLAN` in the test against the same package-private SQL builder (or an equivalent exact query constant) and assert that the plan uses `containers_by_chunk` and the chunks primary-key lookup instead of the world-only container unique index.

Update every fake `IndexRepository` implementation to return `CandidatePage` values and return `null` for the new root lookup until Task 3 adds that contract. Update existing callers and tests to pass `null` and an explicit page limit where they directly query candidates.

- [ ] **Step 3: Run the repository and interface tests green**

Run:

```bash
./gradlew :common:test --tests dev.jlo.kitsune.index.SqliteIndexRepositoryTest --tests dev.jlo.kitsune.index.IndexWorkerTest
./gradlew :platform:bukkit:test --tests dev.jlo.kitsune.bukkit.BukkitRuntimeLifecycleTest --tests dev.jlo.kitsune.index.ContainerIndexLoadedRootRestorationTest
```

Expected result: all selected tests pass, including candidate ordering, no skips/duplicates across cursors, unavailable-chunk filtering, and query-plan assertions.

---

### Task 2: Page search validation while preserving global ranking

**Files:**
- Modify: `common/src/main/java/dev/jlo/kitsune/search/SearchService.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/search/SearchServiceTest.java`

**Interfaces:**
- Consumes `IndexRepository.CandidatePage`, `IndexRepository.CandidateCursor`, and the existing `SearchPolicy`.
- Keeps `CANDIDATE_PAGE_SIZE = 128` private to `SearchService`.
- Refactors internal ranking to return a page aggregate:

```java
private record RankedPage(
    List<RootMatch> roots,
    int totalMatchingRoots,
    int totalMatchingStacks
) {}
```

- `RankAccumulator` owns the exact global counts and at most `policy.maxResults()` roots, sorted with the existing `ROOT_COMPARATOR`. Its `finish()` returns `NO_MATCHES` only when global matching-root count is zero.

- [ ] **Step 1: Add a failing cross-page ranking test**

Add a `SearchServiceTest` case with more than 128 candidates, all accessible, where the best-scoring root is in the second page and at least one matching root exists in both pages. Assert:

```java
assertEquals(SearchOutcome.Status.SUCCESS, outcome.status());
assertEquals(129, outcome.totalAccessibleMatchingRoots());
assertEquals(129, outcome.totalMatchingStacks());
assertEquals(lateBest.identity().key(), outcome.roots().getFirst().key());
assertEquals(2, harness.findCandidateCalls());
assertEquals(2, harness.loadDocumentCalls());
```

Use the existing deterministic fake embedding provider and generate roots with distinct x coordinates; keep the policy’s `maxResults` small enough to verify trimming happens after global merge. Add an assertion that validation never receives more than 128 candidates in one server-thread batch by recording page batch sizes in `FakeLiveRootAccess`. Run:

```bash
./gradlew :common:test --tests dev.jlo.kitsune.search.SearchServiceTest
```

Expected result before implementation: compilation failure from the new page API, or a failing assertion because the existing pipeline calls one unbounded candidate lookup.

- [ ] **Step 2: Implement the paged asynchronous pipeline**

Replace the single `findCandidates`/`validateCandidates`/`loadAndRank` chain with a recursive page stage that:

1. submits one `CandidatePage` query on `IndexWorker`;
2. validates only that page through `ServerThreadBridge`;
3. loads only allowed roots from that page and creates a `RankedPage` on `IndexWorker`;
4. adds the page counts and roots to `RankAccumulator`;
5. follows `CandidatePage.next()` until null, checking `requireCurrent` at every transition;
6. returns `accumulator.finish()` only after the final page.

Do not stop after the accumulator reaches `maxResults`: later pages can contain a higher score, and all matching counts must remain exact. Empty or fully denied pages must continue when `next()` is non-null. Keep `rankDocuments`’ existing score validation, per-root path cap, item ordering, root ordering, and no-match behavior, moving only the final result assembly into `RankAccumulator`.

Implement the fake repository page method by sorting identities with the same `(world, x, y, z)` comparator and returning the first slice strictly after the supplied cursor. Record each validation batch size in `FakeLiveRootAccess`.

- [ ] **Step 3: Run all search-service tests green**

Run:

```bash
./gradlew :common:test --tests dev.jlo.kitsune.search.SearchServiceTest
```

Expected result: all existing access-filtering, cancellation, warmup, score, count, and ranking tests pass, plus the cross-page test proves global top results and totals.

---

### Task 3: Skip unchanged persisted root replacements

**Files:**
- Modify: `common/src/main/java/dev/jlo/kitsune/index/IndexRepository.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java`
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/index/ContainerIndex.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryTest.java`
- Modify: `platform/bukkit/src/test/java/dev/jlo/kitsune/index/ContainerIndexLoadedRootRestorationTest.java`
- Modify: all `IndexRepository` fakes listed in Task 1

**Interfaces:**
- Add this persisted identity lookup to `IndexRepository`:

```java
Optional<RootIdentity> findRoot(BlockKey key) throws SQLException;
```

- `SqliteIndexRepository.findRoot` selects the root’s coordinate, block type, fingerprint, and revision from `containers`, returning `Optional.empty()` when absent.
- `ContainerIndex.submitReplacement` uses the lookup before embedding. It skips work only when all of these are true:
  - `rootWork.key().equals(draft.key())`;
  - stored root coordinate equals `draft.key()`;
  - stored block type equals `draft.blockType()`;
  - stored fingerprint equals `draft.fingerprint()` by `Arrays.equals`.
- A canonical-root change never takes this shortcut because the obsolete requested coordinate still must be deleted.

- [ ] **Step 1: Write failing persisted-identity and no-op tests**

In `SqliteIndexRepositoryTest`, after replacing a root, assert `findRoot(root)` returns its block type, fingerprint, and revision, and assert a missing coordinate returns empty.

In `ContainerIndexLoadedRootRestorationTest`, make `BlockingRepository` retain the last `RootIdentity` for each replacement and count embedding calls through a small delegating `EmbeddingProvider`. Add this scenario:

```java
fixture.finishInitialIndexing();
int initialEmbeddings = fixture.embeddingProvider.embedCalls();
fixture.index.markDirty(fixture.root);
fixture.tick(3L);
fixture.awaitWorker();
fixture.tick(4L);

assertEquals(List.of(fixture.root), fixture.repository.replacedRoots());
assertEquals(initialEmbeddings, fixture.embeddingProvider.embedCalls());
assertEquals(Set.of(fixture.root), fixture.index.loadedRoots());
```

Run the two focused test classes. Expected result before implementation: compilation failure for `findRoot`, or a second replacement/embedding call.

- [ ] **Step 2: Implement the lookup and no-op branch**

Add `findRoot` to the interface and all fakes. Implement the SQLite `SELECT` with the same coordinate predicate used by replacement. In `ContainerIndex.submitReplacement`, run `repository.findRoot(draft.key())` inside the existing worker task before allocating `IndexedItem` objects. Return normally when the persisted identity matches; the existing `whenComplete` path must still enqueue `RootCompletion(rootWork, draft.key(), null)` so tracker completion and loaded-root restoration remain unchanged. If the lookup fails, let the existing failure/retry path handle it.

Use `Arrays.equals` for fingerprints and do not compare revision values; a new revision is intentionally not a content change.

- [ ] **Step 3: Run indexing tests green**

Run:

```bash
./gradlew :common:test --tests dev.jlo.kitsune.index.SqliteIndexRepositoryTest
./gradlew :platform:bukkit:test --tests dev.jlo.kitsune.index.ContainerIndexLoadedRootRestorationTest
```

Expected result: persisted identity lookup, replacement behavior, canonical deletion, unavailable snapshots, loaded-root restoration, and the new no-op test all pass.

---

### Task 4: Document the bounded search and replacement behavior

**Files:**
- Modify: `docs/superpowers/specs/2026-07-31-kitsune-nearby-storage-search-design.md`

**Interfaces:**
- Documentation only; it must describe the implemented behavior without adding configuration or promising an R-tree.

- [ ] **Step 1: Update the SQLite/search sections**

Extend the SQLite model text after the chunk index description to state that candidate roots are read in deterministic coordinate order through fixed-size keyset pages, and that each page is live-validated and document-loaded separately while final top results and total counts are accumulated globally. State that a completed snapshot whose root coordinate, block type, and fingerprint match the persisted identity skips embedding and the replacement transaction.

- [ ] **Step 2: Check documentation against code**

Read the edited paragraphs and confirm every named value and ordering rule matches `IndexRepository`, `SqliteIndexRepository`, `SearchService`, and `ContainerIndex`; do not describe unimplemented R-tree support or a tunable page setting.

---

### Task 5: Full verification and review

**Files:**
- No new files. Inspect all modified Java, test, and documentation files, preserving unrelated existing changes.

- [ ] **Step 1: Run focused regression coverage**

```bash
./gradlew :common:test --tests dev.jlo.kitsune.index.SqliteIndexRepositoryTest --tests dev.jlo.kitsune.search.SearchServiceTest --tests dev.jlo.kitsune.index.IndexWorkerTest
./gradlew :platform:bukkit:test --tests dev.jlo.kitsune.index.ContainerIndexLoadedRootRestorationTest --tests dev.jlo.kitsune.bukkit.BukkitRuntimeLifecycleTest
```

Expected result: all selected tests pass.

- [ ] **Step 2: Run the complete project check**

```bash
./gradlew clean check --console=plain
```

Expected result: Gradle exits with code 0; all configured test and verification tasks pass, with only existing `NO-SOURCE` notices where modules have no tests.

- [ ] **Step 3: Review the final diff and request code review**

Inspect the final diff for accidental edits, stale old `findCandidates` callsites, unbounded page collections, incorrect cursor comparisons, per-page result truncation, and no-op branches that could skip canonical deletion. Request a read-only code review after tests pass, then address any evidence-backed findings and rerun the affected tests.
