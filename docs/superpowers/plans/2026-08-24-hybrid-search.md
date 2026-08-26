# Hybrid Full-Text + Semantic Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/kitsune` always-on hybrid search: SQLite FTS5 plus the configured embedding cosine, fused with Reciprocal Rank Fusion.

**Architecture:** Project item metadata into an FTS5 content table keyed by `items.id`. Parse player text into a quoted MATCH expression. Run FTS and cosine independently over the existing nearby-root universe, fuse at `(BlockKey, ItemPath)`, and keep live/protection/radius gates. All new engine code lives in `kitsune-common` (search/index/config) and `kitsune-paper` (YAML + ConfigLoader). Do not touch the removed `api/`, `common/`, or `bukkit/` legacy module trees; all code must target `kitsune-api`, `kitsune-common`, or `kitsune-paper`.

**Tech Stack:** Java 25, Gradle, SQLite FTS5 (`unicode61`), JUnit 5, existing `FeatureVocabulary` + `SearchService`.

**Spec:** `docs/superpowers/specs/2026-08-24-hybrid-search-design.md`

## Global Constraints

- Always-on FTS5 + configured embedding provider; no retriever toggles; no phonetic index.
- Player text is tokenized and quoted; raw text is never SQLite `MATCH` syntax.
- `minimum-score` filters the semantic list only, before fusion.
- Display score is `min(1.0, rrf * (k + 1) / 2)` so `ItemMatch` stays in `[0, 1]`.
- FTS I/O runs on `IndexWorker`, never the main thread.
- Schema V3 keeps vectors; V2 DBs backfill FTS from `items.descriptor`.
- Protection, radius, live-root, commands, markers unchanged.
- Paths: `kitsune-api`, `kitsune-common`, `kitsune-paper` only. Google Java style, 2-space indent.
- Tests: `./gradlew :kitsune-api:test :kitsune-common:test :kitsune-paper:test` must PASS before the last task is done.

---

### Task 1: MATCH query builder [FR-002, FR-003, NFR-003]

**Files:**
- Create: `kitsune-common/src/main/java/dev/jlo/kitsune/search/FullTextQuery.java`
- Test: `kitsune-common/src/test/java/dev/jlo/kitsune/search/FullTextQueryTest.java`

**Interfaces:**
- Produces `FullTextQuery.parse(String raw)` → `FullTextQuery`.
- `boolean isEmpty()` is true when `FeatureVocabulary.tokenize(raw)` is empty.
- `String matchExpression()` is the bound MATCH string; empty query uses `""`.
- Consumes: `FeatureVocabulary.tokenize`, `FeatureVocabulary.aliasesFor`.

- [ ] **Step 1: Write the failing test**

```java
package dev.jlo.kitsune.search;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FullTextQueryTest {
  @Test
  void emptyAndPunctuationAreEmpty() {
    assertTrue(FullTextQuery.parse("").isEmpty());
    assertTrue(FullTextQuery.parse("   ").isEmpty());
    assertTrue(FullTextQuery.parse("!!!").isEmpty());
    assertEquals("", FullTextQuery.parse("!!!").matchExpression());
  }

  @Test
  void quotesOperatorsAndWildcardsBecomeLiteralsOrAreDropped() {
    FullTextQuery query = FullTextQuery.parse("oak AND planks OR \"foo\"* (bar)");
    assertFalse(query.isEmpty());
    String match = query.matchExpression();
    assertFalse(match.contains(" AND AND "));
    assertTrue(match.contains("(\"oak\")"));
    assertTrue(match.contains("AND"));
    assertTrue(match.startsWith("("));
  }

  @Test
  void lastTokenIsPrefixAndAliasesStayInsideTheTokenGroup() {
    FullTextQuery query = FullTextQuery.parse("oak log");
    assertEquals("(\"oak\") AND (\"log\"* OR \"logs\")", query.matchExpression());
  }

  @Test
  void foodExpandsAliasInsideTheOnlyGroup() {
    assertEquals("(\"food\"* OR \"edible\")", FullTextQuery.parse("food").matchExpression());
  }

  @Test
  void parseIsDeterministic() {
    assertEquals(
        FullTextQuery.parse("Oak Plank").matchExpression(),
        FullTextQuery.parse("oak plank").matchExpression());
  }
}
```

Use aliases that already exist in `FeatureVocabulary` (`log` ↔ `logs`, `food` → `edible`). Do not add vocabulary just to satisfy MATCH tests.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.search.FullTextQueryTest`
Expected: FAIL compile (`FullTextQuery` does not exist) or FAIL assertions.

- [ ] **Step 3: Implement `FullTextQuery`**

```java
package dev.jlo.kitsune.search;

import dev.jlo.kitsune.embedding.FeatureVocabulary;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FullTextQuery {
  private final String matchExpression;

  private FullTextQuery(String matchExpression) {
    this.matchExpression = matchExpression;
  }

  public static FullTextQuery parse(String raw) {
    List<String> tokens = FeatureVocabulary.tokenize(raw);
    if (tokens.isEmpty()) {
      return new FullTextQuery("");
    }
    List<String> groups = new ArrayList<>();
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      boolean prefix = i == tokens.size() - 1;
      StringBuilder group = new StringBuilder("(");
      group.append(quote(token));
      if (prefix) {
        group.append('*');
      }
      for (String alias : FeatureVocabulary.aliasesFor(token)) {
        group.append(" OR ").append(quote(alias));
      }
      group.append(')');
      groups.add(group.toString());
    }
    return new FullTextQuery(String.join(" AND ", groups));
  }

  public boolean isEmpty() {
    return matchExpression.isEmpty();
  }

  public String matchExpression() {
    return matchExpression;
  }

  static String quote(String token) {
    return "\"" + token.replace("\"", "\"\"") + "\"";
  }
}
```

FTS5 prefix form is `"token"*` (star **outside** the quotes).

- [ ] **Step 4: Run the test and confirm PASS**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.search.FullTextQueryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add kitsune-common/src/main/java/dev/jlo/kitsune/search/FullTextQuery.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/search/FullTextQueryTest.java \
  kitsune-common/src/main/java/dev/jlo/kitsune/embedding/FeatureVocabulary.java
git commit -m "feat: parse player queries into quoted FTS5 MATCH expressions"
```

---

### Task 2: FTS document projector [FR-007]

**Files:**
- Create: `kitsune-common/src/main/java/dev/jlo/kitsune/index/ItemSearchDocument.java`
- Create: `kitsune-common/src/main/java/dev/jlo/kitsune/index/ItemSearchProjector.java`
- Test: `kitsune-common/src/test/java/dev/jlo/kitsune/index/ItemSearchProjectorTest.java`

**Interfaces:**
- Produces `ItemSearchDocument(String material, String display, String tags, String enchantments, String lore)`.
- Produces `ItemSearchProjector.project(ItemDescriptor): ItemSearchDocument`.
- Each field is space-joined unique lowercase tokens from `FeatureVocabulary.tokenize`.
- `tags` includes `customTags`, `traits`, and `FeatureVocabulary.aliasesFor` of **all** tokens that appear in any field.

- [ ] **Step 1: Write failing projector tests**

Use `ItemDescriptor.builder().materialKey("minecraft:oak_planks").amount(1).addDisplayText("Oak Planks").addLore("A wooden board").addEnchantment("minecraft:mending", 1).addTrait("building").addCustomTag("planks").build()`.

Assert:
- `material` contains `minecraft` and `oak` and `planks` (split on non-alphanumeric).
- `display` contains `oak` and `planks`.
- `lore` contains `wooden` and `board`.
- `enchantments` contains `minecraft` and `mending`.
- `tags` contains `building`, `planks`, and alias tokens such as `edible` only if a source token aliases to it (do not assert aliases that vocabulary does not define).
- Two `project` calls on equal descriptors return equal documents.

- [ ] **Step 2: Run tests; expect missing types**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.index.ItemSearchProjectorTest`
Expected: FAIL compile.

- [ ] **Step 3: Implement projector**

```java
public final class ItemSearchProjector {
  private ItemSearchProjector() {}

  public static ItemSearchDocument project(ItemDescriptor descriptor) {
    LinkedHashSet<String> material = tokens(descriptor.materialKey());
    LinkedHashSet<String> display = new LinkedHashSet<>();
    descriptor.displayText().forEach(text -> display.addAll(tokens(text)));
    LinkedHashSet<String> lore = new LinkedHashSet<>();
    descriptor.lore().forEach(text -> lore.addAll(tokens(text)));
    LinkedHashSet<String> enchantments = new LinkedHashSet<>();
    descriptor.enchantments().keySet().forEach(key -> enchantments.addAll(tokens(key)));
    descriptor.attributes().keySet().forEach(key -> enchantments.addAll(tokens(key)));
    LinkedHashSet<String> tags = new LinkedHashSet<>();
    descriptor.customTags().forEach(tag -> tags.addAll(tokens(tag)));
    descriptor.traits().forEach(trait -> tags.addAll(tokens(trait)));
    LinkedHashSet<String> all = new LinkedHashSet<>();
    all.addAll(material);
    all.addAll(display);
    all.addAll(lore);
    all.addAll(enchantments);
    all.addAll(tags);
    for (String token : List.copyOf(all)) {
      tags.addAll(FeatureVocabulary.aliasesFor(token));
    }
    return new ItemSearchDocument(
        join(material), join(display), join(tags), join(enchantments), join(lore));
  }

  private static LinkedHashSet<String> tokens(String text) {
    return new LinkedHashSet<>(FeatureVocabulary.tokenize(text));
  }

  private static String join(Set<String> tokens) {
    return String.join(" ", tokens);
  }
}
```

`ItemSearchDocument` is a public record with five `String` components; none null (use `""` if a set is empty).

- [ ] **Step 4: Run projector tests PASS**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.index.ItemSearchProjectorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add kitsune-common/src/main/java/dev/jlo/kitsune/index/ItemSearchDocument.java \
  kitsune-common/src/main/java/dev/jlo/kitsune/index/ItemSearchProjector.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/index/ItemSearchProjectorTest.java
git commit -m "feat: project item descriptors into FTS5 document fields"
```

---

### Task 3: RRF display scores [FR-005]

**Files:**
- Create: `kitsune-common/src/main/java/dev/jlo/kitsune/search/ReciprocalRankFusion.java`
- Test: `kitsune-common/src/test/java/dev/jlo/kitsune/search/ReciprocalRankFusionTest.java`

**Interfaces:**
- Produces `ReciprocalRankFusion.displayScore(int k, Integer fullTextRank, Integer semanticRank)`.
- Ranks are 1-based. `null` means the retriever omitted the item.
- `k < 1` or rank `< 1` throws `IllegalArgumentException`.
- Formula: `rrf = Σ 1/(k+rank)` then `min(1.0, rrf * (k + 1) / 2.0)`.

- [ ] **Step 1: Write failing tests**

```java
assertEquals(1.0, ReciprocalRankFusion.displayScore(60, 1, 1), 1e-9);
assertEquals(0.5, ReciprocalRankFusion.displayScore(60, 1, null), 1e-9);
assertEquals(0.5, ReciprocalRankFusion.displayScore(60, null, 1), 1e-9);
assertTrue(ReciprocalRankFusion.displayScore(60, 1, 2)
    > ReciprocalRankFusion.displayScore(60, 1, null));
assertTrue(ReciprocalRankFusion.displayScore(60, 1, 1) <= 1.0);
assertThrows(IllegalArgumentException.class,
    () -> ReciprocalRankFusion.displayScore(0, 1, 1));
assertThrows(IllegalArgumentException.class,
    () -> ReciprocalRankFusion.displayScore(60, 0, 1));
assertEquals(0.0, ReciprocalRankFusion.displayScore(60, null, null), 1e-9);
```

- [ ] **Step 2: Run; expect missing type**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.search.ReciprocalRankFusionTest`
Expected: FAIL compile.

- [ ] **Step 3: Implement**

```java
public final class ReciprocalRankFusion {
  private ReciprocalRankFusion() {}

  public static double displayScore(int k, Integer fullTextRank, Integer semanticRank) {
    if (k < 1) {
      throw new IllegalArgumentException("RRF k must be at least 1");
    }
    double rrf = 0.0;
    rrf += contribution(k, fullTextRank);
    rrf += contribution(k, semanticRank);
    return Math.min(1.0, rrf * (k + 1) / 2.0);
  }

  private static double contribution(int k, Integer rank) {
    if (rank == null) {
      return 0.0;
    }
    if (rank < 1) {
      throw new IllegalArgumentException("Rank must be at least 1");
    }
    return 1.0 / (k + rank);
  }
}
```

- [ ] **Step 4: Tests PASS**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.search.ReciprocalRankFusionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add kitsune-common/src/main/java/dev/jlo/kitsune/search/ReciprocalRankFusion.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/search/ReciprocalRankFusionTest.java
git commit -m "feat: normalize reciprocal rank fusion into unit interval scores"
```

---

### Task 4: Hybrid config knobs [FR-009]

**Files:**
- Modify: `kitsune-common/src/main/java/dev/jlo/kitsune/config/KitsuneConfig.java`
- Modify: `kitsune-common/src/main/java/dev/jlo/kitsune/search/SearchPolicy.java`
- Modify: `kitsune-common/src/test/java/dev/jlo/kitsune/config/KitsuneConfigTest.java`
- Modify: `kitsune-paper/src/main/java/dev/jlo/kitsune/config/ConfigLoader.java`
- Modify: `kitsune-paper/src/main/resources/config.yml`
- Modify: `kitsune-paper/src/test/java/dev/jlo/kitsune/config/ConfigLoaderTest.java`
- Modify: `kitsune-paper/src/main/java/dev/jlo/kitsune/bukkit/BukkitRuntime.java` (SearchPolicy construction only)
- Modify: every `KitsuneConfig.validated(...)` and `new SearchPolicy(...)` call site in `kitsune-common` and `kitsune-paper` tests (grep).

**Interfaces:**
- `KitsuneConfig` gains `int rrfK`, `int fullTextLimit`, `int semanticLimit` immediately before `embeddingProvider`.
- Bounds: `rrfK` 1–1000, `fullTextLimit` 1–256, `semanticLimit` 1–256. Invalid → `invalid("hybrid rrf k")` / `"hybrid full-text limit"` / `"hybrid semantic limit"`.
- `SearchPolicy` gains the same three ints after `warmupTimeout`, same bounds (`rrfK` 1–1000, limits 1–256).
- YAML:

```yaml
search:
  hybrid:
    rrf-k: 60
    full-text-limit: 64
    semantic-limit: 64
```

- `ConfigLoader` reads `config.getInt("search.hybrid.rrf-k", 60)` and the two limit keys with defaults 64 so existing configs without the section still load.
- `BukkitRuntime` passes `open.config().rrfK()`, `fullTextLimit()`, `semanticLimit()` into `SearchPolicy`.
- There is no disable flag.

- [ ] **Step 1: Update `KitsuneConfigTest` and `ConfigLoaderTest` first** so they fail to compile against the old 13-arg `validated` / 5-arg `SearchPolicy`.
  - Append `60, 64, 64` before `"builtin:sparse-v1"` / `"p"` in every `validated` call.
  - Assert defaults: a MemoryConfiguration **without** `search.hybrid.*` still loads `rrfK=60`, limits 64.
  - Reject `rrf-k=0`, `full-text-limit=0`, `semantic-limit=257`.
  - `new SearchPolicy(16, 0.75, 10, 4, Duration.ofSeconds(1), 60, 64, 64)` in `SearchServiceTest.defaultPolicy()` and every other constructor.

- [ ] **Step 2: Compile tests**

Run: `./gradlew :kitsune-common:test :kitsune-paper:test --tests dev.jlo.kitsune.config.*`
Expected: FAIL compile until records/loader are updated.

- [ ] **Step 3: Implement record fields, validation, YAML, loader, runtime wiring.** Do not add retriever booleans.

- [ ] **Step 4: Run config tests PASS, then `SearchServiceTest` compile+PASS with the extra SearchPolicy args (behavior unchanged until Task 7).**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.config.KitsuneConfigTest --tests dev.jlo.kitsune.search.SearchServiceTest`
Run: `./gradlew :kitsune-paper:test --tests dev.jlo.kitsune.config.ConfigLoaderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add kitsune-common/src/main/java/dev/jlo/kitsune/config/KitsuneConfig.java \
  kitsune-common/src/main/java/dev/jlo/kitsune/search/SearchPolicy.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/config/KitsuneConfigTest.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/search/SearchServiceTest.java \
  kitsune-paper/src/main/java/dev/jlo/kitsune/config/ConfigLoader.java \
  kitsune-paper/src/main/java/dev/jlo/kitsune/bukkit/BukkitRuntime.java \
  kitsune-paper/src/main/resources/config.yml \
  kitsune-paper/src/test/java/dev/jlo/kitsune/config/ConfigLoaderTest.java
git commit -m "feat: add hybrid RRF k and retriever cap configuration"
```

---

### Task 5: V3 FTS schema and safe SQL splitting [FR-008, NFR-004]

**Files:**
- Create: `kitsune-common/src/main/resources/db/migration/V3__item_fts.sql`
- Modify: `kitsune-common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java` (`splitStatements`, `migrate`)
- Test: `kitsune-common/src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryTest.java`
- Test: `kitsune-common/src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryLifecycleTest.java` (reopen still works)

**Interfaces:**
- `splitStatements` must not split on `;` inside `BEGIN` … `END` trigger bodies (case-insensitive).
- `migrate()`:
  - current `3` → return
  - current not in `{null, 1, 2}` → `SQLException("Unsupported schema version: " + current)`
  - apply missing V1/V2 as today, then V3 DDL, then Java backfill (Task 6 can own backfill if this task only creates empty FTS for fresh DBs — **this task must backfill** because V2 DBs already have items)
  - after backfill: `COUNT(items) == COUNT(item_search)`; else rollback, leave version 2
  - set `schema_version` to `3` only after verification
- Fresh DB: V1+V2+V3 in one transaction as today does V1+V2.

**V3 SQL** (execute via improved splitter):

```sql
CREATE TABLE items_v3 (
    id INTEGER PRIMARY KEY,
    container_id INTEGER NOT NULL,
    path BLOB NOT NULL,
    amount INTEGER NOT NULL CHECK (amount > 0),
    descriptor BLOB NOT NULL,
    provider_id TEXT NOT NULL,
    provider_version INTEGER NOT NULL,
    vector BLOB NOT NULL,
    vector_norm REAL NOT NULL CHECK (vector_norm >= 0.0),
    UNIQUE (container_id, path),
    FOREIGN KEY (container_id) REFERENCES containers(id) ON DELETE CASCADE
);
INSERT INTO items_v3 (
    container_id, path, amount, descriptor, provider_id, provider_version, vector, vector_norm
)
SELECT container_id, path, amount, descriptor, provider_id, provider_version, vector, vector_norm
FROM items;
DROP TABLE items;
ALTER TABLE items_v3 RENAME TO items;

CREATE TABLE item_search (
    item_id INTEGER PRIMARY KEY,
    material TEXT NOT NULL,
    display TEXT NOT NULL,
    tags TEXT NOT NULL,
    enchantments TEXT NOT NULL,
    lore TEXT NOT NULL,
    FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
);

CREATE VIRTUAL TABLE item_fts USING fts5(
    material,
    display,
    tags,
    enchantments,
    lore,
    content='item_search',
    content_rowid='item_id',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TRIGGER item_search_ai AFTER INSERT ON item_search BEGIN
  INSERT INTO item_fts(rowid, material, display, tags, enchantments, lore)
  VALUES (new.item_id, new.material, new.display, new.tags, new.enchantments, new.lore);
END;

CREATE TRIGGER item_search_ad AFTER DELETE ON item_search BEGIN
  INSERT INTO item_fts(item_fts, rowid) VALUES('delete', old.item_id);
END;

CREATE TRIGGER item_search_au AFTER UPDATE ON item_search BEGIN
  INSERT INTO item_fts(item_fts, rowid) VALUES('delete', old.item_id);
  INSERT INTO item_fts(rowid, material, display, tags, enchantments, lore)
  VALUES (new.item_id, new.material, new.display, new.tags, new.enchantments, new.lore);
END;
```

Backfill in `migrate` after DDL, still inside the transaction:

```java
try (PreparedStatement select = connection.prepareStatement(
        "SELECT id, descriptor FROM items");
     ResultSet rs = select.executeQuery();
     PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO item_search (item_id, material, display, tags, enchantments, lore) VALUES (?, ?, ?, ?, ?, ?)")) {
  while (rs.next()) {
    ItemSearchDocument document = ItemSearchProjector.project(
        DescriptorCodec.decode(rs.getBytes(2)));
    insert.setLong(1, rs.getLong(1));
    insert.setString(2, document.material());
    insert.setString(3, document.display());
    insert.setString(4, document.tags());
    insert.setString(5, document.enchantments());
    insert.setString(6, document.lore());
    insert.addBatch();
  }
  insert.executeBatch();
}
```

Then count-check `items` vs `item_search`. Do not advertise version 3 on mismatch.

- [ ] **Step 1: Add tests**
  - `splitStatements` on a string containing a trigger with internal `;` yields the CREATE TRIGGER as **one** statement.
  - Open a V2-shaped DB: create via current code path is version 2 only if you stop migrate before this change — instead, insert a V2 database by running V1+V2 SQL in the test, set `schema_version=2`, insert one `items` row with `DescriptorCodec.encode` of an oak-planks descriptor and a dummy vector (`new byte[] {0}` is invalid if loadDocuments is called — use `SparseTagEmbeddingProvider` to embed, store `encode()` + `norm()`). Then open `SqliteIndexRepository`, `migrate()`, assert version 3, `COUNT(item_search) == COUNT(items)`, `loadDocuments` still returns the embedding.
  - Rollback: build a V2 DB with one well-formed item and one `items.descriptor` of `new byte[] {0x00}` so `DescriptorCodec.decode` throws during backfill. `migrate()` must throw, `schema_version` must remain `2`, and `item_fts` / `item_search` must not exist (or not be queryable as a completed V3). Then a second open of a clean V2 DB still migrates successfully.
  - Existing `SqliteIndexRepositoryTest` `migrate`/replace tests still pass.

- [ ] **Step 2: Run tests; expect V3 missing / version stuck at 2**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.index.SqliteIndexRepositoryTest --tests dev.jlo.kitsune.index.SqliteIndexRepositoryLifecycleTest`
Expected: FAIL until migrate applies V3.

- [ ] **Step 3: Implement splitter + V3 file + migrate.** Keep `PRAGMA foreign_keys = ON` as today.

Splitter sketch: scan character-by-character; track `beginDepth` incrementing on a `BEGIN` token and decrementing on `END`; split on `;` only when `beginDepth == 0`.

- [ ] **Step 4: Tests PASS**

Expected: PASS, including reopen lifecycle.

- [ ] **Step 5: Commit**

```bash
git add kitsune-common/src/main/resources/db/migration/V3__item_fts.sql \
  kitsune-common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryTest.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryLifecycleTest.java
git commit -m "feat: migrate SQLite index to FTS5 item projection"
```

---

### Task 6: FTS writes and lookup [FR-007, NFR-002]

**Files:**
- Modify: `kitsune-common/src/main/java/dev/jlo/kitsune/index/IndexRepository.java`
- Modify: `kitsune-common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java` (`replaceRoot`, `findFullTextMatches`)
- Modify fakes (add the new method, return `List.of()` unless the test needs hits):
  - `kitsune-common/src/test/java/dev/jlo/kitsune/search/SearchServiceTest.java` (`FakeIndexRepository`) — stub only this task
  - `kitsune-common/src/test/java/dev/jlo/kitsune/index/IndexWorkerTest.java`
  - `kitsune-common/src/test/java/dev/jlo/kitsune/index/CachedEmbeddingResolverTest.java`
  - `kitsune-paper/src/test/java/dev/jlo/kitsune/index/ContainerIndexLoadedRootRestorationTest.java`
- Test: `kitsune-common/src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryTest.java`

**Interfaces:**

```java
record FullTextMatch(
    RootIdentity root,
    ItemPath path,
    int amount,
    ItemDescriptor descriptor,
    double bm25
) {}

List<FullTextMatch> findFullTextMatches(
    String matchExpression,
    UUID worldId,
    int minChunkX,
    int maxChunkX,
    int minChunkZ,
    int maxChunkZ,
    int limit
) throws SQLException;
```

- `limit <= 0` throws `IllegalArgumentException("Full-text limit must be positive")`.
- Empty `matchExpression` returns `List.of()` (SearchService will not call this for empty queries).
- SQL as in the spec: `bm25(item_fts, 10.0, 8.0, 6.0, 5.0, 2.0)`, `ORDER BY rank ASC, c.x, c.y, c.z, i.path`, `LIMIT ?`, available chunks, world + chunk bounds.
- Bind MATCH as `ps.setString(1, matchExpression)` — never concatenate.
- Decode path with `ItemPathCodec.decode`, descriptor with `DescriptorCodec.decode`.
- `replaceRoot`: after `DELETE FROM items WHERE container_id = ?` (cascade must clear `item_search`/FTS), insert each item with `Statement.RETURN_GENERATED_KEYS` or `INSERT … RETURNING id`, then insert `item_search` for that `id` using `ItemSearchProjector`. Same transaction.

- [ ] **Step 1: Write repository tests**
  - `replaceRoot` of a diamond pickaxe; `findFullTextMatches(FullTextQuery.parse("diam").matchExpression(), …)` returns that path.
  - `replaceRoot` with a different snapshot removes the old FTS hit.
  - `deleteRoot` → zero FTS rows for that container (`SELECT COUNT(*) FROM item_search s JOIN items i ON i.id = s.item_id WHERE i.container_id = ?`).
  - Reopen DB, same MATCH still hits.
  - World/chunk predicates exclude a container in another world or far chunk.
  - Unavailable chunk (`setChunkAvailable(..., false, ...)`) excludes the hit.
  - More than `limit` hits returns exactly `limit` rows in bm25 order.
  - Raw MATCH operators in the **parameter** from `FullTextQuery.parse("foo AND bar")` still run as literals (no SQL exception).

- [ ] **Step 2: Run; expect interface / SQL missing**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.index.SqliteIndexRepositoryTest`
Expected: FAIL compile or FAIL assertions.

- [ ] **Step 3: Implement interface method, SQLite SQL, replaceRoot FTS inserts, and empty stubs on fakes.**

- [ ] **Step 4: `./gradlew :kitsune-common:test :kitsune-paper:test` PASS** (SearchService still cosine-only).

- [ ] **Step 5: Commit**

```bash
git add kitsune-common/src/main/java/dev/jlo/kitsune/index/IndexRepository.java \
  kitsune-common/src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryTest.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/index/IndexWorkerTest.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/index/CachedEmbeddingResolverTest.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/search/SearchServiceTest.java \
  kitsune-paper/src/test/java/dev/jlo/kitsune/index/ContainerIndexLoadedRootRestorationTest.java
git commit -m "feat: persist and query FTS5 matches beside item vectors"
```

---

### Task 7: SearchService RRF fusion [FR-001, FR-004, FR-006, NFR-001, NFR-002, FR-010]

**Files:**
- Modify: `kitsune-common/src/main/java/dev/jlo/kitsune/search/SearchService.java`
- Modify: `kitsune-common/src/test/java/dev/jlo/kitsune/search/SearchServiceTest.java`

**Interfaces:**
- `search(...)` still returns `CompletableFuture<SearchOutcome>`.
- After `requireCurrent`, parse `FullTextQuery.parse(request.query())`. If `isEmpty()`, return `unsupportedQuery` (do not embed, do not FTS).
- Else embed as today. If zero-norm, unsupported as today (fail closed; do not return FTS-only).
- Submit `repository.findFullTextMatches(query.matchExpression(), bounds..., policy.fullTextLimit())` on `worker`.
- Validate FTS hit roots with the same `liveRootAccess.validate` batching as spatial candidates. Drop denied/stale/out-of-radius. Never pass denied keys to `loadDocuments`.
- Keep spatial paging + cosine; keep hits with cosine `>= minimumScore`; cap to `policy.semanticLimit()` after sort.
- Cap FTS list to `fullTextLimit` (repository already limited; re-cap after access filter, preserving BM25 order).
- Fuse remaining FTS + semantic items by `(root.key(), path)` using `ReciprocalRankFusion.displayScore(policy.rrfK(), ftsRank, semanticRank)`.
- FTS-only items become `ItemMatch` with the fused display score, FTS amount/descriptor/path.
- Semantic-only items use fused display score (not raw cosine).
- Root rollup uses fused scores; comparator unchanged (score desc, distance, coords).
- `maxResults` / `maxPathsPerRoot` still apply after fusion.
- Existing session cancel / warmup / failure mapping unchanged.

**FakeIndexRepository:** add a `Set` or flag on `ScoreMatch` (`boolean fullTextHit`). `findFullTextMatches` returns seeded items whose `fullTextHit` is true, in seed order, bm25 = `-index` so order is stable. Cosine still uses `score`. Helper `scoreMatch` defaults `fullTextHit=false`. New helper `ftsMatch(path, cosine, slot)` sets `fullTextHit=true`.

- [ ] **Step 1: Write failing SearchService tests** (keep existing tests compiling; their cosine-only seeds need `fullTextHit=true` **or** high cosine so they still appear — **prefer leaving them cosine-only** so FR-001 AC-2 is proven by existing diamond searches). Update any test that asserted exact cosine `0.99` as `bestScore` if that item is not in the FTS list: display becomes `displayScore(k, null, 1) == 0.5` for a sole semantic rank-1 item. That is an intentional contract change — update those assertions to the fused display score.
  - `ftsOnlyHitAppearsWhenCosineBelowMinimum`: FTS item cosine 0.1, policy minimum 0.8, `fullTextHit=true` → SUCCESS, that root present.
  - `cosineOnlyHitAppearsWhenNotInFtsList`: cosine 0.95, `fullTextHit=false` → present, score `0.5` at default k.
  - `bothListsBeatOneList`: three roots at same distance; both-lists item ranks first.
  - `deniedFtsRootNeverLoadsOrRenders`: denied + `fullTextHit=true` high cosine; outcome omits it; `loadDocuments` never sees it.
  - `emptyQueryIsUnsupportedWithoutRepositoryCalls`: query `"!!!"` → unsupported; findFullText/loadDocuments not called.
  - Existing protection, paging, warmup tests still PASS.

- [ ] **Step 2: Run SearchServiceTest; expect assertion failures on scores / missing FTS-only hits**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.search.SearchServiceTest`
Expected: FAIL assertions.

- [ ] **Step 3: Implement fusion in `SearchService`.** FTS + cosine work via `worker.submit`. Main thread only `liveRootAccess` / `serverBridge.supply`.

- [ ] **Step 4: SearchServiceTest PASS, then full module tests PASS**

Run: `./gradlew :kitsune-common:test --tests dev.jlo.kitsune.search.SearchServiceTest`
Run: `./gradlew :kitsune-api:test :kitsune-common:test :kitsune-paper:test`
Expected: BUILD SUCCESSFUL, all PASS.

- [ ] **Step 5: Commit**

```bash
git add kitsune-common/src/main/java/dev/jlo/kitsune/search/SearchService.java \
  kitsune-common/src/test/java/dev/jlo/kitsune/search/SearchServiceTest.java
git commit -m "feat: fuse FTS5 and cosine rankings with reciprocal rank fusion"
```

---

### Task 8: README + final verification [FR-010]

**Files:**
- Modify: `README.md` (ranking sentence + config snippet)

- [ ] **Step 1: Change the README ranking bullet and config sample** to document hybrid FTS5 + embeddings, RRF, and `search.hybrid.*`. Do not mention phonetic or retriever toggles. Do not point at `api/`, `common/`, or `bukkit/`.

- [ ] **Step 2: Final test run**

Run: `./gradlew :kitsune-api:test :kitsune-common:test :kitsune-paper:test`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: describe hybrid FTS5 and semantic ranking"
```

---

## Spec coverage

| Requirement | Task |
|---|---|
| FR-001 both retrievers + RRF | 7 |
| FR-002 MATCH safety | 1, 6 |
| FR-003 prefix + alias AND-groups | 1 |
| FR-004 minimum-score semantic-only | 7 |
| FR-005 display scores in `[0,1]` | 3, 7 |
| FR-006 protection on FTS hits | 6, 7 |
| FR-007 FTS aligned with items | 2, 5, 6 |
| FR-008 V3 backfill + vectors | 5 |
| FR-009 config, no toggles | 4 |
| FR-010 commands/markers | 7, 8 |
| NFR-001 off-main SQL | 7 |
| NFR-002 caps | 4, 6, 7 |
| NFR-003 deterministic MATCH | 1 |
| NFR-004 transactional migrate | 5 |

Out of scope here: phonetic, lexical-only runtime, deleting `api/`/`common/`/`bukkit/` (see `docs/superpowers/specs/2026-08-24-legacy-module-cutover-design.md`).
