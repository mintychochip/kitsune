# Kitsune Nearby Storage Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java 21 Paper 1.21.4 plugin that persistently indexes loaded block storage, ranks nested item paths with local cosine similarity, filters protected roots before scoring, and renders temporary player-private search billboards from `/kitsune`.

**Architecture:** Paper objects are read only on the server thread and converted to immutable records. A single background worker owns SQLite, descriptor encoding, vector generation, and scoring. Searches alternate deliberately between background candidate/vector work and server-thread chunk/access/entity validation; per-player generation tokens prevent canceled async work from rendering.

**Tech Stack:** Java 21; Gradle 9.1.0 Kotlin DSL; Paper API `1.21.4-R0.1-SNAPSHOT`; RunPaper `3.0.2`; Shadow `9.6.1`; SQLite JDBC `3.53.2.1`; JUnit Jupiter `5.14.4`.

## Global Constraints

- Source specification: `docs/superpowers/specs/2026-07-31-kitsune-nearby-storage-search-design.md`.
- Target Paper 1.21.4 exactly and compile Java bytecode with `--release 21`.
- Use `xyz.jpenilla.run-paper` and run `runServer` with a Java 21 toolchain; this workstation has Java 25 only, so Gradle toolchain provisioning must supply Java 21.
- Never load or generate a chunk for search or indexing.
- Never iterate chunk block volume; enumerate `Chunk#getTileEntities(false)` and filter persistent block inventory holders.
- Root scope follows the explicit product decision: index every loaded persistent block inventory holder, not only chests/trapped chests; exclude ender-chest, player, and entity inventories.
- Never access Bukkit/Paper world, chunk, block, inventory, `ItemStack`, entity, player, or protection objects on a background executor.
- SQLite is a rebuildable persistence/cache layer; unloaded or unvalidated rows never become results.
- Filter protection at the root-identity stage before loading item descriptors/vectors, then revalidate immediately before rendering.
- Default search radius is 32 blocks, hard maximum 128; default marker duration is 20 seconds; default result cap is 32.
- `/kitsune <query...>` and `/kitsune --verbose <query...>` are player-only; verbose is per invocation.
- No inventory GUI, teleport, root opening, block mutation, forced chunk loading, ONNX runtime, or remote embedding requirement.
- Unknown custom items retain baseline material/text/metadata/PDC features; opaque custom contents require `NestedContentsProvider`.
- Display entities must be configured `visibleByDefault=false` in the pre-spawn callback and shown only to the search owner.
- Tests are pure JUnit where possible. Paper behavior is proven with RunPaper, not an unverified MockBukkit version.
- Each task's production change and contract tests form one atomic commit. Do not stage `.superpowers/`, `run/`, `.gradle/`, or unrelated files.

## Verified External References

- RunPaper 3.0.2: <https://plugins.gradle.org/plugin/xyz.jpenilla.run-paper>
- RunPaper configuration: <https://github.com/jpenilla/run-task>
- Paper 1.21.4 API artifact: <https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/1.21.4-R0.1-SNAPSHOT/maven-metadata.xml>
- Tile-entity enumeration: <https://jd.papermc.io/paper/1.21.4/org/bukkit/Chunk.html#getTileEntities(boolean)>
- Default-hidden entities: <https://jd.papermc.io/paper/1.21.4/org/bukkit/entity/Entity.html#setVisibleByDefault(boolean)>
- Per-player visibility: <https://jd.papermc.io/paper/1.21.4/org/bukkit/entity/Player.html#showEntity(org.bukkit.plugin.Plugin,org.bukkit.entity.Entity)>
- Pre-spawn configuration: <https://jd.papermc.io/paper/1.21.4/org/bukkit/RegionAccessor.html#spawn(org.bukkit.Location,java.lang.Class,java.util.function.Consumer)>
- LWCX source/API and publication identity: <https://github.com/pop4959/LWCX>

## Planned File Structure

### Build and resources

- `.gitignore` — build, run-server, IDE, and brainstorming artifacts.
- `settings.gradle.kts` — project name and Java toolchain resolver.
- `build.gradle.kts` — Paper, SQLite, Shadow, JUnit, and RunPaper configuration.
- `gradle.properties` — project version/group and Gradle JVM settings.
- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` — pinned Gradle 9.1.0 wrapper.
- `src/main/resources/plugin.yml` — plugin metadata, command, permission, soft dependencies.
- `src/main/resources/config.yml` — approved bounded defaults.
- `src/main/resources/db/migration/V1__initial.sql` — SQLite schema.

### Lifecycle and configuration

- `src/main/java/dev/jlo/kitsune/KitsunePlugin.java` — construction, wiring, enable/disable order.
- `src/main/java/dev/jlo/kitsune/config/KitsuneConfig.java` — immutable validated settings.
- `src/main/java/dev/jlo/kitsune/config/ConfigLoader.java` — Bukkit YAML adapter only.
- `src/main/java/dev/jlo/kitsune/command/KitsuneCommand.java` — player/permission/syntax/outcome mapping.
- `src/main/java/dev/jlo/kitsune/command/SearchRequest.java` — pure command parser.

### Public extension API

- `src/main/java/dev/jlo/kitsune/api/embedding/Embedding.java`
- `src/main/java/dev/jlo/kitsune/api/embedding/EmbeddingProvider.java`
- `src/main/java/dev/jlo/kitsune/api/item/ItemFeatureProvider.java`
- `src/main/java/dev/jlo/kitsune/api/item/NestedContentsProvider.java`
- `src/main/java/dev/jlo/kitsune/api/protection/AccessDecision.java`
- `src/main/java/dev/jlo/kitsune/api/protection/BlockAccessProvider.java`

### Immutable model and embeddings

- `src/main/java/dev/jlo/kitsune/model/BlockKey.java`
- `src/main/java/dev/jlo/kitsune/model/ChunkKey.java`
- `src/main/java/dev/jlo/kitsune/model/ItemPath.java`
- `src/main/java/dev/jlo/kitsune/model/ItemPathStep.java`
- `src/main/java/dev/jlo/kitsune/model/ItemDescriptor.java`
- `src/main/java/dev/jlo/kitsune/model/IndexedItem.java`
- `src/main/java/dev/jlo/kitsune/model/ContainerSnapshot.java`
- `src/main/java/dev/jlo/kitsune/model/RootIdentity.java`
- `src/main/java/dev/jlo/kitsune/model/ItemDraft.java`
- `src/main/java/dev/jlo/kitsune/model/ContainerDraft.java`
- `src/main/java/dev/jlo/kitsune/embedding/FeatureVocabulary.java`
- `src/main/java/dev/jlo/kitsune/embedding/SparseEmbedding.java`
- `src/main/java/dev/jlo/kitsune/embedding/SparseTagEmbeddingProvider.java`
- `src/main/java/dev/jlo/kitsune/embedding/EmbeddingRegistry.java`

### Item extraction

- `src/main/java/dev/jlo/kitsune/item/BukkitItemDescriber.java`
- `src/main/java/dev/jlo/kitsune/item/ItemFeatureRegistry.java`
- `src/main/java/dev/jlo/kitsune/item/NestedContentsRegistry.java`
- `src/main/java/dev/jlo/kitsune/item/NestedItemWalker.java`
- `src/main/java/dev/jlo/kitsune/item/ShulkerContentsProvider.java`
- `src/main/java/dev/jlo/kitsune/item/BundleContentsProvider.java`
- `src/main/java/dev/jlo/kitsune/item/TraversalLimits.java`

### Persistence and live index

- `src/main/java/dev/jlo/kitsune/index/IndexRepository.java`
- `src/main/java/dev/jlo/kitsune/index/SqliteIndexRepository.java`
- `src/main/java/dev/jlo/kitsune/index/DescriptorCodec.java`
- `src/main/java/dev/jlo/kitsune/index/ItemPathCodec.java`
- `src/main/java/dev/jlo/kitsune/index/IndexWorker.java`
- `src/main/java/dev/jlo/kitsune/index/RootResolver.java`
- `src/main/java/dev/jlo/kitsune/index/ContainerSnapshotter.java`
- `src/main/java/dev/jlo/kitsune/index/DirtyRootTracker.java`
- `src/main/java/dev/jlo/kitsune/index/ReconciliationCursor.java`
- `src/main/java/dev/jlo/kitsune/index/ContainerIndex.java`
- `src/main/java/dev/jlo/kitsune/index/IndexListener.java`
- `src/main/java/dev/jlo/kitsune/index/IndexWarmupTimeoutException.java`

### Protection, search, and presentation

- `src/main/java/dev/jlo/kitsune/protection/ProtectionRegistry.java`
- `src/main/java/dev/jlo/kitsune/protection/LwcProtectionProvider.java`
- `src/main/java/dev/jlo/kitsune/search/ServerThreadBridge.java`
- `src/main/java/dev/jlo/kitsune/search/BukkitServerThreadBridge.java`
- `src/main/java/dev/jlo/kitsune/search/SearchService.java`
- `src/main/java/dev/jlo/kitsune/search/SearchOutcome.java`
- `src/main/java/dev/jlo/kitsune/search/RootMatch.java`
- `src/main/java/dev/jlo/kitsune/search/LeafMatch.java`
- `src/main/java/dev/jlo/kitsune/search/SearchContext.java`
- `src/main/java/dev/jlo/kitsune/search/SearchGuard.java`
- `src/main/java/dev/jlo/kitsune/session/SearchToken.java`
- `src/main/java/dev/jlo/kitsune/session/SearchSessionManager.java`
- `src/main/java/dev/jlo/kitsune/ui/ChatTreeRenderer.java`
- `src/main/java/dev/jlo/kitsune/ui/MarkerRenderer.java`
- `src/main/java/dev/jlo/kitsune/ui/SearchPresenter.java`
- `src/main/java/dev/jlo/kitsune/ui/RenderedMarker.java`
- `src/main/java/dev/jlo/kitsune/ui/SessionListener.java`

Tests mirror these packages under `src/test/java`. Pure fakes live under `src/test/java/dev/jlo/kitsune/support`; no production mock layer is introduced.

---

### Task 1: Bootstrap a runnable Paper 1.21.4 plugin

**Files:**
- Create: `.gitignore`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `src/main/java/dev/jlo/kitsune/KitsunePlugin.java`
- Create: `src/main/resources/plugin.yml`
- Create: `src/main/resources/config.yml`

**Interfaces:**
- Consumes: Paper 1.21.4, Java 21 toolchain, RunPaper 3.0.2.
- Produces: a shaded plugin JAR, a registered `/kitsune` command whose empty invocation displays usage, and `runServer` fixed to Paper 1.21.4/Java 21.

- [ ] **Step 1: Generate the pinned Gradle wrapper**

Use the official Gradle 9.1.0 distribution because the workstation has no system Gradle:

```bash
work="$(mktemp -d)"
curl --fail --location https://services.gradle.org/distributions/gradle-9.1.0-bin.zip --output "$work/gradle.zip"
unzip -q "$work/gradle.zip" -d "$work"
"$work/gradle-9.1.0/bin/gradle" wrapper --gradle-version 9.1.0 --distribution-type bin
```

Expected: `./gradlew --version` reports Gradle 9.1.0. Remove only the temporary directory after the wrapper exists.

- [ ] **Step 2: Create the build configuration**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kitsune"
```

`gradle.properties`:

```properties
group=dev.jlo
version=0.1.0-SNAPSHOT
org.gradle.jvmargs=-Xmx2G -Dfile.encoding=UTF-8
```

`build.gradle.kts`:

```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.53.2.1")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion("1.21.4")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    jvmArgs("-Dcom.mojang.eula.agree=true")
}
```

`.gitignore`:

```gitignore
.gradle/
build/
run/
.idea/
*.iml
.superpowers/
```

- [ ] **Step 3: Create the minimal lifecycle and resources**

`KitsunePlugin.java`:

```java
package dev.jlo.kitsune;

import org.bukkit.plugin.java.JavaPlugin;

public final class KitsunePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();
        getLogger().info("Kitsune enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Kitsune disabled");
    }
}
```

`plugin.yml`:

```yaml
name: Kitsune
version: '${version}'
main: dev.jlo.kitsune.KitsunePlugin
api-version: '1.21.4'
description: Semantic nearby storage search
softdepend: [LWC]
commands:
  kitsune:
    description: Search nearby storage
    usage: /kitsune [--verbose] <query>
    permission: kitsune.search
permissions:
  kitsune.search:
    description: Search nearby accessible storage
    default: true
```

`config.yml` must contain the exact approved defaults:

```yaml
search:
  radius: 32
  max-radius: 128
  minimum-score: 0.30
  max-results: 32
  max-paths-per-root: 16
  warmup-timeout-seconds: 3
markers:
  duration-seconds: 20
index:
  chunks-per-tick: 2
  roots-per-tick: 8
  reconciliation-period-ticks: 200
  maximum-depth: 4
  maximum-stacks-per-root: 4096
embedding:
  provider: builtin:sparse-v1
```

- [ ] **Step 4: Build and boot the plugin**

Run:

```bash
./gradlew clean build
./gradlew runServer
```

Expected build: `BUILD SUCCESSFUL` and `build/libs/kitsune-0.1.0-SNAPSHOT.jar`. Expected server log: Java 21 runtime, Paper 1.21.4, `Kitsune enabled`, and no missing SQLite class. At the server console run `kitsune`; expected output is the `plugin.yml` usage line. Enter `stop` and confirm `Kitsune disabled`.

- [ ] **Step 5: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle src
 git commit -m "Bootstrap Paper 1.21.4 plugin"
```

### Task 2: Validate configuration and command syntax

**Files:**
- Create: `src/main/java/dev/jlo/kitsune/config/KitsuneConfig.java`
- Create: `src/main/java/dev/jlo/kitsune/config/ConfigLoader.java`
- Create: `src/main/java/dev/jlo/kitsune/command/SearchRequest.java`
- Test: `src/test/java/dev/jlo/kitsune/config/KitsuneConfigTest.java`
- Test: `src/test/java/dev/jlo/kitsune/command/SearchRequestTest.java`

**Interfaces:**
- Consumes: YAML keys from Task 1.
- Produces: `KitsuneConfig`, `SearchRequest.parse(String[])`, and exact startup validation used by every later component.

- [ ] **Step 1: Write failing parser and bounds tests**

```java
@Test
void parsesInvocationScopedVerboseQuery() {
    assertEquals(new SearchRequest("diamond sword", true),
        SearchRequest.parse(new String[]{"--verbose", "diamond", "sword"}));
}

@Test
void rejectsMisplacedFlagAndEmptyQuery() {
    assertThrows(IllegalArgumentException.class,
        () -> SearchRequest.parse(new String[]{"diamond", "--verbose"}));
    assertThrows(IllegalArgumentException.class,
        () -> SearchRequest.parse(new String[]{}));
}

@Test
void rejectsUnsafeConfiguration() {
    assertThrows(IllegalArgumentException.class,
        () -> KitsuneConfig.validated(129, 128, 0.30, 32, 16, 3, 20, 2, 8, 200, 4, 4096,
            "builtin:sparse-v1"));
    assertThrows(IllegalArgumentException.class,
        () -> KitsuneConfig.validated(32, 128, Double.NaN, 32, 16, 3, 20, 2, 8, 200, 4, 4096,
            "builtin:sparse-v1"));
}
```

- [ ] **Step 2: Run the focused tests and confirm failure**

Run:

```bash
./gradlew test --tests '*SearchRequestTest' --tests '*KitsuneConfigTest'
```

Expected: compilation fails because `SearchRequest` and `KitsuneConfig` do not exist.

- [ ] **Step 3: Implement exact parsing and validated immutable settings**

`SearchRequest`:

```java
public record SearchRequest(String query, boolean verbose) {
    public SearchRequest {
        query = query.strip().replaceAll("\\s+", " ");
        if (query.isEmpty()) throw new IllegalArgumentException("Query must not be empty");
    }

    public static SearchRequest parse(String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("Usage: /kitsune [--verbose] <query>");
        boolean verbose = args[0].equals("--verbose");
        int start = verbose ? 1 : 0;
        if (start == args.length) throw new IllegalArgumentException("Usage: /kitsune [--verbose] <query>");
        for (int i = start; i < args.length; i++) {
            if (args[i].startsWith("--")) throw new IllegalArgumentException("Unknown or misplaced flag: " + args[i]);
        }
        return new SearchRequest(String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length)), verbose);
    }
}
```

`KitsuneConfig` is a record with these fields in order: `radius`, `maxRadius`, `minimumScore`, `maxResults`, `maxPathsPerRoot`, `warmupTimeoutSeconds`, `markerDurationSeconds`, `chunksPerTick`, `rootsPerTick`, `reconciliationPeriodTicks`, `maximumDepth`, `maximumStacksPerRoot`, `embeddingProvider`. Its `validated(...)` factory must reject:

```java
if (radius < 1 || maxRadius < 1 || maxRadius > 128 || radius > maxRadius) throw invalid("search radius");
if (!Double.isFinite(minimumScore) || minimumScore < 0.0 || minimumScore > 1.0) throw invalid("minimum score");
if (maxResults < 1 || maxResults > 256) throw invalid("max results");
if (maxPathsPerRoot < 1 || maxPathsPerRoot > 128) throw invalid("max paths per root");
if (warmupTimeoutSeconds < 1 || warmupTimeoutSeconds > 30) throw invalid("warmup timeout");
if (markerDurationSeconds < 1 || markerDurationSeconds > 300) throw invalid("marker duration");
if (chunksPerTick < 1 || chunksPerTick > 16 || rootsPerTick < 1 || rootsPerTick > 128) throw invalid("index budget");
if (reconciliationPeriodTicks < 20 || reconciliationPeriodTicks > 72_000) throw invalid("reconciliation period");
if (maximumDepth < 1 || maximumDepth > 8) throw invalid("maximum depth");
if (maximumStacksPerRoot < 1 || maximumStacksPerRoot > 16_384) throw invalid("maximum stacks per root");
if (embeddingProvider == null || embeddingProvider.isBlank()) throw invalid("embedding provider");
```

`ConfigLoader.load(JavaPlugin)` reads each exact YAML path from Task 1 and calls `KitsuneConfig.validated`; it does not clamp or substitute an invalid value.

- [ ] **Step 4: Verify tests and config startup**

Run:

```bash
./gradlew test --tests '*SearchRequestTest' --tests '*KitsuneConfigTest'
```

Expected: all parser and validation tests pass. Change a copied test YAML value to `search.radius: 129`; `ConfigLoader` must throw with `Invalid search radius` rather than clamp.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/jlo/kitsune/config src/main/java/dev/jlo/kitsune/command/SearchRequest.java src/test
 git commit -m "Validate Kitsune commands and configuration"
```

### Task 3: Implement deterministic sparse embeddings

**Files:**
- Create: public embedding API and all immutable model/embedding files listed above.
- Test: `src/test/java/dev/jlo/kitsune/embedding/SparseTagEmbeddingProviderTest.java`
- Test: `src/test/java/dev/jlo/kitsune/model/ModelImmutabilityTest.java`

**Interfaces:**
- Consumes: normalized query text from `SearchRequest`.
- Produces: `EmbeddingProvider`, `SparseTagEmbeddingProvider.ID = "builtin:sparse-v1"`, immutable descriptors/paths/snapshots, encoded sparse vectors, and cosine scoring.

`ItemDescriptor` is an immutable final class with a nested `Builder`. The builder exposes `materialKey(String)`, `amount(int)`, `addDisplayText(String)`, `addLore(String)`, `addEnchantment(String, int)`, `addAttribute(String, double)`, `addTrait(String)`, `addScalarMetadata(String, String)`, `addCustomTag(String)`, and `build()`. `build()` copies and sorts every collection; constructed descriptors expose unmodifiable collections only.

The immutable draft/persisted types are fixed as follows:

```java
public record ItemDraft(ItemPath path, int amount, ItemDescriptor descriptor) {}
public record ContainerDraft(BlockKey key, String blockType, byte[] fingerprint, List<ItemDraft> items) {}
public record IndexedItem(ItemPath path, int amount, ItemDescriptor descriptor, Embedding embedding) {}
public record ContainerSnapshot(BlockKey key, String blockType, byte[] fingerprint, List<IndexedItem> items) {}
```

Each record validates positive amounts, non-empty keys/types, defensive-copies byte arrays and lists, and returns a clone from every byte-array accessor. `EmbeddingRegistry` always registers `SparseTagEmbeddingProvider`, adds Bukkit `ServicesManager` providers by unique ID, rejects duplicate IDs, selects the configured provider exactly, and rejects a missing provider instead of falling back.

- [ ] **Step 1: Write failing observable ranking tests**

```java
private final SparseTagEmbeddingProvider provider = new SparseTagEmbeddingProvider();

@Test
void semanticMiningQueryRanksPickaxeAboveFood() {
    var pickaxe = descriptor("minecraft:diamond_pickaxe", Set.of("tool", "mining"), Set.of("mending"));
    var beef = descriptor("minecraft:cooked_beef", Set.of("food"), Set.of());
    var query = provider.embedQuery("mining tool");
    assertTrue(query.cosine(provider.embed(pickaxe)) > query.cosine(provider.embed(beef)));
}

@Test
void metadataQueryMatchesEnchantment() {
    var enchanted = descriptor("minecraft:diamond_pickaxe", Set.of("tool"), Set.of("mending"));
    var plain = descriptor("minecraft:diamond_pickaxe", Set.of("tool"), Set.of());
    var query = provider.embedQuery("mending");
    assertTrue(query.cosine(provider.embed(enchanted)) > query.cosine(provider.embed(plain)));
}

@Test
void blankOrPunctuationOnlyQueryHasZeroNorm() {
    assertEquals(0.0, provider.embedQuery("---").norm());
}

private ItemDescriptor descriptor(String material, Set<String> traits, Set<String> enchantments) {
    ItemDescriptor.Builder builder = ItemDescriptor.builder().materialKey(material).amount(1);
    traits.forEach(builder::addTrait);
    enchantments.forEach(key -> builder.addEnchantment("minecraft:" + key, 1));
    return builder.build();
}

@Test
void modelConstructorsDefensivelyCopyInputs() {
    List<ItemPathStep> steps = new ArrayList<>(List.of(new ItemPathStep("Chest", 4)));
    byte[] fingerprint = new byte[]{1, 2, 3};
    Map<String, Double> values = new HashMap<>(Map.of("token:diamond", 1.0));
    ItemPath path = new ItemPath(steps);
    ContainerSnapshot snapshot = new ContainerSnapshot(
        new BlockKey(UUID.randomUUID(), 0, 64, 0), "minecraft:chest", fingerprint, List.of());
    SparseEmbedding vector = SparseEmbedding.of("builtin:sparse-v1", 1, values);

    steps.clear();
    fingerprint[0] = 9;
    values.clear();

    assertEquals(1, path.steps().size());
    assertArrayEquals(new byte[]{1, 2, 3}, snapshot.fingerprint());
    assertEquals(1, vector.values().size());
    assertThrows(UnsupportedOperationException.class,
        () -> vector.values().put("token:mutate", 1.0));
}
```

Also test that constructor inputs cannot mutate `ItemDescriptor`, `ItemPath`, `ContainerSnapshot`, or vector maps after construction.

- [ ] **Step 2: Confirm tests fail**

Run:

```bash
./gradlew test --tests '*SparseTagEmbeddingProviderTest' --tests '*ModelImmutabilityTest'
```

Expected: compilation fails on missing embedding/model types.

- [ ] **Step 3: Implement stable interfaces and cosine math**

Use these exact public contracts:

```java
public interface Embedding {
    String providerId();
    int providerVersion();
    double norm();
    byte[] encode();
    double cosine(Embedding other);
}

public interface EmbeddingProvider {
    String id();
    int version();
    Embedding embed(ItemDescriptor descriptor);
    Embedding embedQuery(String query);
    Embedding decode(byte[] payload, double norm);
}
```

`SparseEmbedding.cosine` must reject a different provider ID/version, return `0.0` for either zero norm, iterate the smaller feature map, and compute:

```java
double dot = 0.0;
for (var entry : smaller.entrySet()) {
    dot += entry.getValue() * larger.getOrDefault(entry.getKey(), 0.0);
}
return Math.max(0.0, Math.min(1.0, dot / (norm * other.norm)));
```

Encode a sparse vector deterministically with `DataOutputStream`: entry count followed by lexicographically sorted UTF key/double value pairs. `decode` rejects negative counts, duplicate keys, non-finite/non-positive values, trailing bytes, and mismatched externally stored norm.

- [ ] **Step 4: Implement the versioned feature profile**

`FeatureVocabulary` lowercases with `Locale.ROOT`, splits on non-ASCII alphanumeric boundaries and underscores, and emits these v1 aliases in addition to the original token:

```java
Map.ofEntries(
    entry("sword", Set.of("weapon", "melee")),
    entry("axe", Set.of("tool", "weapon", "chopping")),
    entry("pickaxe", Set.of("tool", "mining")),
    entry("shovel", Set.of("tool", "digging")),
    entry("hoe", Set.of("tool", "farming")),
    entry("helmet", Set.of("armor", "head")),
    entry("chestplate", Set.of("armor", "chest")),
    entry("leggings", Set.of("armor", "legs")),
    entry("boots", Set.of("armor", "feet")),
    entry("food", Set.of("edible"))
)
```

`SparseTagEmbeddingProvider` accumulates one shared `token:<value>` feature namespace with weights: material/name `8.0`, custom IDs `6.0`, enchantments/attributes `5.0`, explicit material traits `4.0`, scalar PDC/custom metadata `3.0`, and lore/general text `2.0`. Query tokens and their aliases use `1.0`. Store `ID = "builtin:sparse-v1"` and `VERSION = 1`; changing weights or vocabulary requires incrementing `VERSION`.

- [ ] **Step 5: Verify focused and full tests**

Run:

```bash
./gradlew test --tests '*SparseTagEmbeddingProviderTest' --tests '*ModelImmutabilityTest'
./gradlew test
```

Expected: ranking, cosine boundaries, codec round trips, malformed payload rejection, and immutability tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/jlo/kitsune/api/embedding src/main/java/dev/jlo/kitsune/model src/main/java/dev/jlo/kitsune/embedding src/test/java/dev/jlo/kitsune/embedding src/test/java/dev/jlo/kitsune/model
 git commit -m "Add deterministic sparse item embeddings"
```

### Task 4: Persist roots and item documents in SQLite

**Files:**
- Create: all persistence files listed under “Persistence and live index”, except Paper-facing resolver/snapshot/index/listener files.
- Create: `src/main/resources/db/migration/V1__initial.sql`
- Test: `src/test/java/dev/jlo/kitsune/index/SqliteIndexRepositoryTest.java`
- Test: `src/test/java/dev/jlo/kitsune/index/DescriptorCodecTest.java`

**Interfaces:**
- Consumes: `ContainerSnapshot`, `ItemDescriptor`, `ItemPath`, `EmbeddingProvider`.
- Produces: synchronous `IndexRepository` used only by `IndexWorker`, atomic root replacement, candidate identity lookup, allowed-ID document loading, and restart-safe availability state.

- [ ] **Step 1: Write failing SQLite contract tests with `@TempDir`**

Cover these observable operations:

```java
@TempDir Path tempDir;

@Test
void migratesEmptyDatabaseAndReopensWithoutMutation() throws Exception {
    Path database = tempDir.resolve("index.db");
    try (var first = RepositoryTestFixture.open(database)) {
        first.repository().migrate();
        assertEquals(1, first.schemaVersion());
    }
    try (var second = RepositoryTestFixture.open(database)) {
        second.repository().migrate();
        assertEquals(1, second.schemaVersion());
    }
}

@Test
void replacementDeletesOldDocumentsAtomically() throws Exception {
    try (var fixture = RepositoryTestFixture.open(tempDir.resolve("replace.db"))) {
        BlockKey root = fixture.key(0, 64, 0);
        fixture.repository().replaceRoot(fixture.snapshot(root, "old-item"), 1);
        fixture.repository().replaceRoot(fixture.snapshot(root, "new-item"), 2);
        assertEquals(List.of("new-item"), fixture.materialKeys(root));
    }
}

@Test
void deletionCascadesDocuments() throws Exception {
    try (var fixture = RepositoryTestFixture.open(tempDir.resolve("delete.db"))) {
        BlockKey root = fixture.key(0, 64, 0);
        fixture.repository().replaceRoot(fixture.snapshot(root, "diamond"), 1);
        fixture.repository().deleteRoot(root);
        assertEquals(0, fixture.containerCount());
        assertEquals(0, fixture.itemCount());
    }
}

@Test
void candidatesAreBoundedByWorldAndChunkRange() throws Exception {
    try (var fixture = RepositoryTestFixture.open(tempDir.resolve("bounds.db"))) {
        BlockKey inside = fixture.key(0, 64, 0);
        BlockKey outside = fixture.key(64, 64, 0);
        fixture.insertAvailable(inside, "diamond");
        fixture.insertAvailable(outside, "diamond");
        List<BlockKey> keys = fixture.repository().findCandidates(
            inside.worldId(), -1, 1, -1, 1).stream().map(RootIdentity::key).toList();
        assertEquals(List.of(inside), keys);
    }
}

@Test
void startupResetMakesPersistedChunksUnavailable() throws Exception {
    Path database = tempDir.resolve("restart.db");
    BlockKey root;
    try (var first = RepositoryTestFixture.open(database)) {
        root = first.key(0, 64, 0);
        first.insertAvailable(root, "diamond");
        assertFalse(first.repository().findCandidates(root.worldId(), 0, 0, 0, 0).isEmpty());
    }
    try (var second = RepositoryTestFixture.open(database)) {
        second.repository().markAllChunksUnavailable();
        assertTrue(second.repository().findCandidates(root.worldId(), 0, 0, 0, 0).isEmpty());
    }
}

@Test
void descriptorAndPathCodecsRoundTripUnicodeAndSortedMaps() throws Exception {
    ItemDescriptor descriptor = ItemDescriptor.builder()
        .materialKey("minecraft:diamond_pickaxe").amount(1)
        .addDisplayText("鉱山道具").addEnchantment("minecraft:mending", 1)
        .addScalarMetadata("custom:id", "example:miner").build();
    ItemPath path = new ItemPath(List.of(
        new ItemPathStep("Chest", 4), new ItemPathStep("Purple Shulker Box", 12)));
    assertEquals(descriptor, DescriptorCodec.decode(DescriptorCodec.encode(descriptor)));
    assertEquals(path, ItemPathCodec.decode(ItemPathCodec.encode(path)));
}

@Test
void providerVersionChangeReembedsStoredDescriptors() throws Exception {
    try (var fixture = RepositoryTestFixture.open(tempDir.resolve("reembed.db"))) {
        BlockKey root = fixture.key(0, 64, 0);
        fixture.repository().replaceRoot(fixture.snapshot(root, "diamond"), 1);
        EmbeddingProvider versionTwo = fixture.provider("builtin:sparse-v1", 2);
        fixture.repository().reembedAll(versionTwo);
        List<IndexedItem> items = fixture.repository()
            .loadDocuments(Set.of(root), versionTwo).get(root);
        assertEquals(2, items.getFirst().embedding().providerVersion());
        assertEquals("minecraft:diamond", items.getFirst().descriptor().materialKey());
    }
}
```

`RepositoryTestFixture` is a package-private helper in `SqliteIndexRepositoryTest`: `open(Path)` creates/migrates a real repository; `snapshot(BlockKey, String...)` creates fully encoded test documents through `SparseTagEmbeddingProvider`; `provider(String, int)` returns a deterministic test provider whose version is the supplied value; `insertAvailable` replaces the root and marks its `ChunkKey` available; count/material methods query the same JDBC file with prepared statements. Its methods contain no mocked JDBC behavior.

- [ ] **Step 2: Confirm repository tests fail**

Run:

```bash
./gradlew test --tests '*SqliteIndexRepositoryTest' --tests '*DescriptorCodecTest'
```

Expected: compilation fails because repository/codecs do not exist.

- [ ] **Step 3: Create the transactional schema**

`V1__initial.sql`:

```sql

CREATE TABLE IF NOT EXISTS schema_metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS chunks (
    world_uuid TEXT NOT NULL,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    available INTEGER NOT NULL CHECK (available IN (0, 1)),
    revision INTEGER NOT NULL,
    PRIMARY KEY (world_uuid, chunk_x, chunk_z)
);

CREATE TABLE IF NOT EXISTS containers (
    id INTEGER PRIMARY KEY,
    world_uuid TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    block_type TEXT NOT NULL,
    fingerprint BLOB NOT NULL,
    revision INTEGER NOT NULL,
    UNIQUE (world_uuid, x, y, z)
);

CREATE INDEX IF NOT EXISTS containers_by_chunk
ON containers (world_uuid, chunk_x, chunk_z);

CREATE TABLE IF NOT EXISTS items (
    container_id INTEGER NOT NULL,
    path BLOB NOT NULL,
    amount INTEGER NOT NULL CHECK (amount > 0),
    descriptor BLOB NOT NULL,
    provider_id TEXT NOT NULL,
    provider_version INTEGER NOT NULL,
    vector BLOB NOT NULL,
    vector_norm REAL NOT NULL CHECK (vector_norm >= 0.0),
    PRIMARY KEY (container_id, path),
    FOREIGN KEY (container_id) REFERENCES containers(id) ON DELETE CASCADE
);
```

Connection initialization executes `PRAGMA journal_mode=WAL` and `PRAGMA foreign_keys=ON` before beginning the migration transaction. Migration code then reads the resource as UTF-8, executes only the DDL statements in one transaction, records `schema_version=1`, and fails startup on an unknown newer version.

- [ ] **Step 4: Implement exact repository operations**

```java
public interface IndexRepository extends AutoCloseable {
    void migrate() throws SQLException;
    void markAllChunksUnavailable() throws SQLException;
    void setChunkAvailable(ChunkKey chunk, boolean available, long revision) throws SQLException;
    void replaceRoot(ContainerSnapshot snapshot, long revision) throws SQLException;
    void deleteRoot(BlockKey key) throws SQLException;
    List<RootIdentity> findCandidates(UUID worldId, int minChunkX, int maxChunkX,
                                      int minChunkZ, int maxChunkZ) throws SQLException;
    Map<BlockKey, List<IndexedItem>> loadDocuments(Set<BlockKey> allowed,
                                                    EmbeddingProvider provider) throws SQLException;
    void reembedAll(EmbeddingProvider provider) throws SQLException;
}
```

`replaceRoot` must start a transaction, upsert the container row, delete prior item rows, insert the complete new set, commit, and restore auto-commit. On any exception, rollback and preserve the prior committed root. `findCandidates` joins `containers` to `chunks` with `available=1` and returns identity/location only. `loadDocuments` is the only method that reads descriptors/vectors and accepts only already allowed root keys.
`reembedAll` streams stored descriptors in bounded batches of 256, embeds them with the selected provider, and updates `provider_id`, `provider_version`, `vector`, and `vector_norm` in one transaction per batch. Startup compares every row's provider ID/version with the configured provider and completes this re-embedding before marking a chunk available.

`DescriptorCodec` and `ItemPathCodec` use version-prefixed deterministic `DataInput/DataOutput` encodings with explicit entry and string-length bounds; no Java object serialization.

- [ ] **Step 5: Add the single-owner worker**

```java
public final class IndexWorker implements AutoCloseable {
    @FunctionalInterface
    public interface CheckedSupplier<T> { T get() throws Exception; }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
        Thread.ofPlatform().name("kitsune-index").unstarted(r));
    private final AtomicBoolean closed = new AtomicBoolean();
    private final IndexRepository repository;

    public <T> CompletableFuture<T> submit(CheckedSupplier<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Index worker is closed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return operation.get();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, executor);
    }

    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Index worker did not terminate");
                }
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        repository.close();
    }
}
```

Implement `close` as: atomic closed flag, `shutdown`, wait at most five seconds, `shutdownNow` if needed, then close the repository on the caller only after queued operations stop.

- [ ] **Step 6: Verify persistence contracts**

Run:

```bash
./gradlew test --tests '*SqliteIndexRepositoryTest' --tests '*DescriptorCodecTest'
```

Expected: migrations, transaction rollback, replacement, cascade, candidate bounds, availability reset, provider mismatch, and codec tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db src/main/java/dev/jlo/kitsune/index src/test/java/dev/jlo/kitsune/index
 git commit -m "Persist storage index in SQLite"
```

### Task 5: Describe Bukkit items and bounded nested contents

**Files:**
- Create: public item extension APIs and every file under “Item extraction”.
- Test: `src/test/java/dev/jlo/kitsune/item/NestedItemWalkerTest.java`
- Test: `src/test/java/dev/jlo/kitsune/item/TraversalLimitsTest.java`

**Interfaces:**
- Consumes: server-thread `ItemStack` calls and `ServicesManager` registrations.
- Produces: `BukkitItemDescriber.describe(ItemStack)`, built-in shulker/bundle child extraction, root-to-leaf paths, baseline fallback features, and hard traversal bounds. No `ItemStack` escapes the call.

- [ ] **Step 1: Write failing pure traversal tests**

Use an internal test node adapter to prove:

```java
@Test
void emitsChestShulkerLeafPathInSlotOrder() {
    TestTree pickaxe = TestTree.leaf("pickaxe");
    TestTree shulker = TestTree.container("shulker", new TestChild(12, pickaxe));
    var result = walker(4, 4096).walk(new TestChild(4, shulker));
    assertEquals(new ItemPath(List.of(
        new ItemPathStep("Chest", 4), new ItemPathStep("Shulker", 12))),
        result.leaves().getFirst().path());
}

@Test
void stopsAtDepthFourWithoutDroppingContainerItself() {
    TestTree nested = TestTree.chain(6);
    var result = walker(4, 4096).walk(new TestChild(0, nested));
    assertEquals(1, result.leaves().size());
    assertEquals(4, result.leaves().getFirst().path().steps().size());
    assertTrue(result.truncated());
}

@Test
void stopsAfter4096VisitedStacks() {
    TestTree root = TestTree.containerWithLeafCount("bundle", 5000);
    var result = walker(4, 4096).walk(new TestChild(0, root));
    assertEquals(4096, result.visitedStacks());
    assertTrue(result.truncated());
}

@Test
void providerExceptionFallsBackToContainerAsLeaf() {
    TestTree failing = TestTree.failingContainer("backpack");
    var result = walker(4, 4096).walk(new TestChild(2, failing));
    assertEquals("backpack", result.leaves().getFirst().descriptor().materialKey());
}

@Test
void repeatedFingerprintDoesNotLoop() {
    TestTree repeated = TestTree.repeatedFingerprintChain("same", 8);
    var result = walker(4, 4096).walk(new TestChild(0, repeated));
    assertEquals(2, result.visitedStacks());
    assertTrue(result.truncated());
}
```

`TestTree`, `TestChild`, and `walker(...)` are nested test fixtures implementing the walker's package-private generic `TraversalAdapter<TestTree>`: child order is the integer slot, descriptor material key is the node ID, fingerprint is the node fingerprint bytes, and a failing node throws from `children`. Production supplies the same adapter shape for `ItemStack`, keeping the traversal algorithm independently testable.

- [ ] **Step 2: Confirm traversal tests fail**

Run:

```bash
./gradlew test --tests '*NestedItemWalkerTest' --tests '*TraversalLimitsTest'
```

Expected: compilation fails on missing traversal types.

- [ ] **Step 3: Define extension contracts with server-thread ownership explicit**

```java
public interface ItemFeatureProvider {
    void contribute(ItemStack stack, ItemDescriptor.Builder descriptor);
}

public interface NestedContentsProvider {
    boolean supports(ItemStack stack);
    List<ChildItem> children(ItemStack stack);

    record ChildItem(String label, int slot, ItemStack stack) {}
}
```

Registry calls are synchronous on the server thread. Copy each returned child into the traversal immediately; reject null, air, negative slot, or an object retained after the registry call. Catch provider exceptions, rate-limit by provider class, and treat the container stack as an ordinary leaf.

- [ ] **Step 4: Implement complete baseline descriptor extraction**

`BukkitItemDescriber` must read on the server thread and immediately convert to immutable primitives:

- `stack.getType().getKey()` and amount;
- plain text from `ItemMeta.displayName()` and `ItemMeta.lore()` with Adventure `PlainTextComponentSerializer`;
- `getEnchants`, attribute modifiers, damage/unbreakable state, item flags;
- potion, book, firework, armor trim, and custom-model data when the corresponding meta type is present;
- PDC key names and only scalar `BYTE`, `INTEGER`, `LONG`, `FLOAT`, `DOUBLE`, or bounded `STRING` values;
- material traits from `Tag.ITEMS_SWORDS`, `ITEMS_AXES`, `ITEMS_PICKAXES`, `ITEMS_SHOVELS`, `ITEMS_HOES`, head/chest/leg/foot armor tags, plus safe `Material` properties.

Limit every text value to 256 Unicode code points, every collection to 256 entries, and PDC strings to 256 code points. Sort maps/sets before constructing the descriptor. Invoke registered custom feature providers after baseline fields so failure cannot erase fallback data.

- [ ] **Step 5: Implement built-in child providers and depth-first walker**

- `ShulkerContentsProvider` accepts `BlockStateMeta` whose copied state is `ShulkerBox`, then reads its snapshot inventory in slot order.
- `BundleContentsProvider` accepts `BundleMeta` and enumerates `getItems()` by list index.
- `NestedItemWalker` tracks depth, visited count, per-branch SHA-256 of `ItemStack.serializeAsBytes()`, and current `ItemPath`.
- At a bound or provider failure, emit the current container as the leaf. Otherwise emit only descendant leaves, preserving the container step in each path.
- The maximums come from `TraversalLimits(maximumDepth, maximumStacksPerRoot)`.

- [ ] **Step 6: Verify pure tests and Paper compilation**

Run:

```bash
./gradlew test --tests '*NestedItemWalkerTest' --tests '*TraversalLimitsTest'
./gradlew compileJava
```

Expected: all traversal tests pass and every Paper 1.21.4 item/meta call compiles. Live metadata extraction is exercised in Task 11 because no unverified Bukkit mock is allowed.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/jlo/kitsune/api/item src/main/java/dev/jlo/kitsune/item src/test/java/dev/jlo/kitsune/item
 git commit -m "Describe nested Bukkit item contents"
```

### Task 6: Discover and snapshot loaded storage roots

**Files:**
- Create: `RootResolver.java`, `ContainerSnapshotter.java`, `ContainerIndex.java`
- Test: `src/test/java/dev/jlo/kitsune/index/RootResolverTest.java`
- Test: `src/test/java/dev/jlo/kitsune/index/ContainerIndexQueueTest.java`

**Interfaces:**
- Consumes: `Chunk#getTileEntities(false)`, `BlockInventoryHolder`, item describer/walker, embedding provider, index worker.
- Produces: canonical root keys, deduplicated scan/snapshot queues, chunk readiness futures, and no block-volume scan.

- [ ] **Step 1: Write failing canonicalization and budget tests**

```java
@Test
void connectedChestHalvesChooseLexicographicallyLowerAnchor() {
    BlockKey west = key(0, 64, 0);
    BlockKey east = key(1, 64, 0);
    assertEquals(west, RootResolver.canonicalDoubleChest(east, west));
    assertEquals(west, RootResolver.canonicalDoubleChest(west, east));
}

@Test
void dirtyQueueDeduplicatesCanonicalRoot() {
    var harness = IndexQueueHarness.withBudgets(2, 8);
    BlockKey root = key(0, 64, 0);
    for (int i = 0; i < 10; i++) harness.markDirty(root);
    harness.tick();
    assertEquals(List.of(root), harness.snapshottedRoots());
}

@Test
void tickHonorsTwoChunkAndEightRootBudgets() {
    var harness = IndexQueueHarness.withBudgets(2, 8);
    harness.enqueueChunks(3);
    harness.enqueueRoots(10);
    harness.tick();
    assertEquals(2, harness.processedChunkCount());
    assertEquals(8, harness.processedRootCount());
    assertEquals(1, harness.pendingChunkCount());
    assertEquals(2, harness.pendingRootCount());
}

@Test
void readinessCompletesOnlyAfterDiscoveryWritesCommit() {
    var harness = IndexQueueHarness.withBudgets(2, 8);
    ChunkKey chunk = new ChunkKey(WORLD_ID, 0, 0);
    CompletionStage<Void> ready = harness.awaitReady(chunk);
    harness.discover(chunk, List.of(key(0, 64, 0)));
    assertFalse(ready.toCompletableFuture().isDone());
    harness.completeRootWrite(key(0, 64, 0));
    assertTrue(ready.toCompletableFuture().isDone());
}
```

Use pure `BlockKey`/queue fakes for budget tests. Isolate Paper-specific state resolution behind `RootResolver.LiveAccess` so canonical ordering is testable without MockBukkit.

`IndexQueueHarness` is a package-private fake around the production queue state machine. Its `discover` and `completeRootWrite` methods drive the same generation counters as `ContainerIndex`; it does not reproduce queue logic in the test.

- [ ] **Step 2: Confirm tests fail**

Run:

```bash
./gradlew test --tests '*RootResolverTest' --tests '*ContainerIndexQueueTest'
```

Expected: compilation fails on missing resolver/index queue.

- [ ] **Step 3: Implement capability-based root resolution**

On the server thread:

```java
for (BlockState state : chunk.getTileEntities(false)) {
    if (state instanceof BlockInventoryHolder holder) {
        rootResolver.resolve(holder).ifPresent(index::enqueueSnapshot);
    }
}
```

Rules:

- Reject player-scoped ender-chest and entity inventories.
- If the state is `Lootable` with a non-null unresolved loot table, return `UNRESOLVED_LOOT` without reading inventory contents.
- For a connected chest, inspect `DoubleChestInventory`/`DoubleChest` sides, require both chunks loaded, and return the lower coordinate as canonical anchor.
- For every other persistent `BlockInventoryHolder`, use its block coordinate directly.
- Never call `World#getChunkAt` or any API that loads a chunk.

- [ ] **Step 4: Implement server-thread snapshots and background commits**

`ContainerSnapshotter.snapshot(BlockKey)` must re-resolve the loaded block, obtain the complete logical inventory, traverse non-air stacks with Task 5, build immutable descriptors/paths, compute a SHA-256 fingerprint from ordered `ItemStack.serializeAsBytes()` values, and return a plain `ContainerDraft`.

`ContainerIndex` sends that plain snapshot to `IndexWorker`; the worker embeds descriptors and calls `replaceRoot`. A chunk readiness future completes only after discovery and all root replacement/deletion operations for that discovery generation complete.

- [ ] **Step 5: Add bounded initial discovery**

At enable, enqueue every already loaded chunk. On each tick, process at most configured `chunksPerTick` and `rootsPerTick`. Expose:

```java
CompletionStage<Void> awaitReady(Set<ChunkKey> chunks, Duration timeout);
void onChunkLoaded(Chunk chunk);
void onChunkUnloaded(ChunkKey chunk);
void markDirty(BlockKey root);
void delete(BlockKey root);
boolean isChunkReady(ChunkKey chunk);
```

`awaitReady` prioritizes the requested chunks, completes exceptionally with `IndexWarmupTimeoutException` after the configured duration, and never returns partial readiness as success.

- [ ] **Step 6: Verify queue contracts and live boot**

Run unit tests, then `./gradlew runServer`. Expected: startup logs a bounded count of discovered loaded chunks/roots, database schema exists under `run/plugins/Kitsune/`, and no `AsyncCatcher`/thread-access warning appears. Stop the server cleanly.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/jlo/kitsune/index src/test/java/dev/jlo/kitsune/index
 git commit -m "Index loaded storage roots"
```

### Task 7: Keep the persistent index current

**Files:**
- Create: `src/main/java/dev/jlo/kitsune/index/IndexListener.java`
- Modify: `ContainerIndex.java`, `KitsunePlugin.java`
- Test: `src/test/java/dev/jlo/kitsune/index/DirtyRootTrackerTest.java`
- Test: `src/test/java/dev/jlo/kitsune/index/ReconciliationCursorTest.java`

**Interfaces:**
- Consumes: Paper chunk/block/inventory/cook/brew/transfer/open events.
- Produces: one-tick-delayed dirty coalescing, root deletion, chunk availability, unresolved-loot activation, and budgeted periodic reconciliation.

- [ ] **Step 1: Write failing pure tracker tests**

```java
@Test
void repeatedEventsCoalesceIntoOneDelayedSnapshot() {
    var tracker = new DirtyRootTracker();
    BlockKey root = key(0, 64, 0);
    for (int i = 0; i < 10; i++) tracker.markDirty(root, 40);
    assertTrue(tracker.claimDue(40).isEmpty());
    assertEquals(List.of(new PendingRoot(root, 10, PendingAction.SNAPSHOT)),
        tracker.claimDue(41));
}

@Test
void deletionSupersedesSnapshotAndReplacementUsesNewGeneration() {
    var tracker = new DirtyRootTracker();
    BlockKey root = key(0, 64, 0);
    tracker.markDirty(root, 1);
    tracker.markDeleted(root, 1);
    PendingRoot deletion = tracker.claimDue(2).getFirst();
    assertEquals(PendingAction.DELETE, deletion.action());
    tracker.markDirty(root, 2);
    PendingRoot replacement = tracker.claimDue(3).getFirst();
    assertTrue(replacement.revision() > deletion.revision());
    assertEquals(PendingAction.SNAPSHOT, replacement.action());
}

@Test
void staleCompletionCannotReplaceNewerRevision() {
    var tracker = new DirtyRootTracker();
    BlockKey root = key(0, 64, 0);
    tracker.markDirty(root, 1);
    PendingRoot stale = tracker.claimDue(2).getFirst();
    tracker.markDirty(root, 2);
    assertFalse(tracker.complete(stale));
    assertFalse(tracker.claimDue(3).isEmpty());
}

@Test
void reconciliationCursorVisitsRoundRobinWithinBudget() {
    List<BlockKey> roots = List.of(key(0, 64, 0), key(1, 64, 0), key(2, 64, 0));
    var cursor = new ReconciliationCursor(roots);
    assertEquals(roots.subList(0, 2), cursor.next(2));
    assertEquals(List.of(roots.get(2)), cursor.next(2));
    assertTrue(cursor.complete());
}
```

Use exact records `PendingRoot(BlockKey key, long revision, PendingAction action)` and enum `PendingAction { SNAPSHOT, DELETE }`. `markDirty`/`markDeleted` increment the root revision and schedule work for `eventTick + 1`; `claimDue` returns deterministic key order; `complete` succeeds only when its revision remains current.

- [ ] **Step 2: Confirm tracker tests fail**

```bash
./gradlew test --tests '*DirtyRootTrackerTest' --tests '*ReconciliationCursorTest'
```

Expected: missing tracker/cursor types.

- [ ] **Step 3: Register exact event coverage**

At `EventPriority.MONITOR` with `ignoreCancelled=true` where cancellation exists:

- `ChunkLoadEvent` / `ChunkUnloadEvent` update discovery and availability.
- `BlockPlaceEvent`, `BlockBreakEvent`, `BlockExplodeEvent`, and `EntityExplodeEvent` insert/delete affected roots.
- `InventoryClickEvent` and `InventoryDragEvent` dirty top-inventory block holders one tick later.
- `InventoryMoveItemEvent` dirties source and destination; `InventoryPickupItemEvent` dirties destination.
- `FurnaceSmeltEvent`, `FurnaceBurnEvent`, `BrewEvent`, and crafter/block-cook events dirty the owning holder after commit.
- `InventoryOpenEvent` revisits an unresolved loot root one tick later and snapshots only after the loot table is resolved by normal gameplay.

Never read inventory contents inside the event callback. Resolve a canonical key and enqueue only.

- [ ] **Step 4: Implement revision-safe reconciliation**

Maintain a monotonically increasing revision per root. Each queued snapshot captures its revision; SQLite replacement occurs only if it is not older than the persisted revision. Every `reconciliationPeriodTicks`, begin a round-robin pass over known loaded roots and enqueue at most `rootsPerTick` per tick until the pass completes. Do not enumerate blocks again; use roots discovered from tile entities.

A snapshot or SQLite write failure retries only the newest root revision with backoff delays of 20, 100, then 200 ticks. After three failures, keep the prior committed SQLite revision, remove the retry, and rate-limit a root/type/error log entry; the next real event or reconciliation pass may enqueue a newer revision.

- [ ] **Step 5: Verify event-state contracts and restart behavior**

Run tests. Run Paper twice against the same `run/` directory; first create/update roots, then restart. Expected: startup calls `markAllChunksUnavailable`, loaded chunk discovery re-enables/reconciles roots, and no unloaded row is returned as a candidate.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/jlo/kitsune/index src/main/java/dev/jlo/kitsune/KitsunePlugin.java src/test/java/dev/jlo/kitsune/index
 git commit -m "Keep storage index synchronized"
```

### Task 8: Filter access and rank nearby results

**Files:**
- Create: public protection API, `ProtectionRegistry.java`, `SearchToken.java`, and all search files except command/UI/session-manager implementations.
- Test: `src/test/java/dev/jlo/kitsune/protection/ProtectionRegistryTest.java`
- Test: `src/test/java/dev/jlo/kitsune/search/SearchServiceTest.java`
- Test support: fake index worker, server-thread bridge, player/root access facade.

**Interfaces:**
- Consumes: ready chunks, identity-only candidate query, server-thread live roots/providers, allowed-ID document query, embedding provider.
- Produces: cancellable `SearchService.search(SearchContext, SearchRequest, SearchToken, SearchGuard)` and sorted/capped `SearchOutcome` with no inaccessible data.

- [ ] **Step 1: Write failing privacy and ranking tests**

```java
@Test
void denyWinsOverAllowRegardlessOfRegistrationOrder() {
    assertEquals(AccessDecision.DENY,
        ProtectionRegistry.combine(List.of(() -> AccessDecision.ALLOW, () -> AccessDecision.DENY)));
    assertEquals(AccessDecision.DENY,
        ProtectionRegistry.combine(List.of(() -> AccessDecision.DENY, () -> AccessDecision.ALLOW)));
}

@Test
void allNotApplicableAllowsOrdinaryRoot() {
    assertEquals(AccessDecision.ALLOW,
        ProtectionRegistry.combine(List.of(() -> AccessDecision.NOT_APPLICABLE)));
}

@Test
void providerExceptionFailsClosedForThatRoot() {
    assertEquals(AccessDecision.DENY,
        ProtectionRegistry.combine(List.of(() -> { throw new IllegalStateException("provider failed"); })));
}

@Test
void deniedIdsAreNeverPassedToLoadDocuments() {
    var harness = SearchHarness.withRoots(accessibleRoot(), deniedRoot());
    harness.deny(deniedRoot().key());
    harness.search("diamond");
    assertEquals(Set.of(accessibleRoot().key()), harness.loadedDocumentKeys());
}

@Test
void ranksByScoreThenDistanceThenCoordinates() {
    var harness = SearchHarness.withScoredRoots(
        scored(key(3, 64, 0), 0.9, 3.0),
        scored(key(2, 64, 0), 0.9, 2.0),
        scored(key(1, 64, 0), 0.9, 2.0));
    assertEquals(List.of(key(1, 64, 0), key(2, 64, 0), key(3, 64, 0)),
        harness.search("diamond").roots().stream().map(RootMatch::key).toList());
}

@Test
void countsAndTruncationExcludeDeniedRoots() {
    var harness = SearchHarness.withResultCap(1, accessibleRoot(), deniedRoot());
    harness.deny(deniedRoot().key());
    SearchOutcome outcome = harness.search("diamond");
    assertEquals(1, outcome.totalAccessibleMatchingRoots());
    assertEquals(1, outcome.roots().size());
}

@Test
void supersededTokenNeverCompletesAsRenderable() {
    var harness = SearchHarness.pausedAfterCandidateLoad(accessibleRoot());
    var first = harness.start("diamond");
    harness.supersede();
    harness.resume();
    assertEquals(SearchOutcome.Status.CANCELED, first.join().status());
}
```

`SearchHarness` uses the real `SearchService` with an immediate `ServerThreadBridge`, controllable futures around a fake `IndexRepository`, a recording `loadDocuments` method, deterministic embeddings, and a `SearchGuard` backed by an atomic generation. It never copies the production ranking/filter implementation.

- [ ] **Step 2: Confirm tests fail**

```bash
./gradlew test --tests '*ProtectionRegistryTest' --tests '*SearchServiceTest'
```

Expected: missing API/search types.

- [ ] **Step 3: Implement public protection contract and precedence**

```java
public enum AccessDecision { ALLOW, DENY, NOT_APPLICABLE }

public interface BlockAccessProvider {
    AccessDecision canAccess(Player player, Block block);
}
```

`ProtectionRegistry` obtains all `ServicesManager` registrations for `BlockAccessProvider`, invokes them on the server thread, returns denied immediately on any `DENY`, records whether any provider allowed, and allows when all return `NOT_APPLICABLE`. An exception is logged with provider name through a rate limiter and treated as `DENY` for that root.

- [ ] **Step 4: Implement explicit thread-hop search stages**

```java
public record SearchContext(UUID playerId, BlockKey origin) {}

public interface ServerThreadBridge {
    <T> CompletableFuture<T> supply(Callable<T> operation);
    CompletableFuture<Void> run(Runnable operation);
}

public interface SearchGuard {
    boolean isCurrent(SearchToken token);
}

public record SearchToken(UUID playerId, long generation) {}
```

`SearchService` performs these exact stages:

1. Calculate intersecting chunk keys without loading chunks.
2. Await `ContainerIndex.awaitReady`.
3. On `IndexWorker`, load candidate `RootIdentity` only.
4. On server thread, resolve player/world/block, apply exact 3D radius, canonical/fingerprint validation, and `ProtectionRegistry`; return allowed keys only.
5. On `IndexWorker`, load documents for the allowed key set, embed query, score leaves at `minimumScore`, keep `maxPathsPerRoot`, group by root, sort roots by score/distance/coordinates, and cap `maxResults`.
6. Return `SearchOutcome` with `totalAccessibleMatchingRoots` computed before cap and no denied data.

Check `SearchToken` before and after every stage. A zero-norm query returns `UnsupportedQuery`; warm-up timeout returns `IndexWarming`; database/provider error returns `Failure`; no match returns `NoMatches`. No failure includes protected root details.

The method signature is:

```java
CompletableFuture<SearchOutcome> search(
    SearchContext context,
    SearchRequest request,
    SearchToken token,
    SearchGuard guard);
```

- [ ] **Step 5: Verify staged privacy and ranking**

```bash
./gradlew test --tests '*ProtectionRegistryTest' --tests '*SearchServiceTest'
```

Expected: denied IDs never enter `loadDocuments`; all order/cancellation/error tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/jlo/kitsune/api/protection src/main/java/dev/jlo/kitsune/protection src/main/java/dev/jlo/kitsune/search src/test/java/dev/jlo/kitsune/protection src/test/java/dev/jlo/kitsune/search src/test/java/dev/jlo/kitsune/support
 git commit -m "Filter and rank nearby storage results"
```

### Task 9: Render private billboards, verbose trees, and sessions

**Files:**
- Create: `KitsuneCommand.java`, all session/UI files.
- Modify: `KitsunePlugin.java`
- Test: `src/test/java/dev/jlo/kitsune/ui/ChatTreeRendererTest.java`
- Test: `src/test/java/dev/jlo/kitsune/session/SearchSessionManagerTest.java`

**Interfaces:**
- Consumes: `SearchService`, `SearchOutcome`, config, Paper scheduler/entity APIs.
- Produces: complete player command, normal/verbose chat, owner-only `TextDisplay` markers, one-session invariant, 20-second cleanup.

- [ ] **Step 1: Write failing text and lifecycle tests**

Test exact observable strings:

```java
@Test
void normalOutputContainsOnlyAggregateAndMinimalMarker() {
    SearchOutcome outcome = UiFixtures.oneNestedMatch();
    String marker = plain(renderer.normalMarker(\"diamond sword\", outcome.roots().getFirst()));
    String chat = plain(renderer.normalChat(outcome));
    assertEquals(\"DIAMOND SWORD FOUND · 1 · 7m\", marker);
    assertEquals(\"Found 1 accessible storage block containing 1 matching stack.\", chat);
    assertFalse(marker.contains(\"0.91\"));
    assertFalse(chat.contains(\"12, 64, -8\"));
}

@Test
void verboseTreeShowsRootSlotsNestedSlotsAmountsScoresAndCoordinates() {
    String text = plain(renderer.verboseChat(\"mending\", UiFixtures.oneNestedMatch()));
    assertEquals(\"[1] Barrel @ 12, 64, -8 · score 0.91\\n\"
        + \"└─ slot 4: Purple Shulker Box x1\\n\"
        + \"   └─ slot 12: Diamond Pickaxe x1 · score 0.91\\n\"
        + \"      └─ Mending I\", text);
}

@Test
void newSessionRemovesOldMarkersBeforeAcceptingNewOnes() {
    var harness = SessionHarness.create();
    TestMarker old = harness.attachNewSession();
    harness.beginReplacement();
    assertTrue(old.removed());
}

@Test
void lateCompletionCannotAttachMarkersToReplacementSession() {
    var harness = SessionHarness.create();
    SearchToken oldToken = harness.begin();
    harness.begin();
    TestMarker late = new TestMarker();
    assertFalse(harness.attach(oldToken, late));
    assertTrue(late.removed());
}

@Test
void terminalTransitionsRemoveEveryMarker() {
    for (SessionHarness.Transition transition : SessionHarness.Transition.values()) {
        var harness = SessionHarness.create();
        TestMarker marker = harness.attachNewSession();
        harness.apply(transition);
        assertTrue(marker.removed(), transition.name());
    }
}
```

`UiFixtures.oneNestedMatch()` returns the exact approved barrel/shulker/pickaxe tree. `plain(Component)` uses `PlainTextComponentSerializer`. `RenderedMarker` has `UUID id()` and `void remove()`; `TestMarker` records removal. `SessionHarness` drives replacement, expiry, quit, world change, root invalidation, and disable through the real `SearchSessionManager` with a deterministic scheduler.

- [ ] **Step 2: Confirm tests fail**

```bash
./gradlew test --tests '*ChatTreeRendererTest' --tests '*SearchSessionManagerTest'
```

Expected: missing UI/session/command classes.

- [ ] **Step 3: Implement pure normal and verbose rendering**

Verbose marker text is bounded to the root type, best score, distance, and at most `maxPathsPerRoot` lines in `Container → nested container → leaf` form. If the component would exceed 1,024 code points, truncate complete path lines and append `…`; never split a Unicode code point or Adventure component.

Normal marker:

```text
<UPPERCASE NORMALIZED QUERY> FOUND · <QUALIFYING LEAF STACKS> · <ROUNDED DISTANCE>m
```

Normal chat:

```text
Found <root-count> accessible storage blocks containing <leaf-stack-count> matching stacks.
```

Verbose chat uses numbered roots and the approved branch form, caps paths at `maxPathsPerRoot`, and appends `Showing <cap> of <total> matching storage blocks` only when truncated. Use Adventure `Component`s; never concatenate legacy color codes.

- [ ] **Step 4: Spawn private displays safely**

Use one pre-spawn callback so visibility is false before tracking begins:

```java
TextDisplay display = world.spawn(location, TextDisplay.class, entity -> {
    entity.setVisibleByDefault(false);
    entity.setPersistent(false);
    entity.setGravity(false);
    entity.setBillboard(Display.Billboard.CENTER);
    entity.setSeeThrough(true);
    entity.text(component);
});
owner.showEntity(plugin, display);
```

Spawn at the canonical block center and 1.25 blocks above its top. Store entity UUIDs in the owning session. If `showEntity`, final access validation, or any spawn fails, remove the just-created entity and continue no further for that root. Never call `showEntity` for another player.

- [ ] **Step 5: Implement generation-token sessions and listeners**

`SearchSessionManager.begin(UUID)` removes prior entities and returns `SearchToken(playerId, generation)`. `attach(token, Collection<TextDisplay>)` succeeds only for the current generation; otherwise it removes the passed entities immediately. Schedule expiration at configured seconds. `PlayerQuitEvent`, `PlayerChangedWorldEvent`, root invalidation callback, and plugin disable call `clear(playerId)`/`clearAll()`.

- [ ] **Step 6: Wire command and plugin lifecycle**

Enable order:

1. Load/validate config and select `builtin:sparse-v1`.
2. Open/migrate SQLite; mark all chunks unavailable.
3. Register item/nested/protection services and listeners.
4. Start `ContainerIndex` ticks and enqueue loaded chunks.
5. Construct search/session/render services.
6. Set `/kitsune` executor.

Disable order: reject commands, cancel sessions/remove displays, cancel Bukkit tasks/listeners, stop index acceptance, close worker/repository.

`KitsuneCommand` must require `Player`, parse `SearchRequest`, begin a session, invoke search, and map outcomes. The no-match text is exactly `No accessible nearby storage matched <query>.`

- [ ] **Step 7: Verify tests and first real vertical slice**

Run all tests and start RunPaper. Join with a Minecraft 1.21.4 client. From console create a fixture near the player:

```text
setblock <x> <y> <z> minecraft:chest
item replace block <x> <y> <z> container.0 with minecraft:diamond_pickaxe[minecraft:enchantments={levels:{"minecraft:mending":1}}]
```

As player run `/kitsune diamond`. Expected: one minimal billboard over that chest and aggregate chat. Run `/kitsune --verbose mending`. Expected: expanded billboard and a chat tree with root slot, item, enchantment, score, and coordinates. Wait 20 seconds; expected: entity removed.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/dev/jlo/kitsune/command/KitsuneCommand.java src/main/java/dev/jlo/kitsune/session src/main/java/dev/jlo/kitsune/ui src/main/java/dev/jlo/kitsune/KitsunePlugin.java src/test/java/dev/jlo/kitsune/command src/test/java/dev/jlo/kitsune/session src/test/java/dev/jlo/kitsune/ui
 git commit -m "Render private nearby search billboards"
```

### Task 10: Add LWC and external protection compatibility

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/java/dev/jlo/kitsune/protection/LwcProtectionProvider.java`
- Modify: `KitsunePlugin.java`
- Test: `src/test/java/dev/jlo/kitsune/protection/LwcProtectionProviderContractTest.java`

**Interfaces:**
- Consumes: public `BlockAccessProvider`, Bukkit plugin detection, LWCX 2.4.2 API.
- Produces: a compile-only, soft-loaded LWC adapter plus a stable ServicesManager path for every other protection plugin.

**Compatibility boundary:** LWCX has a source-verified public artifact identity (`com.griefcraft:lwc:2.4.2`) and API. Per the final scope decision, Kitsune ships the generic provider SPI plus LWCX. Bolt and other systems integrate from separate bridge plugins through Bukkit `ServicesManager`; core does not guess their APIs or declare unsupported soft dependencies.

- [ ] **Step 1: Add the verified compile-only LWC dependency**

```kotlin
repositories {
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    compileOnly("com.griefcraft:lwc:2.4.2")
    testImplementation("com.griefcraft:lwc:2.4.2")
}
```

Keep `softdepend: [LWC]`; never shade LWC.

- [ ] **Step 2: Write the failing adapter contract test**

The contract test supplies an adapter seam around the static LWC instance and proves:

```java
@Test
void noProtectionIsNotApplicable() {
    var adapter = new LwcProtectionProvider(new FakeLwcAccess(null, true));
    assertEquals(AccessDecision.NOT_APPLICABLE, adapter.canAccess(PLAYER, BLOCK));
}

@Test
void accessibleProtectionAllows() {
    var adapter = new LwcProtectionProvider(new FakeLwcAccess(PROTECTION, true));
    assertEquals(AccessDecision.ALLOW, adapter.canAccess(PLAYER, BLOCK));
}

@Test
void inaccessibleProtectionDenies() {
    var adapter = new LwcProtectionProvider(new FakeLwcAccess(PROTECTION, false));
    assertEquals(AccessDecision.DENY, adapter.canAccess(PLAYER, BLOCK));
}

@Test
void apiFailureDenies() {
    var adapter = new LwcProtectionProvider(new ThrowingLwcAccess());
    assertEquals(AccessDecision.DENY, adapter.canAccess(PLAYER, BLOCK));
}
```

`LwcProtectionProvider.LwcAccess` is a package-private seam with `Object findProtection(Block)` and `boolean canAccess(Player, Object)`. The production implementation casts the opaque value to LWCX `Protection`; test fakes use an ordinary sentinel object, so tests need no Bukkit mock implementation.

- [ ] **Step 3: Implement the adapter with direct API calls**

`LwcProtectionProvider.canAccess` obtains `LWC.getInstance()`, calls `findProtection(block)`, returns `NOT_APPLICABLE` when null, otherwise returns `ALLOW` only when `canAccessProtection(player, protection)` is true. No database read or owner-name comparison is allowed because LWC hooks may modify access.

At enable, if plugin `LWC` is present and enabled, construct/register the adapter with `ServicesManager`. If class linkage or initialization fails while LWC is present, disable Kitsune search and log the exact incompatibility; do not continue with unfiltered results.

- [ ] **Step 4: Verify no-provider and LWC-enabled boots**

Run tests and RunPaper without LWC: Kitsune must enable and ordinary roots remain searchable. Add a compatible LWCX 2.4.2 JAR to the RunPaper plugin download/configuration, restart, protect one fixture chest, and verify its root contributes no count, chat, coordinate, score, or marker to a non-member's search.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src/main/java/dev/jlo/kitsune/protection/LwcProtectionProvider.java src/main/java/dev/jlo/kitsune/KitsunePlugin.java src/test/java/dev/jlo/kitsune/protection/LwcProtectionProviderContractTest.java
 git commit -m "Filter LWC-protected storage"
```

### Task 11: Exercise the full RunPaper acceptance matrix

**Files:**
- Modify only files whose observed behavior fails an approved contract.
- Do not create production debug commands or fixture code.

**Interfaces:**
- Consumes: the complete plugin and Paper 1.21.4 server.
- Produces: observed proof for startup, indexing, ranking, nesting, privacy, freshness, persistence, and cleanup.

- [ ] **Step 1: Run static verification under the provisioned Java 21 toolchain**

```bash
./gradlew clean test shadowJar
./gradlew javaToolchains
```

Expected: all tests pass, Shadow JAR builds, and output lists a Java 21 toolchain used for compile/test/RunPaper. Inspect the JAR: it contains `org/sqlite/JDBC.class`, plugin resources, and no Paper/LWC classes.

- [ ] **Step 2: Start Paper 1.21.4 through RunPaper**

```bash
./gradlew runServer
```

Expected: Paper reports Minecraft 1.21.4 on Java 21, Kitsune migrates SQLite, marks stale chunks unavailable, discovers loaded tile entities without block-volume scans, and registers `/kitsune`.

- [ ] **Step 3: Build a representative live fixture through vanilla commands**

Create nearby chest, barrel, hopper/furnace, double chest, placed shulker, unresolved loot root if available without forcing loot, a chest containing a shulker with an enchanted diamond pickaxe, and a bundle. Keep another matching root beyond 32 blocks and another in an unloaded chunk.

Expected searches:

- `diamond pickaxe` finds exact and nested items.
- `mining tool` ranks pickaxes above unrelated items.
- `mending` finds enchanted metadata.
- A custom-named/PDC scalar item remains searchable by its baseline/custom text.
- Opaque custom container contents do not appear without a provider.
- Beyond-radius and unloaded roots do not appear.

- [ ] **Step 4: Prove two-player marker privacy**

Join two Minecraft 1.21.4 clients. Player A searches. Verify A sees every owner display and Player B sees none at the same coordinates. Then let B perform a different query and confirm each player sees only their own markers. This is required evidence; `Player.canSee` alone is not a substitute for the second-client observation.

- [ ] **Step 5: Prove query-level protection privacy**

Register the test denial provider in a test-only RunPaper plugin or install the verified LWC adapter. For a denied matching root, verify the accessible count, truncation total, chat, verbose tree, logs sent to player, coordinates, score, and markers are indistinguishable from the root not existing.

- [ ] **Step 6: Prove freshness and lifecycle cleanup**

Move items by click, drag, hopper, furnace/brew/crafter where applicable; break/place/explode a root; open unresolved loot through normal gameplay. After the delayed dirty update, results must reflect committed contents. Start a second search before the first finishes, change world, quit, wait 20 seconds, and stop the plugin; after each terminal transition, inspect the world entity list and confirm no Kitsune display remains.

- [ ] **Step 7: Prove restart persistence without stale rendering**

Stop and restart the same RunPaper directory. Confirm SQLite rows persist, every chunk starts unavailable, loaded roots reconcile, unloaded rows never render, and a changed/removed root is repaired before becoming a result.

- [ ] **Step 8: Run final verification once after live fixes**

```bash
./gradlew clean test shadowJar
```

Expected: `BUILD SUCCESSFUL`; no failing tests. Restart RunPaper once more and repeat the smallest vertical slice: one loaded root, `/kitsune diamond`, one owner-only billboard, 20-second cleanup.

- [ ] **Step 9: Commit only if live verification required a code change**

For each independent observed defect, add its regression test and commit the test plus fix together with a message naming that behavior. If no code changed, create no verification-only commit.

## Completion Gate

Implementation is complete only when all eleven task checkpoints pass, the full revised spec coverage is observed, and there are no remaining marker entities, open SQLite handles, unfinished index jobs, or uncommitted implementation changes. The generic `BlockAccessProvider` SPI and source-verified LWCX adapter are the built-in compatibility boundary; Bolt and every other provider use external bridge registration.
