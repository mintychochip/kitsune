# Kitsune Multi-Platform Modules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the single Paper plugin into a Gradle multi-project build with a neutral Maven API, a platform-independent common engine, and usable 1.21.4 Paper, Spigot, Fabric, Forge, and NeoForge artifacts.

**Architecture:** `api` owns immutable neutral contracts and data. `common` owns the current indexing/search/persistence engine behind neutral ports. An internal `platform:bukkit` bridge shares only Spigot-compatible code between the separate Paper and Spigot entrypoints; Fabric, Forge, and NeoForge implement native adapters independently. Platform runtime jars embed common implementation code while `kitsune-api` remains the stable compile-time artifact.

**Tech Stack:** Gradle Kotlin DSL multi-project build, Java 21, JUnit 5, SQLite JDBC, Paper/Spigot 1.21.4 APIs, Fabric Loader/API, Forge, NeoForge, Maven Publish, Shadow.

## Global Constraints

- Target Minecraft `1.21.4` and Java `21` for the first modular release.
- `api` compiles without Bukkit, Paper, Adventure, Fabric, Forge, NeoForge, or loader classes.
- `common` compiles without any platform API and depends on `api` plus persistence/runtime-neutral libraries only.
- Shared Bukkit code compiles against the selected Spigot API; Paper-only APIs remain in `paper`.
- Preserve nearby-storage indexing, nested traversal, access filtering, persistence, search ranking, sessions, and lifecycle behavior.
- Platform capability gaps are explicit and logged; required-port or startup failures stop that platform rather than becoming silent no-ops.
- Platform runtime jars are self-contained; no credentials or repository URLs are hard-coded.
- Existing tests move with their owning module and behavior tests remain green.
- Each coherent change is committed atomically with a focused imperative message.

---

## File map before editing

The current root `build.gradle.kts` is a single Java/Paper/Shadow/Run-Paper project. `settings.gradle.kts` names only `kitsune`. Production sources are under `src/main/java/dev/jlo/kitsune`; tests are under `src/test/java/dev/jlo/kitsune`.

Current neutral candidates to move to `api`:

- `src/main/java/dev/jlo/kitsune/model/*.java`
- `src/main/java/dev/jlo/kitsune/api/embedding/*.java`
- `src/main/java/dev/jlo/kitsune/api/item/*.java`
- `src/main/java/dev/jlo/kitsune/api/protection/*.java`
- Tests under `src/test/java/dev/jlo/kitsune/model`, plus API-provider tests that are added during Task 2.

Current common candidates to move to `common` unchanged or with neutral port injection:

- `config/KitsuneConfig.java` and neutral configuration validation
- `embedding/*.java`
- `index/*.java` except `ContainerIndex.java`, `IndexListener.java`, `ContainerSnapshotter.java`, and platform event/access classes identified in Task 3
- `item/NestedItemWalker.java`, `item/TraversalAdapter.java`, `item/TraversalChild.java`, `item/NestedItemWalkResult.java`, `item/TraversalLimits.java`, `item/ItemFeatureRegistry.java` after neutralizing its provider registry
- `search/IndexReadiness.java`, `ItemMatch.java`, `LiveRootAccess.java`, `RootMatch.java`, `SearchContext.java`, `SearchGuard.java`, `SearchOutcome.java`, `SearchPolicy.java`, `SearchService.java`, `ServerThreadBridge.java`, `AllowedRoot.java`, and neutral `RootResolver.java`
- `session/SearchSessionManager.java`, `SearchToken.java`, `SessionScheduler.java`, `SessionTask.java`; `SessionListener.java` remains platform-specific
- `command/SearchRequest.java` and the neutral command/service coordinator extracted from `KitsuneCommand.java`
- `ui/RenderedMarker.java` and corresponding session marker tests

Current platform-specific candidates:

- `KitsunePlugin.java`
- `ui/*.java`
- `command/KitsuneCommand.java` native registration/rendering portions
- `session/BukkitSessionScheduler.java`
- `search/BukkitLiveRootAccess.java`, `search/BukkitServerThreadBridge.java`
- `item/BukkitTraversalAdapter.java`, `item/BukkitItemDescriber.java`, `item/BundleContentsProvider.java`, `item/ShulkerContentsProvider.java`
- `protection/LwcProtectionProvider.java`, `protection/ProtectionRegistry.java` native registration portions
- `config/ConfigLoader.java`
- `session/SessionListener.java`
- `index/ContainerIndex.java`, `IndexListener.java`, and `ContainerSnapshotter.java`, plus their Paper-specific portions.

---

## Task 1: Establish the multi-project Gradle build

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `gradle.properties`
- Create: `api/build.gradle.kts`
- Create: `common/build.gradle.kts`
- Create: `platform/bukkit/build.gradle.kts`
- Create: `paper/build.gradle.kts`
- Create: `spigot/build.gradle.kts`
- Create: `fabric/build.gradle.kts`
- Create: `forge/build.gradle.kts`
- Create: `neoforge/build.gradle.kts`
- Create: `gradle/libs.versions.toml` for shared plugin and dependency coordinates once the 1.21.4-compatible versions are verified
- Test: Gradle project graph and empty-module compilation

**Interfaces:**
- Produces Gradle projects named `:api`, `:common`, `:platform:bukkit`, `:paper`, `:spigot`, `:fabric`, `:forge`, and `:neoforge`.
- `:common` consumes `project(":api")`.
- `:paper`, `:spigot`, `:fabric`, `:forge`, and `:neoforge` consume `project(":common")` and `project(":api")` plus only their own native dependencies.
- The root project owns shared Java, repository, version, test, publishing, and quality conventions but no production sources after Task 3.

- [ ] **Step 1: Write the failing build-boundary check**

Create `api/src/test/java/dev/jlo/kitsune/api/ApiDependencyBoundaryTest.java` and `common/src/test/java/dev/jlo/kitsune/common/CommonDependencyBoundaryTest.java` in their module source trees before the project graph is wired. The tests load class names from their own classpaths and assert no class under `org.bukkit.`, `io.papermc.`, `net.fabricmc.`, `net.minecraftforge.`, `net.neoforged.`, or `net.minecraft.` is present as a declared dependency. Keep the assertion limited to classpath entries supplied to the module test task so it does not inspect the host Gradle daemon.

```java
@Test
void apiHasNoPlatformDependency() {
    assertFalse(System.getProperty("java.class.path").matches(".*(paper-api|spigot-api|fabric|forge|neoforge|minecraft).*"));
}
```

Use a Gradle dependency-resolution assertion as the authoritative check; the JUnit assertion is only a readable failure in the owning module.

- [ ] **Step 2: Run the boundary check before adding the build graph**

Run `./gradlew :api:test :common:test`.

Expected: FAIL because the projects and test tasks do not exist.

- [ ] **Step 3: Add project includes and centralized versions**

In `settings.gradle.kts`, retain the existing plugin repositories and add:

```kotlin
include(":api", ":common", ":platform:bukkit", ":paper", ":spigot", ":fabric", ":forge", ":neoforge")
project(":platform:bukkit").projectDir = file("platform/bukkit")
```

Move shared `group`, `version`, Java 21 toolchain, UTF-8/release settings, JUnit platform, Maven Central/Paper/CodeMC repositories, and `maven-publish` conventions into the root build script using `subprojects { ... }`. Keep Paper run-server configuration only on `:paper`. Put the 1.21.4 baseline and each platform dependency version in `gradle/libs.versions.toml` or Gradle properties after verifying the coordinates resolve from the configured repositories; do not leave a second untracked version literal in a module script.

- [ ] **Step 4: Add module build scripts with the dependency direction**

`api/build.gradle.kts` applies `java-library`, `maven-publish`, and sources/javadoc packaging with no native dependency. `common/build.gradle.kts` applies `java-library` and depends on `api`, SQLite JDBC, and neutral test libraries. `platform/bukkit/build.gradle.kts` depends on `api` and the Spigot API as `compileOnly`. The five target modules depend on `common`, `api`, and their selected native platform dependencies; Paper and Spigot also depend on `platform:bukkit`.

Use `compileOnly` for native APIs and a platform-specific shadow configuration for the runtime jar. Keep platform-specific Gradle plugins and metadata inside their module scripts; do not apply Fabric Loom, ForgeGradle, or NeoForgeGradle to `api` or `common`.

- [ ] **Step 5: Run project and module compilation**

Run `./gradlew projects :api:test :common:test`.

Expected: all eight projects are listed, empty module test tasks pass, and no API/common dependency includes a native platform artifact.

- [ ] **Step 6: Commit the build graph**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/libs.versions.toml api common platform paper spigot fabric forge neoforge
git diff --cached --check
git commit -m "Create multi-platform Gradle modules"
```

---

## Task 2: Move and neutralize the public API

**Files:**
- Move: `src/main/java/dev/jlo/kitsune/model/*.java` -> `api/src/main/java/dev/jlo/kitsune/model/`
- Move: `src/main/java/dev/jlo/kitsune/api/embedding/*.java` -> `api/src/main/java/dev/jlo/kitsune/api/embedding/`
- Move: `src/main/java/dev/jlo/kitsune/api/item/*.java` -> `api/src/main/java/dev/jlo/kitsune/api/item/`
- Modify: `api/src/main/java/dev/jlo/kitsune/api/protection/BlockAccessProvider.java`
- Modify: `api/src/main/java/dev/jlo/kitsune/api/item/NestedContentsProvider.java`
- Modify: `api/src/main/java/dev/jlo/kitsune/api/item/ItemFeatureProvider.java`
- Create: `api/src/main/java/dev/jlo/kitsune/api/protection/AccessContext.java`
- Move/adapt: model and API tests into `api/src/test/java/dev/jlo/kitsune/`
- Test: `api/src/test/java/dev/jlo/kitsune/api/PlatformNeutralApiTest.java`

**Interfaces:**

```java
public record AccessContext(UUID playerId, String playerName, BlockKey block) {
    public AccessContext {
        Objects.requireNonNull(playerId, "Player ID must not be null");
        if (playerName == null || playerName.isBlank()) {
            throw new IllegalArgumentException("Player name must not be blank");
        }
        Objects.requireNonNull(block, "Block must not be null");
    }
}

public interface BlockAccessProvider {
    AccessDecision canAccess(AccessContext context);
}

public interface ItemFeatureProvider {
    void contribute(ItemDescriptor item, ItemDescriptor.Builder descriptor);
}

public interface NestedContentsProvider {
    boolean supports(ItemDescriptor item);
    List<ChildItem> children(ItemDescriptor item);

    record ChildItem(String label, int slot, ItemDescriptor item) {
        public ChildItem {
            if (label == null || label.isBlank()) throw new IllegalArgumentException("Label must not be blank");
            if (slot < 0) throw new IllegalArgumentException("Slot must not be negative");
            Objects.requireNonNull(item, "Item must not be null");
        }
    }
}
```

- [ ] **Step 1: Write API tests that expose the current leak**

Create `PlatformNeutralApiTest` with tests that inspect every public method and record component in `dev.jlo.kitsune.api` and assert its type package is not a platform package. Add behavior tests for `AccessContext` validation and for neutral `ChildItem` validation. Use reflection only for the boundary; existing model tests continue to test actual value behavior.

- [ ] **Step 2: Run the API tests before neutralization**

Run `./gradlew :api:test`.

Expected: FAIL because the current API contracts mention `Player`, `Block`, `ItemStack`, and the old source set is not yet in `:api`.

- [ ] **Step 3: Move the model and contract sources without changing neutral model semantics**

Move the model files into the API source set, preserve their public package names to avoid needless consumer churn, and update imports in API contracts and tests. Keep defensive array copies, bounded descriptor construction, record validation, and equality semantics unchanged.

- [ ] **Step 4: Replace native signatures with neutral signatures**

Change `BlockAccessProvider` to accept `AccessContext`. Change item extension contracts to receive the neutral descriptor and return neutral child descriptors. Remove all Bukkit imports from API sources. If the existing `ItemDescriptor.Builder` is too mutable for a public provider input, add a package-stable read-only `ItemDescriptorView` and make the builder an output sink; do not expose a native object as an alternative.

- [ ] **Step 5: Run API tests and inspect dependencies**

Run `./gradlew :api:test` and `./gradlew :api:dependencies --configuration runtimeClasspath`.

Expected: all API tests pass and the runtime dependency report contains only Java/platform-neutral libraries.

- [ ] **Step 6: Commit the public API**

```bash
git add api src/main/java/dev/jlo/kitsune/model src/main/java/dev/jlo/kitsune/api src/test/java/dev/jlo/kitsune/model src/test/java/dev/jlo/kitsune/api
git diff --cached --check
git commit -m "Expose platform-neutral Kitsune API"
```

---

## Task 3: Move the common engine behind neutral ports

**Files:**
- Move: `src/main/java/dev/jlo/kitsune/config/KitsuneConfig.java`, `embedding`, `index/*.java` except `ContainerIndex.java`, `IndexListener.java`, and `ContainerSnapshotter.java`, `search`, `session`, neutral `ui/RenderedMarker.java`, and neutral `command`/`item`/`protection` files -> `common/src/main/java/dev/jlo/kitsune/`
- Move: corresponding tests under `src/test/java/dev/jlo/kitsune/` -> `common/src/test/java/dev/jlo/kitsune/`
- Create: `common/src/main/java/dev/jlo/kitsune/runtime/PlatformRuntime.java`
- Create: `common/src/main/java/dev/jlo/kitsune/runtime/PlatformCapabilities.java`
- Create: `common/src/main/java/dev/jlo/kitsune/runtime/ChangeListener.java`
- Create: `common/src/main/java/dev/jlo/kitsune/command/SearchCommandHandler.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/index/RootResolver.java` to remove the native factory and retain generic resolution logic
- Modify: `common/src/main/java/dev/jlo/kitsune/search/LiveRootAccess.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/item/ItemFeatureRegistry.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/protection/ProtectionRegistry.java`
- Test: common fake-port fixtures and `CommonEngineContractTest`

**Interfaces:**

```java
public interface SearchCommandHandler {
    CompletionStage<SearchOutcome> execute(SearchContext context, SearchRequest request);
}

public interface PlatformRuntime extends AutoCloseable {
    Path dataDirectory();
    ServerThreadBridge serverThread();
    PlatformCapabilities capabilities();
    void registerChangeListener(ChangeListener listener);
    void unregisterChangeListener(ChangeListener listener);
    void registerSearchCommand(SearchCommandHandler command);
    void close();
}

public interface ChangeListener {
    void markDirty(BlockKey block);
    void markChunkDirty(ChunkKey chunk);
}

public interface LiveRootAccess {
    Set<ChunkKey> loadedChunks(SearchContext context, int radius);
    AllowedRoot validate(SearchContext context, RootIdentity identity, int radius);
}
```

`ContainerSnapshotter` and `RootResolver` must consume neutral `LiveRootAccess`/snapshot ports; native inventory generics are removed from common. `ItemFeatureRegistry` stores public neutral providers. `ProtectionRegistry` stores public neutral access providers and evaluates `AccessContext` values. `KitsuneConfig`, SQLite repositories, workers, search service, session manager, and traversal limits remain common-owned.

- [ ] **Step 1: Add a common fake-port contract test**

Create `common/src/test/java/dev/jlo/kitsune/common/CommonEngineContractTest.java` with deterministic fake implementations of `ServerThreadBridge`, `LiveRootAccess`, an in-memory `IndexRepository`, and a fixed `EmbeddingProvider`. Assert that a search returns the same ranked roots/items for two platform-independent fixtures, that denied roots never reach result loading, and that closing the runtime stops the session scheduler and repository.

- [ ] **Step 2: Run the common contract test before extraction**

Run `./gradlew :common:test`.

Expected: FAIL because the engine source and test fixtures are still in the root source set and the new common runtime ports do not exist.

- [ ] **Step 3: Move pure sources and tests into `common`**

Move source files by responsibility, not by import count. Leave native files listed in the platform task untouched. Update package/import references only as required by the new module source roots. Keep engine packages stable where possible so the relocation does not mix a package rename with behavior changes.

- [ ] **Step 4: Replace native generic ports with neutral snapshots**

Refactor `ContainerSnapshotter`, `RootResolver`, and any `SearchService` callers so common receives `ContainerSnapshot`, `IndexedItem`, `ItemDescriptor`, `RootIdentity`, `BlockKey`, and `ChunkKey`, never `Inventory`, `ItemStack`, `World`, or `Server`. Preserve the existing revision/fingerprint and cancellation checks. Make `PlatformCapabilities` an immutable value that reports named support flags and does not contain native classes.

- [ ] **Step 5: Move provider registries to API-shaped inputs**

Update `ItemFeatureRegistry` and `ProtectionRegistry` to register/unregister neutral providers. A provider exception is captured and reported as a failed contribution/access decision according to existing error policy; it is not swallowed. Add tests for registration order, duplicate IDs where applicable, provider failure, and provider removal.

- [ ] **Step 6: Run common tests and dependency checks**

Run `./gradlew :common:test :common:dependencies --configuration compileClasspath`.

Expected: all moved index/search/config/session/embedding tests pass, and the common compile classpath contains no Paper, Spigot, Bukkit, Fabric, Forge, NeoForge, or Minecraft artifact.

- [ ] **Step 7: Commit the common engine extraction**

```bash
git add common src/main/java/dev/jlo/kitsune src/test/java/dev/jlo/kitsune
# Exclude files assigned to platform tasks from this commit.
git diff --cached --check
git commit -m "Extract platform-independent Kitsune engine"
```

---

## Task 4: Implement the shared Bukkit bridge and Paper/Spigot modules

**Files:**
- Move: `src/main/java/dev/jlo/kitsune/search/BukkitLiveRootAccess.java` -> `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/`
- Move: `src/main/java/dev/jlo/kitsune/search/BukkitServerThreadBridge.java` -> `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/`
- Move/adapt: `src/main/java/dev/jlo/kitsune/item/BukkitTraversalAdapter.java`, `BundleContentsProvider.java` -> `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/item/`
- Move/adapt: `src/main/java/dev/jlo/kitsune/session/BukkitSessionScheduler.java` -> `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/session/`
- Move/adapt: `src/main/java/dev/jlo/kitsune/session/SessionListener.java` -> `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/session/BukkitSessionListener.java`
- Move/adapt: `src/main/java/dev/jlo/kitsune/config/ConfigLoader.java` -> `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/config/BukkitConfigLoader.java`
- Move/adapt: `src/main/java/dev/jlo/kitsune/index/ContainerIndex.java` -> `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/index/BukkitContainerIndex.java`
- Move/adapt: `src/main/java/dev/jlo/kitsune/index/ContainerSnapshotter.java` -> `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/index/`; create `BukkitRootResolver.java` for the native live-access factory
- Create: `paper/src/main/java/dev/jlo/kitsune/paper/PaperPlugin.java`
- Create: `spigot/src/main/java/dev/jlo/kitsune/spigot/SpigotPlugin.java`
- Create: `paper/src/main/java/dev/jlo/kitsune/paper/PaperCapabilities.java`
- Create: `spigot/src/main/java/dev/jlo/kitsune/spigot/SpigotCapabilities.java`
- Split: `src/main/java/dev/jlo/kitsune/item/BukkitItemDescriber.java` into shared Bukkit-safe and Paper-only describers
- Split: `src/main/java/dev/jlo/kitsune/item/ShulkerContentsProvider.java` into shared safe and Paper capability implementations
- Split: `src/main/java/dev/jlo/kitsune/index/IndexListener.java` into common dirty-root sink, Bukkit-safe event handlers, and Paper-only handlers
- Move/adapt: `src/main/java/dev/jlo/kitsune/protection/LwcProtectionProvider.java`, `ProtectionRegistry.java` into Bukkit/Paper or Spigot modules according to dependency use
- Create: `paper/src/main/resources/plugin.yml`
- Create: `spigot/src/main/resources/plugin.yml`
- Move/adapt: `src/main/java/dev/jlo/kitsune/KitsunePlugin.java`, `command/KitsuneCommand.java`, `ui/*.java` into the two entrypoint modules and shared neutral command/session coordinators
- Tests: Bukkit adapter fixtures, Paper capability tests, Spigot compile boundary tests, plugin metadata tests

**Interfaces:**

```java
public final class BukkitRuntime implements PlatformRuntime {
    public BukkitRuntime(JavaPlugin plugin, Server server, Path dataDirectory, boolean paperCapabilities) { ... }
    public void start() { ... }
    @Override public void close() { ... }
}

public final class PaperPlugin extends JavaPlugin {
    private BukkitRuntime runtime;
    @Override public void onEnable() { runtime = PaperBootstrap.start(this); }
    @Override public void onDisable() { if (runtime != null) runtime.close(); }
}

public final class SpigotPlugin extends JavaPlugin {
    private BukkitRuntime runtime;
    @Override public void onEnable() { runtime = SpigotBootstrap.start(this); }
    @Override public void onDisable() { if (runtime != null) runtime.close(); }
}
```

- [ ] **Step 1: Write Bukkit adapter contract tests**

Add tests for `BukkitServerThreadBridge` scheduling/completion, `BukkitSessionScheduler` cancellation, block-to-`BlockKey` conversion, inventory snapshot conversion, and plugin shutdown. Use existing fake/test server fixtures where available; do not make the common test suite depend on Bukkit.

- [ ] **Step 2: Run the adapter tests before splitting Paper code**

Run `./gradlew :platform:bukkit:test :paper:test :spigot:test`.

Expected: FAIL because the shared bridge and target entrypoints do not yet exist.

- [ ] **Step 3: Extract shared Bukkit-safe runtime code**

Move the server-thread bridge, scheduler, live-root access, Bukkit-safe item/container conversions, safe event listeners, and LWC registration into `platform:bukkit`. Compile this project against Spigot API only. Convert every native object to neutral common values before crossing into common.

- [ ] **Step 4: Isolate Paper-only behavior**

Move `PersistentDataContainerView`, `RegistryAccess`, `RegistryKey`, `TileStateInventoryHolder`, `CrafterCraftEvent`, and other Paper-only references into `paper`. Use Spigot-safe APIs for persistent data, trim keys, shulker snapshots, and event coverage in the shared bridge. Expose missing Paper behavior through `PlatformCapabilities` rather than adding a Paper import to shared code.

- [ ] **Step 5: Add separate lifecycle and metadata**

Create Paper and Spigot entrypoints that construct the common engine with their own runtime and register/unregister listeners exactly once. Give both `plugin.yml` files the same command, permission, version expansion, and optional LWC dependency; keep the main class and API version valid for the selected server API. Paper run-server support remains on `:paper` only.

- [ ] **Step 6: Run compilation and targeted smoke tests**

Run `./gradlew :platform:bukkit:test :paper:test :spigot:test :paper:shadowJar :spigot:shadowJar` and inspect both jars for the correct plugin metadata. Run the existing Paper smoke server task for startup/shutdown and execute the Spigot adapter fixture without a Paper class on its classpath.

Expected: shared Bukkit code compiles against Spigot, Paper-only symbols occur only in `paper`, and both runtime jars initialize/close the common engine.

- [ ] **Step 7: Commit Bukkit, Paper, and Spigot support**

```bash
git add platform/bukkit paper spigot src/main/java/dev/jlo/kitsune src/test/java/dev/jlo/kitsune
# Stage only the Bukkit/Paper/Spigot paths from the moved source set.
git diff --cached --check
git commit -m "Add Paper and Spigot platform integrations"
```

---

## Task 5: Implement the Fabric module

**Files:**
- Create: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricMod.java`
- Create: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricRuntime.java`
- Create: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricItemAccess.java`
- Create: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricWorldAccess.java`
- Create: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricConfigLoader.java`
- Create: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricSessionListener.java`
- Create: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricChangeListener.java`
- Create: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricCommand.java`
- Create: `fabric/src/main/resources/fabric.mod.json`
- Create: `fabric/src/main/resources/assets/kitsune/lang/en_us.json` if native translations are used
- Test: `fabric/src/test/java/dev/jlo/kitsune/fabric/FabricAdapterContractTest.java`

**Interfaces:**

`FabricRuntime` implements `PlatformRuntime`; `FabricItemAccess` converts Fabric `ItemStack` and NBT into `ItemDescriptor` and neutral nested children; `FabricWorldAccess` implements `LiveRootAccess`; `FabricChangeListener` maps block/container events to `ChangeListener`.

- [ ] **Step 1: Write the Fabric adapter contract test**

Test that a Fabric item fixture with material, count, custom name, enchantment, and NBT produces the same neutral descriptor fields as the common fixture. Test that a block event marks the expected `BlockKey` and that runtime close unregisters callbacks and closes the common engine.

- [ ] **Step 2: Run the Fabric test before adapter implementation**

Run `./gradlew :fabric:test`.

Expected: FAIL because the Fabric entrypoint and adapters are absent.

- [ ] **Step 3: Add Fabric loader/API configuration and metadata**

Configure the Fabric module with the verified 1.21.4 Fabric Loader/API/Loom versions, Java 21, `dev.jlo:kitsune-api`, and `dev.jlo:kitsune-common`. Declare the `main` entrypoint and required Minecraft/Fabric dependencies in `fabric.mod.json`. Keep common and API package access compatible with the runtime jar layout.

- [ ] **Step 4: Implement native item, world, event, command, and lifecycle ports**

Use Fabric server-thread execution for world reads, native level/chunk/block-entity access for root validation and snapshots, menu/container inventories for contents, NBT and registry identifiers for descriptors, Fabric callbacks for dirty-root changes, and Brigadier/Fabric command registration for search. Translate output through a neutral-to-native renderer and retain the existing search policy/session behavior.

- [ ] **Step 5: Run Fabric tests and jar smoke check**

Run `./gradlew :fabric:test :fabric:build` and inspect the jar for `fabric.mod.json`. Start the configured Fabric test runtime or deterministic loader fixture, enable the mod, run one search request, and disable it. Verify worker and SQLite shutdown.

- [ ] **Step 6: Commit Fabric support**

```bash
git add fabric
git diff --cached --check
git commit -m "Add Fabric platform integration"
```

---

## Task 6: Implement Forge and NeoForge modules

**Files:**
- Create: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeMod.java`
- Create: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeRuntime.java`
- Create: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeItemAccess.java`
- Create: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeWorldAccess.java`
- Create: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeChangeListener.java`
- Create: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeCommand.java`
- Create: `forge/src/main/resources/META-INF/mods.toml`
- Create: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeMod.java`
- Create: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeRuntime.java`
- Create: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeConfigLoader.java`
- Create: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeSessionListener.java`
- Create: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeSessionListener.java`
- Create: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeItemAccess.java`
- Create: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeWorldAccess.java`
- Create: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeChangeListener.java`
- Create: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeCommand.java`
- Create: `neoforge/src/main/resources/META-INF/neoforge.mods.toml`
- Test: `forge/src/test/java/dev/jlo/kitsune/forge/ForgeAdapterContractTest.java`
- Create: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeConfigLoader.java`
- Test: `neoforge/src/test/java/dev/jlo/kitsune/neoforge/NeoForgeAdapterContractTest.java`

**Interfaces:**

Both modules implement the same common ports but use loader-native classes and lifecycle hooks. Their public module entrypoints are separate classes; no Forge source is imported by NeoForge and no NeoForge source is imported by Forge.

- [ ] **Step 1: Write Forge and NeoForge adapter contract tests**

For each module, test native item/NBT-to-descriptor conversion, block/chunk identity conversion, event-to-dirty-root translation, command registration, and idempotent runtime close. Assert that a denied root is filtered before native container contents are exposed to common.

- [ ] **Step 2: Run both tests before adding loader code**

Run `./gradlew :forge:test :neoforge:test`.

Expected: FAIL because both entrypoints and adapters are absent.

- [ ] **Step 3: Configure the Forge module and metadata**

Use the verified Forge 1.21.4 Gradle plugin/dependency coordinates, Java 21, and the shared API/common projects. Define `mods.toml`, mod ID, display name, version expansion, required Minecraft range, and dependency declarations. Register the common runtime through Forge lifecycle/event-bus hooks.

- [ ] **Step 4: Configure the NeoForge module and metadata**

Use the verified NeoForge 1.21.4 Gradle plugin/dependency coordinates, Java 21, and the shared API/common projects. Define `neoforge.mods.toml`, mod ID, display name, version expansion, required Minecraft range, and dependency declarations. Register the common runtime through NeoForge lifecycle/event-bus hooks.

- [ ] **Step 5: Implement native adapters independently**

Implement level/chunk/block-entity access, menu/container snapshots, item-stack/NBT descriptor conversion, event registration, server-thread scheduling, command registration, native text output, and shutdown for Forge first, then mirror the behavior in NeoForge using its actual APIs rather than copying incompatible imports. The common engine receives only neutral values and ports.

- [ ] **Step 6: Run loader tests and package checks**

Run `./gradlew :forge:test :neoforge:test :forge:build :neoforge:build`. Inspect both jars for the correct metadata, invoke each configured test runtime or deterministic loader fixture for enable/search/disable, and verify no loader-specific class is present in `common`.

- [ ] **Step 7: Commit Forge-family support**

```bash
git add forge neoforge
git diff --cached --check
git commit -m "Add Forge and NeoForge integrations"
```

---

## Task 7: Finish publication and the API usage fixture

**Files:**
- Modify: `build.gradle.kts`
- Modify: `api/build.gradle.kts`
- Modify: `common/build.gradle.kts`
- Modify: `paper/build.gradle.kts`, `spigot/build.gradle.kts`, `fabric/build.gradle.kts`, `forge/build.gradle.kts`, `neoforge/build.gradle.kts`
- Create: `api/src/test/java/dev/jlo/kitsune/api/ApiUsageExampleTest.java`

**Interfaces:**

The API usage fixture must compile against only `dev.jlo:kitsune-api` and demonstrate a neutral provider:

```java
final class ExampleAccessProvider implements BlockAccessProvider {
    @Override
    public AccessDecision canAccess(AccessContext context) {
        return AccessDecision.ALLOW;
    }
}
```

- [ ] **Step 1: Write the API consumer fixture**

Add a test that creates an `AccessContext`, registers a neutral provider, constructs an `EmbeddingProvider`, and verifies no platform class is required. Keep it compile-only and deterministic; it must not start a game server.

- [ ] **Step 2: Run the fixture before publication wiring**

Run `./gradlew :api:test`.

Expected: the fixture compiles against the API module and remains independent of all platform modules.

- [ ] **Step 3: Add publication metadata**

Apply `maven-publish` to `api` and consumable platform projects. Configure `withSourcesJar()`, `withJavadocJar()`, stable artifact IDs, POM name/description/license/scm fields using repository facts, and an optional authenticated repository whose URL and credentials come from properties/environment. Keep internal bridge publication disabled by default and embed common implementation into platform runtime jars.

- [ ] **Step 4: Verify local publication and artifact contents**

Run:

```bash
./gradlew clean build publishToMavenLocal
```

Expected: API and five platform artifacts are present under `~/.m2/repository/dev/jlo`, platform jars contain their own metadata and common implementation, API contains no platform classes, and no credentials appear in generated POMs or artifacts.

- [ ] **Step 5: Commit publication and example changes**

```bash
git add build.gradle.kts api/build.gradle.kts common/build.gradle.kts paper/build.gradle.kts spigot/build.gradle.kts fabric/build.gradle.kts forge/build.gradle.kts neoforge/build.gradle.kts api/src/test
git diff --cached --check
git commit -m "Publish modular Kitsune artifacts"
```

---

## Task 8: Full verification and cleanup

**Files:**
- Modify only files required by failing checks from Tasks 1–7.
- Remove obsolete root `src/main` and `src/test` files after every source/test has an owning module.
- Verify `.gitignore` continues to exclude `.worktrees/`, Gradle output, and generated runtime data.

- [ ] **Step 1: Run the complete test/build matrix**

Run:

```bash
./gradlew clean test build
```

Expected: all API, common, Bukkit, Paper, Spigot, Fabric, Forge, and NeoForge tests pass; every requested artifact builds.

- [ ] **Step 2: Run dependency and forbidden-import checks**

Run:

```bash
./gradlew :api:dependencies --configuration compileClasspath :common:dependencies --configuration compileClasspath
```

Then search only module source roots for forbidden imports:

```bash
./gradlew :api:compileJava :common:compileJava
```

Expected: API/common compile successfully without native platform classes; Paper-only imports occur only under `paper/src`; Fabric/Forge/NeoForge imports occur only in their modules.

- [ ] **Step 3: Run lifecycle smoke checks**

Run the configured Paper server smoke test and each deterministic Fabric, Forge, NeoForge, and Spigot adapter fixture. Exercise enable, one search request against a fixture root, access denial, dirty-root notification, and disable. Verify no worker, scheduler, listener, or SQLite connection remains active after disable.

- [ ] **Step 4: Remove dead single-project wiring**

Delete the old root plugin entrypoint, root resource metadata, and root-only build tasks after confirming all code and resources have moved. Remove obsolete imports, duplicate aliases, and stale Paper-only references from non-Paper modules. Do not leave placeholders, compatibility shims, or unused platform source sets.

- [ ] **Step 5: Review artifacts and commit final cleanup**

```bash
git status --short
git diff --check
git log --oneline -8
```

Stage only cleanup required by the modular build and commit:

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/libs.versions.toml api common platform paper spigot fabric forge neoforge src/main src/test .gitignore
git diff --cached --check
git commit -m "Complete modular platform migration"
```

The final branch must have a clean worktree, a passing full build, and atomic commits whose messages match their staged concerns.
