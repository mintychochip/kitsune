# Kitsune Minecraft 26.2 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every Kitsune platform module from Minecraft 1.21.4 to a verified, runnable Minecraft 26.2 baseline.

**Architecture:** Keep `api` and `common` platform-neutral, keep `platform:bukkit` compiled against Spigot, and keep Paper-only, Fabric, Forge, and NeoForge integrations in their existing modules. The migration changes version/toolchain edges and ports native adapters only where 26.2 APIs or mappings require it.

**Tech Stack:** Gradle 9.5.1 Kotlin DSL, Java 25, Paper/Spigot 26.2 APIs, Fabric Loom 1.17.17 with non-obfuscated 26.2 Minecraft, Forge 26.2-65.1.0, NeoForge 26.2.0.45-beta, Shadow, JUnit 5, SQLite JDBC.

## Global Constraints

- Target Minecraft `26.2` across Paper, Spigot, Fabric, Forge, and NeoForge.
- Use Paper API `io.papermc.paper:paper-api:26.2.build.92-stable`; Paper's runtime task uses `minecraftVersion("26.2")`.
- Use Spigot API `org.spigotmc:spigot-api:26.2-R0.1-SNAPSHOT`.
- Use Fabric Loom `1.17.17`, plugin ID `net.fabricmc.fabric-loom`, Minecraft `26.2`, Fabric Loader `0.19.3`, and Fabric API `0.156.0+26.2`; do not retain a Yarn mapping dependency.
- Use Forge `26.2-65.1.0` and NeoForge `26.2.0.45-beta`.
- Compile and run with Java 25; the Gradle wrapper is 9.5.1.
- `api` and `common` contain no platform API imports or dependencies.
- Shared Bukkit code contains no Paper imports; Paper-only code stays in `paper`.
- Preserve indexing, persistence, search ranking, protection, nested traversal, lifecycle, command, and result-presentation behavior.
- Fix incompatibilities at platform boundaries; do not add reflection shims, broad suppressions, dead 1.21.4 branches, or fake fallbacks.
- Use isolated RunPaper state for smoke tests; do not touch an existing world.
- Commit each coherent verified unit with an imperative message and a narrowly staged file set.

---

## File map before editing

**Build and version files:**

- `gradle.properties` — Minecraft baseline and project properties.
- `build.gradle.kts` — shared Java/toolchain, repository, publishing, and test conventions.
- `gradle/libs.versions.toml` — Paper, Spigot, Forge, NeoForge, SQLite, and test dependency versions.
- `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, and `gradlew.bat` — Gradle wrapper distribution.
- `paper/build.gradle.kts`, `spigot/build.gradle.kts`, `fabric/build.gradle.kts`, `forge/build.gradle.kts`, `neoforge/build.gradle.kts` — platform plugins and dependencies.

**Platform metadata:**

- `paper/src/main/resources/plugin.yml` and `spigot/src/main/resources/plugin.yml` — Bukkit API versions.
- `fabric/src/main/resources/fabric.mod.json` — Fabric Loader, Minecraft, Java, and Fabric API requirements.
- `forge/src/main/resources/META-INF/mods.toml` — Forge loader and Minecraft ranges.
- `neoforge/src/main/resources/META-INF/neoforge.mods.toml` — NeoForge loader and Minecraft ranges.

**Native source adapters:**

- Bukkit bridge: `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/**`.
- Fabric: `fabric/src/main/java/dev/jlo/kitsune/fabric/**`.
- Forge: `forge/src/main/java/dev/jlo/kitsune/forge/**`.
- NeoForge: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/**`.
- Entry points and contract tests under the corresponding platform `src/main` and `src/test` trees.

**Documentation:**

- `docs/superpowers/specs/2026-08-03-kitsune-minecraft-26-2-migration-design.md` — approved migration design.
- Existing 1.21.4 design/plan files remain historical records unless a file is clearly an active, non-historical project contract.

---

## Task 1: Update the centralized 26.2 build baseline

**Files:**

- Modify: `gradle.properties`
- Modify: `build.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Regenerate: `gradlew`, `gradlew.bat`
- Modify: `paper/build.gradle.kts`
- Modify: `fabric/build.gradle.kts`
- Modify: `forge/build.gradle.kts`
- Modify: `neoforge/build.gradle.kts`
- Test: Gradle project listing and dependency resolution

**Interfaces:**

- Produces `minecraftVersion=26.2` for all consumers that read the root property.
- Produces version-catalog aliases `libs.paper.api`, `libs.spigot.api`, `libs.forge`, and `libs.neoforge` with the exact versions in the global constraints.
- Produces Java 25 compile/toolchain conventions for every Java subproject.
- Produces a Paper `runServer` task configured for Minecraft 26.2 and Java 25.

- [ ] **Step 1: Confirm the baseline fails for the intended reason before edits**

Run:

```bash
./gradlew :paper:tasks --all
```

Expected: configuration fails while the old Fabric Loom/Yarn setup remaps the 1.21.4 Fabric API sources; this records the pre-migration failure without modifying files.

- [ ] **Step 2: Replace the version catalog pins**

Set the `[versions]` entries in `gradle/libs.versions.toml` to:

```toml
paper-api = "26.2.build.92-stable"
spigot-api = "26.2-R0.1-SNAPSHOT"
forge = "26.2-65.1.0"
neoforge = "26.2.0.45-beta"
```

Leave SQLite and JUnit versions unchanged.

- [ ] **Step 3: Update the root baseline and Java conventions**

Change `gradle.properties` to:

```properties
minecraftVersion=26.2
```

In `build.gradle.kts`, change both the Java toolchain and compiler release assignments from 21 to 25:

```kotlin
extensions.configure<JavaPluginExtension> {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}
```

- [ ] **Step 4: Upgrade the wrapper to Gradle 9.5.1**

Run:

```bash
./gradlew wrapper --gradle-version 9.5.1 --distribution-type bin
```

Confirm `gradle/wrapper/gradle-wrapper.properties` points to `gradle-9.5.1-bin.zip` and the generated wrapper scripts are updated. Do not hand-edit unrelated wrapper logic.

- [ ] **Step 5: Update platform build plugins and dependencies**

In `paper/build.gradle.kts`, keep `minecraftVersion(rootProject.providers.gradleProperty("minecraftVersion").get())` and change the launcher block to Java 25:

```kotlin
tasks.runServer {
    minecraftVersion(rootProject.providers.gradleProperty("minecraftVersion").get())
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    jvmArgs("-Dcom.mojang.eula.agree=true")
}
```

In `fabric/build.gradle.kts`, change the plugin line to:

```kotlin
id("net.fabricmc.fabric-loom") version "1.17.17"
```

and replace the 1.21.4 Minecraft/Yarn/Loader/API dependencies with:

```kotlin
minecraft("com.mojang:minecraft:26.2")
modImplementation("net.fabricmc:fabric-loader:0.19.3")
modImplementation("net.fabricmc.fabric-api:fabric-api:0.156.0+26.2")
```

Do not add a replacement Yarn coordinate; official 26.2 is non-obfuscated and Loom's 26.2 plugin path does not need a `mappings` dependency.

In `forge/build.gradle.kts`, change the official mapping version to `26.2`. Keep the existing ForgeGradle plugin until resolution or compilation proves it cannot consume `26.2-65.1.0`; then update only to a verified ForgeGradle release that does.

- [ ] **Step 6: Resolve the project graph and baseline dependencies**

Run:

```bash
./gradlew projects
./gradlew :api:compileJava :common:compileJava :platform:bukkit:compileJava
```

Expected: all eight projects are listed, Gradle resolves the new coordinates, `api` and `common` compile unchanged, and any failure is confined to the native platform currently being ported. Stop and inspect a missing coordinate rather than substituting a different version.

- [ ] **Step 7: Commit the verified build baseline**

Inventory and stage only the baseline files:

```bash
git status --short
git add gradle.properties build.gradle.kts gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.properties gradlew gradlew.bat paper/build.gradle.kts fabric/build.gradle.kts forge/build.gradle.kts
git diff --cached --check
git commit -m "Update build baseline for Minecraft 26.2"
```

---

## Task 2: Port Fabric to Loom 1.17 and 26.2 names

**Files:**

- Modify: `fabric/build.gradle.kts`
- Modify: `fabric/src/main/resources/fabric.mod.json`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricChangeListener.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricCommand.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricItemAccess.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricMod.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricRuntime.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricServerThreadBridge.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricSessionListener.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricSessionScheduler.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricWorldAccess.java`
- Test: `fabric/src/test/java/dev/jlo/kitsune/fabric/FabricAdapterContractTest.java`

**Interfaces:**

- Consumes the neutral `api`/`common` classes without changing their public contracts.
- Produces a Fabric mod compiled against the unobfuscated 26.2 Minecraft classes, Fabric Loader 0.19.3, and Fabric API 0.156.0+26.2.

- [ ] **Step 1: Update Fabric metadata**

In `fabric.mod.json`, set the dependency block to:

```json
"fabricloader": ">=0.19.3",
"minecraft": "~26.2",
"java": ">=25",
"fabric-api": "*"
```

- [ ] **Step 2: Port imports and symbols using the 26.2 official namespace**

Use the 26.2 Minecraft source/javadocs resolved by Loom as the source of truth. Replace every remaining Yarn-only package or symbol in the listed Java files; do not preserve compatibility aliases. The high-risk files are:

- `FabricItemAccess.java`: item components, registry lookups, text, rarity, NBT, and nested container extraction.
- `FabricWorldAccess.java`: block/entity/world chunk access, inventory discovery, registry IDs, player locations, and live validation.
- `FabricCommand.java` and `FabricRuntime.java`: command source, server/player text, tick registration, and server directory access.
- `FabricChangeListener.java` and `FabricSessionListener.java`: Fabric API event signatures.

Keep existing null/limit checks, digest inputs, search-token cancellation, and server-thread dispatch unchanged. If a 26.2 API moved or renamed, update only the adapter call and its direct conversion code.

- [ ] **Step 3: Compile the Fabric adapter before running its tests**

Run:

```bash
./gradlew :fabric:compileJava
```

Expected: no missing Yarn packages, no unresolved 1.21.4 method signatures, and only Java source/API diagnostics from the files listed in this task if the first pass is incomplete.

- [ ] **Step 4: Run Fabric tests and artifact build**

Run:

```bash
./gradlew :fabric:test :fabric:build
```

Expected: `FabricAdapterContractTest` passes and `fabric/build/libs/` contains the 26.2 Fabric artifact with the existing shaded common/API contents.

- [ ] **Step 5: Commit the Fabric port**

```bash
git add fabric/build.gradle.kts fabric/src
git diff --cached --check
git commit -m "Port Fabric adapter to Minecraft 26.2"
```

---

## Task 3: Port the shared Bukkit bridge, Paper, and Spigot

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `platform/bukkit/build.gradle.kts`
- Modify: `platform/bukkit/src/main/java/dev/jlo/kitsune/bukkit/**`
- Test: `platform/bukkit/src/test/java/dev/jlo/kitsune/**`
- Modify: `paper/src/main/resources/plugin.yml`
- Modify: `spigot/src/main/resources/plugin.yml`
- Test/build: `paper/build.gradle.kts`, `spigot/build.gradle.kts`

**Interfaces:**

- `platform:bukkit` continues to expose only Spigot-compatible code to both entrypoints.
- `paper` may consume Paper-only APIs after the bridge compiles.
- `spigot` contains no `io.papermc` imports and resolves `spigot-api:26.2-R0.1-SNAPSHOT`.

- [ ] **Step 1: Update Bukkit metadata**

Set `api-version: '26.2'` in both plugin descriptors. Keep command names, permissions, soft dependencies, and usage text unchanged.

- [ ] **Step 2: Compile the shared bridge against Spigot first**

Run:

```bash
./gradlew :platform:bukkit:compileJava
```

For each diagnostic, update the smallest shared bridge call site. Check `BukkitItemDescriber`, `BukkitItemSerialization`, `BukkitTraversalAdapter`, `BukkitLiveRootAccess`, `BukkitRootResolver`, and `MarkerRenderer` first because they touch item metadata, registries, world/chunk enumeration, and entity presentation. Keep event/listener lifecycle and thread-bridge behavior unchanged.

- [ ] **Step 3: Run bridge tests and enforce the boundary**

Run:

```bash
./gradlew :platform:bukkit:test
./gradlew :platform:bukkit:dependencies --configuration compileClasspath
```

Expected: bridge tests pass and the compile classpath contains Spigot but not Paper-only artifacts.

- [ ] **Step 4: Compile and test Spigot**

Run:

```bash
./gradlew :spigot:compileJava :spigot:test :spigot:build
```

Expected: the entrypoint loads, the shaded jar is produced, and no Paper import appears in Spigot sources.

- [ ] **Step 5: Port Paper-only calls and test Paper**

Run:

```bash
./gradlew :paper:compileJava
./gradlew :paper:test :paper:build
```

Update only Paper-specific diagnostics, including registry lookup, persistent-data views, shulker snapshots, entity visibility, and Paper event signatures. Confirm the plugin descriptor remains `main: dev.jlo.kitsune.paper.PaperPlugin` and the shaded jar contains the existing common/API runtime classes.

- [ ] **Step 6: Commit the Bukkit/Paper/Spigot port**

```bash
git add gradle/libs.versions.toml platform/bukkit paper spigot
git diff --cached --check
git commit -m "Port Bukkit platforms to Minecraft 26.2"
```

---

## Task 4: Port Forge to 26.2

**Files:**

- Modify: `forge/build.gradle.kts`
- Modify: `forge/src/main/resources/META-INF/mods.toml`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeChangeListener.java`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeCommand.java`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeItemAccess.java`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeMod.java`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeRuntime.java`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeServerThreadBridge.java`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeSessionListener.java`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeSessionScheduler.java`
- Modify: `forge/src/main/java/dev/jlo/kitsune/forge/ForgeWorldAccess.java`
- Test: `forge/src/test/java/dev/jlo/kitsune/forge/ForgeAdapterContractTest.java`

**Interfaces:**

- Produces a Forge 26.2 mod using official mappings and Forge `26.2-65.1.0`.
- Keeps the existing Forge lifecycle/event-bus registration and neutral runtime contracts.

- [ ] **Step 1: Update Forge metadata and mappings**

In `forge/build.gradle.kts`, use:

```kotlin
minecraft {
    mappings("official", "26.2")
}
```

In `mods.toml`, use the 26.2-compatible ranges:

```toml
loaderVersion="[65,)"
versionRange="[65,)"
versionRange="[26.2,26.3)"
```

Keep the first two ranges attached to `forge` and the last range attached to `minecraft`.

- [ ] **Step 2: Port Forge native calls**

Run `./gradlew :forge:compileJava` and update diagnostics in `ForgeItemAccess`, `ForgeWorldAccess`, `ForgeCommand`, `ForgeChangeListener`, and `ForgeRuntime`. Preserve data-component extraction, HolderLookup usage, server-thread dispatch, event registration, loaded-chunk restrictions, and search-session cleanup. If ForgeGradle or EventBus versions are incompatible, resolve a published 26.2-compatible plugin/artifact before changing the pin; do not use a guessed coordinate.

- [ ] **Step 3: Run Forge tests and artifact build**

Run:

```bash
./gradlew :forge:test :forge:build
```

Expected: `ForgeAdapterContractTest` passes and a shaded Forge jar is produced.

- [ ] **Step 4: Commit the Forge port**

```bash
git add gradle/libs.versions.toml forge
git diff --cached --check
git commit -m "Port Forge adapter to Minecraft 26.2"
```

---

## Task 5: Port NeoForge to 26.2

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `neoforge/build.gradle.kts`
- Modify: `neoforge/src/main/resources/META-INF/neoforge.mods.toml`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeChangeListener.java`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeCommand.java`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeItemAccess.java`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeMod.java`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeRuntime.java`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeServerThreadBridge.java`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeSessionListener.java`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeSessionScheduler.java`
- Modify: `neoforge/src/main/java/dev/jlo/kitsune/neoforge/NeoForgeWorldAccess.java`
- Test: `neoforge/src/test/java/dev/jlo/kitsune/neoforge/NeoForgeAdapterContractTest.java`

**Interfaces:**

- Produces a NeoForge `26.2.0.45-beta` mod with the existing `kitsune` mod ID and neutral runtime contracts.

- [ ] **Step 1: Update NeoForge metadata**

In `neoforge.mods.toml`, set the NeoForge and Minecraft constraints to:

```toml
versionRange="[26.2,)"
versionRange="[26.2,26.3)"
```

Keep the first range on the `neoforge` dependency and the second on `minecraft`.

- [ ] **Step 2: Port NeoForge native calls**

Run `./gradlew :neoforge:compileJava` and fix diagnostics in item/component extraction, registry access, event registration, command sources, world/chunk access, and lifecycle cleanup. Preserve the current `NeoForgeRuntime` construction and the merged-jar test runtime setup. Keep NeoForge code independent from Forge code.

- [ ] **Step 3: Run NeoForge tests and artifact build**

Run:

```bash
./gradlew :neoforge:test :neoforge:build
```

Expected: `NeoForgeAdapterContractTest` passes and the shaded NeoForge jar is produced.

- [ ] **Step 4: Commit the NeoForge port**

```bash
git add gradle/libs.versions.toml neoforge
git diff --cached --check
git commit -m "Port NeoForge adapter to Minecraft 26.2"
```

---

## Task 6: Verify the complete 26.2 build and live Paper server

**Files:**

- Test: all existing module test/build tasks
- Runtime state: isolated RunPaper directory only; do not commit generated files
- Inspect: active build/metadata/docs references to `1.21.4`

**Interfaces:**

- All platform modules resolve and build against 26.2.
- The real Paper server loads the built Kitsune plugin and responds to `/kitsune`.

- [ ] **Step 1: Run neutral and platform regression suites**

Run:

```bash
./gradlew :api:test :common:test :platform:bukkit:test :paper:test :spigot:test :fabric:test :forge:test :neoforge:test
```

Expected: every existing test task passes. A failure is fixed in the owning module and the command is rerun; no test is deleted or weakened to accommodate a migration break.

- [ ] **Step 2: Build every artifact**

Run:

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`, with platform jars in each module's `build/libs/` directory and no unresolved 1.21.4 dependency.

- [ ] **Step 3: Launch the actual Paper 26.2 server**

Start `./gradlew :paper:runServer` as a supervised long-running process with readiness requiring both the Paper `Done (` log and TCP port 25565. Confirm logs contain Paper 26.2 and `Kitsune enabled`.

- [ ] **Step 4: Exercise the plugin command and stop cleanly**

Send `kitsune` to the Paper console. Confirm the usage line from `plugin.yml` is emitted without an exception. Send `stop`; confirm the process exits normally and logs contain `Kitsune disabled`.

- [ ] **Step 5: Search active files for stale version pins**

Run a scoped search over build files, platform metadata, and active documentation. Every remaining `1.21.4` occurrence must either be removed or be inside an explicitly historical design/plan record. Do not rewrite historical records solely to erase their original target.

- [ ] **Step 6: Commit verification-only cleanup separately when needed**

If the final search identifies a current config/doc fix not covered by a platform commit, stage only that fix and use an imperative message describing the single concern. Leave generated RunPaper state ignored or removed.

---

## Final acceptance checklist

- [ ] `minecraftVersion=26.2` is centralized and consumed by the Paper runtime task.
- [ ] Paper, Spigot, Fabric, Forge, and NeoForge dependencies resolve at their verified 26.2 coordinates.
- [ ] Fabric uses `net.fabricmc.fabric-loom` 1.17.17 without Yarn mappings.
- [ ] Java 25 and Gradle 9.5.1 are used by the build and server.
- [ ] `api`, `common`, Bukkit bridge, and every native adapter test pass.
- [ ] `./gradlew build` succeeds.
- [ ] Paper 26.2 boots, enables Kitsune, answers `kitsune`, and stops cleanly.
- [ ] No generated server state or unrelated changes remain in the working tree.
