# Embedding Cache and Hopper Coalesce Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Status:** Implemented on `embedding-cache-hopper-coalesce`. Full `./gradlew test` passed on 2026-08-17.

**Goal:** Reuse embeddings by semantic descriptor hash and stop hopper transfers from re-embedding every stack or dropping chests from search.

**Architecture:** Add SHA-256 semantic hashes (descriptor codec with amount forced to 1), a version-2 `embeddings` table, and a worker-side resolver that `embedAll`s only cache misses. Debounce transfer dirties, do not cancel in-flight snapshots, stop content-dirty from clearing search markers, and drop live fingerprint equality.

**Tech Stack:** Java 21, Gradle, SQLite JDBC, JUnit 5, existing `IndexRepository` / `ContainerIndex` / `DirtyRootTracker` / `LiveRootAccess` pipeline.

## File map

- Create: `common/src/main/java/dev/jlo/kitsune/index/SemanticDescriptorHash.java`
- Create: `common/src/main/java/dev/jlo/kitsune/index/CachedEmbeddingResolver.java`
- Create: `common/src/main/resources/db/migration/V2__embeddings_cache.sql`
- Create: `common/src/test/java/dev/jlo/kitsune/index/SemanticDescriptorHashTest.java`
- Create: `common/src/test/java/dev/jlo/kitsune/index/CachedEmbeddingResolverTest.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/DescriptorCodec.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/IndexRepository.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/DirtyRootTracker.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/api` no — `api/src/main/java/dev/jlo/kitsune/api/embedding/EmbeddingProvider.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/embedding/remote/OpenAiCompatibleEmbeddingProvider.java`
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/index/ContainerIndex.java`
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/index/IndexListener.java`
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/BukkitLiveRootAccess.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricWorldAccess.java`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeWorldAccess.java`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeWorldAccess.java`
- Modify tests listed in each task
- Modify: `docs/superpowers/specs/2026-07-31-kitsune-nearby-storage-search-design.md`

## Global constraints

- TDD: failing test first, watch it fail, then minimal production code.
- Do not mix this work into a commit with the uncommitted spatial-search / mutation-policy files unless those hunks are required for the new test to compile.
- Work on a feature branch; do not commit to `master`.
- Atomic commits: one logical unit per commit (behavior + its tests).
- `replaceRoot` stays wipe-and-rewrite for `items`.
- Cache key includes provider id + version and excludes amount.

---

### Task 1: Semantic descriptor hash

**Files:**
- Create: `common/src/main/java/dev/jlo/kitsune/index/SemanticDescriptorHash.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/DescriptorCodec.java`
- Create: `common/src/test/java/dev/jlo/kitsune/index/SemanticDescriptorHashTest.java`
- Test: `common/src/test/java/dev/jlo/kitsune/index/DescriptorCodecTest.java` (only if encodeSemantic needs coverage there)

- [ ] **Step 1: Write the failing hash tests**

```java
package dev.jlo.kitsune.index;

import dev.jlo.kitsune.model.ItemDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemanticDescriptorHashTest {
    @Test
    void amountDoesNotChangeTheHash() {
        assertEquals(hash(stone(1)), hash(stone(64)));
    }

    @Test
    void materialChangeChangesTheHash() {
        assertNotEquals(hash(stone(1)), hash(dirt(1)));
    }

    @Test
    void displayNameChangeChangesTheHash() {
        ItemDescriptor named = ItemDescriptor.builder()
            .materialKey("minecraft:cobblestone")
            .amount(1)
            .addDisplayText("Lucky Cobble")
            .build();
        assertNotEquals(hash(stone(1)), hash(named));
    }

    @Test
    void bytesAreSha256LengthAndStable() {
        SemanticDescriptorHash first = hash(stone(8));
        assertEquals(32, first.bytes().length);
        assertArrayEquals(first.bytes(), hash(stone(8)).bytes());
    }

    @Test
    void rejectsNullDescriptor() {
        assertThrows(NullPointerException.class, () -> SemanticDescriptorHash.of(null));
    }

    private static SemanticDescriptorHash hash(ItemDescriptor descriptor) {
        return SemanticDescriptorHash.of(descriptor);
    }

    private static ItemDescriptor stone(int amount) {
        return ItemDescriptor.builder().materialKey("minecraft:cobblestone").amount(amount).build();
    }

    private static ItemDescriptor dirt(int amount) {
        return ItemDescriptor.builder().materialKey("minecraft:dirt").amount(amount).build();
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails to compile or find `SemanticDescriptorHash`**

Run: `./gradlew :common:test --tests dev.jlo.kitsune.index.SemanticDescriptorHashTest`

Expected: FAIL — cannot find symbol `SemanticDescriptorHash`.

- [ ] **Step 3: Implement hash + amount-forced encode**

Add `DescriptorCodec.encodeSemantic(ItemDescriptor)` that is identical to `encode` except `output.writeInt(1)` instead of `descriptor.amount()`.

```java
public final class SemanticDescriptorHash {
    private final byte[] bytes;

    private SemanticDescriptorHash(byte[] bytes) {
        this.bytes = bytes.clone();
    }

    public static SemanticDescriptorHash of(ItemDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "Descriptor must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return new SemanticDescriptorHash(digest.digest(DescriptorCodec.encodeSemantic(descriptor)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required", ex);
        }
    }

    public static SemanticDescriptorHash ofBytes(byte[] bytes) {
        Objects.requireNonNull(bytes, "Hash bytes must not be null");
        if (bytes.length != 32) {
            throw new IllegalArgumentException("Descriptor hash must be 32 bytes");
        }
        return new SemanticDescriptorHash(bytes);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) { /* Arrays.equals */ }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
```

- [ ] **Step 4: Re-run the hash tests**

Run: `./gradlew :common:test --tests dev.jlo.kitsune.index.SemanticDescriptorHashTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/dev/jlo/kitsune/index/SemanticDescriptorHash.java \
        common/src/main/java/dev/jlo/kitsune/index/DescriptorCodec.java \
        common/src/test/java/dev/jlo/kitsune/index/SemanticDescriptorHashTest.java
git commit -m "Add amount-insensitive semantic descriptor hashes"
```

---

### Task 2: Embeddings table and repository API

**Files:**
- Create: `common/src/main/resources/db/migration/V2__embeddings_cache.sql`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/IndexRepository.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryTest.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/Task4CompileAccessTest.java` (schema_version 1 → 2)
- Modify every `IndexRepository` fake: `IndexWorkerTest`, `SearchServiceTest`, `BukkitRuntimeLifecycleTest`, `ContainerIndexLoadedRootRestorationTest`

`V2__embeddings_cache.sql`:

```sql
CREATE TABLE IF NOT EXISTS embeddings (
    provider_id TEXT NOT NULL,
    provider_version INTEGER NOT NULL,
    descriptor_hash BLOB NOT NULL,
    vector BLOB NOT NULL,
    vector_norm REAL NOT NULL CHECK (vector_norm >= 0.0),
    PRIMARY KEY (provider_id, provider_version, descriptor_hash)
);
```

Repository methods:

```java
Map<SemanticDescriptorHash, Embedding> findEmbeddings(
    EmbeddingProvider provider, Set<SemanticDescriptorHash> hashes) throws SQLException;

void putEmbeddings(
    EmbeddingProvider provider, Map<SemanticDescriptorHash, Embedding> embeddings) throws SQLException;
```

Empty hash set returns empty map. `putEmbeddings` upserts. Lookup ignores rows whose provider id/version do not match the argument. Decoded embeddings must pass the existing `requireProviderIdentity` check.

- [ ] **Step 1: Write failing repository tests**

In `SqliteIndexRepositoryTest`:

```java
@Test
void embeddingsRoundTripBySemanticHashAndIgnoreAmount() throws Exception {
    try (RepositoryTestFixture fixture = new RepositoryTestFixture(tempDir.resolve("cache.db"))) {
        EmbeddingProvider provider = new SparseTagEmbeddingProvider();
        ItemDescriptor one = ItemDescriptor.builder().materialKey("minecraft:cobblestone").amount(1).build();
        ItemDescriptor stack = ItemDescriptor.builder().materialKey("minecraft:cobblestone").amount(64).build();
        SemanticDescriptorHash hash = SemanticDescriptorHash.of(one);
        Embedding embedded = provider.embed(one);

        fixture.repository().putEmbeddings(provider, Map.of(hash, embedded));

        Map<SemanticDescriptorHash, Embedding> found = fixture.repository().findEmbeddings(
            provider, Set.of(SemanticDescriptorHash.of(stack)));
        assertEquals(1, found.size());
        assertArrayEquals(embedded.encode(), found.get(hash).encode());
    }
}

@Test
void embeddingsMissWhenProviderIdentityDiffers() throws Exception {
    // put with sparse-v1, find with a stub provider of id "other" / version 1 → empty
}

@Test
void migrateOpensExistingV1DatabaseAndCreatesEmbeddingsTable() throws Exception {
    // create a V1-only file (run only V1 SQL), open via SqliteIndexRepository.open, assert schema_version=2
    // and SELECT COUNT(*) FROM embeddings succeeds
}
```

Fakes should compile: add the two methods as empty `Map.of()` / no-op.

- [ ] **Step 2: Run the new repository tests**

Run: `./gradlew :common:test --tests dev.jlo.kitsune.index.SqliteIndexRepositoryTest`

Expected: FAIL — `findEmbeddings` / `putEmbeddings` missing, or schema_version stays 1.

- [ ] **Step 3: Implement V2 migrate + find/put**

Change `migrate()`:

- missing table → apply V1 then V2, record version 2
- version 1 → apply V2, set version 2
- version 2 → return
- other → `SQLException("Unsupported schema version")`

`findEmbeddings`: if hashes empty, return empty. Select matching rows for the provider in batches of 200. Key the result with `SemanticDescriptorHash.ofBytes`.

`putEmbeddings`: `INSERT OR REPLACE` in a transaction.

- [ ] **Step 4: Re-run repository + Task4CompileAccessTest**

Run:

```bash
./gradlew :common:test --tests dev.jlo.kitsune.index.SqliteIndexRepositoryTest --tests dev.jlo.kitsune.Task4CompileAccessTest
```

Expected: PASS. Update `Task4CompileAccessTest` expected version to 2 as part of green.

- [ ] **Step 5: Commit**

```bash
git commit -m "Persist embeddings by semantic descriptor hash"
```

---

### Task 3: Unique re-embed and CachedEmbeddingResolver

**Files:**
- Modify: `api/src/main/java/dev/jlo/kitsune/api/embedding/EmbeddingProvider.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/embedding/remote/OpenAiCompatibleEmbeddingProvider.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java` (`reembedAll`)
- Create: `common/src/main/java/dev/jlo/kitsune/index/CachedEmbeddingResolver.java`
- Create: `common/src/test/java/dev/jlo/kitsune/index/CachedEmbeddingResolverTest.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryTest.java`

- [ ] **Step 1: Write failing tests**

`CachedEmbeddingResolverTest`:

```java
@Test
void identicalSemanticStacksEmbedOnce() {
    CountingProvider provider = new CountingProvider();
    MemoryRepository repository = new MemoryRepository();
    CachedEmbeddingResolver resolver = new CachedEmbeddingResolver();
    List<IndexedItem> items = resolver.resolve(
        List.of(draft(stone(1), 0), draft(stone(64), 1)), provider, repository);
    assertEquals(1, provider.embedAllCalls());
    assertEquals(1, provider.descriptorsSeen());
    assertEquals(1, items.get(0).amount());
    assertEquals(64, items.get(1).amount());
}

@Test
void secondResolveReusesPersistedVectors() {
    // first resolve stone, second resolve stone+dirt → embedAll only dirt
}
```

`SqliteIndexRepositoryTest`: after `replaceRoot` of 10 identical-semantic stacks and `reembedAll` with a counting provider, `embed`/`embedAll` is called once, and `findEmbeddings` returns the new vector.

Add default method on `EmbeddingProvider`:

```java
default List<Embedding> embedAll(List<ItemDescriptor> descriptors) {
    Objects.requireNonNull(descriptors, "Descriptors must not be null");
    List<Embedding> embeddings = new ArrayList<>(descriptors.size());
    for (ItemDescriptor descriptor : descriptors) {
        embeddings.add(embed(descriptor));
    }
    return List.copyOf(embeddings);
}
```

Mark `OpenAiCompatibleEmbeddingProvider.embedAll` with `@Override`.

- [ ] **Step 2: Run resolver + reembed tests, expect FAIL**

Run: `./gradlew :common:test --tests dev.jlo.kitsune.index.CachedEmbeddingResolverTest --tests dev.jlo.kitsune.index.SqliteIndexRepositoryTest`

- [ ] **Step 3: Implement resolver and unique reembedAll**

`CachedEmbeddingResolver.resolve`:

1. For each draft, compute hash.
2. `findEmbeddings` for the unique hash set.
3. Unique missing descriptors (first descriptor per missing hash).
4. `embedAll(missing)`, `putEmbeddings`, merge into the map.
5. Return `IndexedItem(path, amount, descriptor, embedding)` in draft order.

`reembedAll`: select distinct semantic hashes (compute from stored descriptors), `embedAll` unique descriptors, update all `items` rows whose semantic hash matches, upsert `embeddings`, `DELETE FROM embeddings WHERE provider_id <> ? OR provider_version <> ?`.

- [ ] **Step 4: Re-run tests, expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "Reuse cached embeddings and re-embed unique descriptors"
```

---

### Task 4: Wire resolver into ContainerIndex

**Files:**
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/index/ContainerIndex.java`
- Modify: `platform/bukkit/src/test/java/dev/jlo/kitsune/index/ContainerIndexLoadedRootRestorationTest.java`
- Modify fakes so `findEmbeddings`/`putEmbeddings` are an in-memory map on the blocking repository used by that test

- [ ] **Step 1: Write failing ContainerIndex test**

```java
@Test
void countOnlyChangeReusesCachedEmbeddings() throws Exception {
    try (Fixture fixture = new Fixture()) {
        fixture.finishInitialIndexing();
        int afterFirst = fixture.embeddingProvider.embedCalls();
        fixture.changeAmountKeepDescriptor();
        fixture.index.markDirty(fixture.root);
        fixture.tick(3L);
        fixture.awaitWorker();
        fixture.tick(4L);
        assertEquals(afterFirst, fixture.embeddingProvider.embedCalls());
        assertEquals(2, fixture.repository.replacedRoots().size());
    }
}
```

The fixture’s first snapshot embeds once and `putEmbeddings` stores it. The second snapshot has a different fingerprint (amount changed) but the same semantic hash.

Until `submitReplacement` uses `CachedEmbeddingResolver`, embed calls increase.

- [ ] **Step 2: Run the Bukkit index test, expect FAIL**

Run: `./gradlew :platform:bukkit:test --tests dev.jlo.kitsune.index.ContainerIndexLoadedRootRestorationTest.countOnlyChangeReusesCachedEmbeddings`

- [ ] **Step 3: Replace the embed loop with `CachedEmbeddingResolver`**

In `submitReplacement`, after the fingerprint skip, call the resolver instead of `embeddingProvider.embed` per leaf.

- [ ] **Step 4: Re-run the Bukkit index test class, expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "Skip embedding on hopper-style count-only reindexes"
```

---

### Task 5: Transfer debounce and in-flight coalesce

**Files:**
- Modify: `common/src/main/java/dev/jlo/kitsune/index/DirtyRootTracker.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/index/DirtyRootTrackerTest.java`
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/index/ContainerIndex.java`
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/index/IndexListener.java`
- Modify: `platform/bukkit/src/test/java/dev/jlo/kitsune/index/IndexListenerMutationPolicyTest.java`

Constants: `SNAPSHOT_DELAY=1`, `TRANSFER_DELAY=5`, `TRANSFER_MAX_LATENCY=20`.

```java
public void markDirty(BlockKey key, long eventTick) { /* delay 1, no max */ }
public void markTransferDirty(BlockKey key, long eventTick) { /* delay 5, max 20 */ }
public boolean isPending(BlockKey key) { return current.containsKey(key); }
```

Rules:

- Same-tick `markDirty` still coalesces; due tick is `eventTick + 1` (keep existing click test).
- `markTransferDirty` at T=100 is due at 105. Another transfer at 101 is due at 106. Transfers from T=100 through T=119 are due no later than 120.
- If the current work is claimed, a later `markDirty` / `markTransferDirty` does **not** change `current` revision; it sets `followUp`. `complete` then schedules `SNAPSHOT` at `completeTick + 1` when follow-up is set.
- `markDeleted` still replaces current work, including claimed, and clears follow-up.

- [ ] **Step 1: Write failing tracker tests for transfer delay, max latency, and in-flight follow-up**

Keep `sameTickDirtyEventsCoalesceToOneNextTickSnapshotWithRevisionAdvancingToTen` passing.

New tests:

```java
@Test
void transferDirtyIsDueFiveTicksLaterAndExtendsWithEachMove() { ... }

@Test
void transferDirtyDoesNotWaitLongerThanTwentyTicksFromFirstUnclaimedDirty() { ... }

@Test
void dirtyDuringClaimedSnapshotSchedulesFollowUpWithoutStalingInFlightRevision() {
    tracker.markDirty(root, 1L);
    PendingRoot inFlight = assertSingleDue(tracker, 2L);
    tracker.markDirty(root, 3L);
    assertTrue(tracker.claimDue(4L).isEmpty());
    assertTrue(tracker.isCurrent(inFlight));
    assertTrue(tracker.complete(inFlight));
    PendingRoot followUp = assertSingleDue(tracker, 4L); // complete tick + 1; pass complete's tick
}
```

`complete` currently does not take a tick. Change to `complete(PendingRoot work, long currentTick)` **or** schedule follow-up at the existing `due` using a stored complete-side tick. Prefer `complete(work, currentTick)` and update existing complete call sites (`ContainerIndex.handleRootCompletion` has `tick`). Update existing tracker tests to pass the completion tick.

- [ ] **Step 2: Run DirtyRootTrackerTest, expect new tests FAIL**

- [ ] **Step 3: Implement tracker + listener transfer path**

`IndexListener.onInventoryMoveItem` / `onInventoryPickupItem` call `index.markTransferDirty(...)`.
`ContainerIndex.markTransferDirty` does **not** call `rootInvalidated`. Regular `markDirty` still does in this task; Task 6 removes that.

- [ ] **Step 4: Re-run tracker + listener tests, expect PASS**

- [ ] **Step 5: Commit**

```bash
git commit -m "Coalesce hopper dirties without cancelling in-flight snapshots"
```

---

### Task 6: Search stays up during content mutation

**Files:**
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/index/ContainerIndex.java` (`markDirty` must not call `rootInvalidated`)
- Modify: `platform/bukkit/src/test/java/dev/jlo/kitsune/index/ContainerIndexLoadedRootRestorationTest.java`
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/BukkitLiveRootAccess.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricWorldAccess.java`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeWorldAccess.java`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeWorldAccess.java`
- Add or modify live-access tests if they exist; otherwise add a focused Bukkit test that `validate` returns non-null when fingerprint bytes differ but block type matches
- Modify: `docs/superpowers/specs/2026-07-31-kitsune-nearby-storage-search-design.md`

- [ ] **Step 1: Rewrite the existing invalidation test and add fingerprint-mismatch live-access test**

Change `dirtyRootInvalidatesItsPublishedMarkersImmediately` to:

```java
@Test
void dirtyRootDoesNotInvalidatePublishedMarkers() throws Exception {
    try (Fixture fixture = new Fixture()) {
        fixture.finishInitialIndexing();
        fixture.index.markDirty(fixture.root);
        assertEquals(List.of(), fixture.invalidatedRoots);
    }
}
```

Keep delete/unload invalidation tests.

For live access: construct `BukkitLiveRootAccess` with a snapshotter double that returns a complete draft whose fingerprint is not the identity fingerprint, same key and block type; `validate` must return non-null (protection allows). If Bukkit doubles are too heavy, extract the fingerprint comparison into a package-visible helper on a common type and unit-test that. Prefer changing the four `validate` methods to delete the fingerprint equality line and add one common test if a common helper is introduced.

Do **not** introduce a helper unless a test cannot be written against an existing type. Deleting the four equality checks is the production change; cover it with at least one platform test.

- [ ] **Step 2: Run the rewritten tests, expect FAIL (dirty still invalidates; live access still rejects)**

- [ ] **Step 3: Remove `rootInvalidated` from `markDirty` / `markTransferDirty`. Remove live fingerprint equality on all four platforms.**

Update the nearby-storage design freshness + live-validation paragraphs to match this spec.

- [ ] **Step 4: Re-run Bukkit + fabric/forge compile tests as available**

```bash
./gradlew :common:test :platform:bukkit:test :fabric:test :forge:test :neoforge:test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "Keep search results live during hopper transfers"
```

---

### Task 7: Spec sync and verification

**Files:**
- Modify: `docs/superpowers/specs/2026-07-31-kitsune-nearby-storage-search-design.md` if any paragraph was missed
- This plan: check off completed tasks

Freshness model must state:

- Transfer/pickup dirties debounce 5 ticks, max 20, and do not cancel in-flight snapshots.
- Content dirty does not clear markers.
- Live validation does not require fingerprint equality.
- Embeddings are reused by semantic hash in table `embeddings`.
- `items` replacement is still atomic wipe-and-rewrite.

- [ ] **Step 1: Grep the design spec for leftover contradictions** (`fingerprint` + `must` + `invalidat`)

- [ ] **Step 2: Run the full test suite**

```bash
./gradlew test
```

Expected: PASS.

- [ ] **Step 3: Commit remaining spec-only edits if any**

```bash
git commit -m "Document embedding cache and hopper freshness rules"
```

---

## Self-review

1. Spec coverage: semantic key, persistent table, resolver, unique reembed, fingerprint skip kept, transfer debounce, in-flight follow-up, no marker invalidation on dirty, live fingerprint dropped — each has a task.
2. No TBD/placeholder steps.
3. Type names: `SemanticDescriptorHash`, `CachedEmbeddingResolver`, `markTransferDirty`, `findEmbeddings`, `putEmbeddings` are consistent across tasks.
