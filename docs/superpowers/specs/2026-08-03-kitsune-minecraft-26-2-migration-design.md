# Kitsune Minecraft 26.2 Migration Design

**Status:** Approved in design discussion; awaiting written-spec review
**Date:** 2026-08-03
**Target:** Minecraft 26.2 across Paper, Spigot, Fabric, Forge, and NeoForge
**Runtime:** Java 25

## Problem

The repository currently targets Minecraft 1.21.4 across five platform modules. Version declarations are split between `gradle.properties`, the version catalog, platform build scripts, and platform metadata. The source adapters also depend on versioned platform APIs and, on Fabric, Yarn mappings.

A version-only edit would leave unresolved coordinates and source/API failures. The migration must produce one coherent 26.2 baseline while preserving the neutral `api`/`common` architecture and existing Kitsune behavior.

## Goals

- Target Minecraft 26.2 in every included platform module.
- Keep `api` and `common` platform-neutral.
- Preserve indexing, persistence, search, protection, nesting, lifecycle, and result-presentation behavior.
- Use resolvable, explicitly pinned platform dependencies.
- Port source and metadata changes required by the 26.2 APIs and mappings.
- Keep the Paper `runServer` task able to launch a real 26.2 server.
- Verify platform-specific compilation/tests and actual Paper plugin enablement.
- Update version-specific design/plan documentation so it no longer describes 1.21.4 as the active baseline.

## Non-goals

- No unrelated refactoring of the neutral engine or platform boundaries.
- No compatibility shim for 1.21.4 after the cutover.
- No world-data migration or conversion of an existing production world. A live 26.2 server smoke test uses an isolated RunPaper directory.
- No new gameplay behavior unrelated to API compatibility.

## Verified dependency baseline

These coordinates were checked against the official Maven metadata available during design:

| Component | Coordinate/configuration |
|---|---|
| Minecraft baseline | `26.2` |
| Paper API | `io.papermc.paper:paper-api:26.2.build.92-stable` |
| Paper runtime | RunPaper `minecraftVersion("26.2")` |
| Spigot API | `org.spigotmc:spigot-api:26.2-R0.1-SNAPSHOT` |
| Fabric Loom | `fabric-loom` `1.17.17` |
| Fabric Minecraft | `com.mojang:minecraft:26.2` |
| Fabric mappings | Official Mojang mappings; no Yarn 26.2 dependency |
| Fabric Loader | `net.fabricmc:fabric-loader:0.19.3` |
| Fabric API | `net.fabricmc.fabric-api:fabric-api:0.156.0+26.2` |
| Forge | `net.minecraftforge:forge:26.2-65.1.0` |
| NeoForge | `net.neoforged:neoforge:26.2.0.45-beta` |
| Java toolchain | Java 25 |
| Gradle wrapper | Gradle 9.5.1, required by the selected Fabric Loom baseline |

Paper's repository uses build-qualified versions for this release rather than a `26.2-R0.1-SNAPSHOT` Paper API coordinate. The runtime task continues to select the `26.2` server line; the compile-time Paper API uses the published build-qualified artifact.

## Architecture and dependency flow

The existing module direction remains unchanged:

```text
api  <-  common  <-  platform adapters
                    |- platform:bukkit <- paper, spigot
                    |- fabric
                    |- forge
                    `- neoforge
```

`api` contains immutable neutral contracts and models. `common` owns indexing, persistence, embedding, search, protection orchestration, nested traversal, configuration, and session behavior. Platform modules own native lifecycle, world, item, command, event, and presentation adapters.

The migration updates platform edges and shared build conventions, not the dependency direction. Paper-only APIs stay in `paper`; the shared Bukkit bridge remains compiled against Spigot. Fabric, Forge, and NeoForge retain independent native adapters.

## Build and configuration changes

1. Centralize the Minecraft baseline and platform coordinates in the version catalog/properties. Avoid a second untracked version literal in build scripts or metadata.
2. Update the root Java toolchain and compile release to 25.
3. Update Paper's `runServer` launcher to Java 25 and retain the EULA agreement configuration.
4. Upgrade the Gradle wrapper to 9.5.1 and the Fabric Loom plugin to 1.17.17.
5. Replace Fabric's Yarn dependency with the supported official-mappings configuration for Minecraft 26.2.
6. Pin the verified Paper, Spigot, Forge, NeoForge, Fabric Loader, and Fabric API versions.
7. Update Forge/NeoForge loader and Minecraft version ranges in `mods.toml` and `neoforge.mods.toml`.
8. Update `api-version` and Minecraft dependency metadata to 26.2 where the platform format supports it.
9. Keep publication artifact names and neutral API coordinates stable.

## Source migration strategy

### Fabric

Migrate the module from Yarn names to the 26.2 official mapping namespace supported by Loom 1.17. Update imports and method/field calls in the Fabric world, item, command, event, lifecycle, and runtime adapters. Preserve the existing adapter contracts and thread boundaries. The repository has no mixin source, so mapping migration is limited to the listed native adapters and metadata.

### Paper and Spigot

Compile the shared Bukkit bridge against the 26.2 Spigot API first. Fix common Bukkit changes at the bridge boundary. Then compile Paper and apply Paper-only changes in the Paper adapter, including registry, persistent-data, shulker, entity visibility, and event APIs if the compiler identifies changes. Keep Spigot free of Paper imports.

### Forge and NeoForge

Update mappings, loader dependencies, and metadata first. Port native item/component, registry, lifecycle/event, command, and world access calls against the 26.2 APIs. Keep Forge and NeoForge implementations independent; do not introduce cross-loader abstractions into `common` to hide incompatible APIs.

### Neutral modules

Run `api` and `common` checks before platform ports. Change neutral code only if a platform contract exposes a real 26.2 incompatibility; otherwise leave it untouched.

## Error handling and compatibility rules

- A missing artifact or unresolved coordinate stops the migration; do not substitute an unverified version.
- A source/API break is fixed at the platform boundary rather than hidden with reflection, broad suppression, or dead compatibility branches.
- If a capability no longer exists on a platform, retain the existing explicit capability/fallback contract and document the observed limitation.
- RunPaper smoke tests use an isolated generated server directory so no existing world data is modified.
- The Paper server must reach the normal ready log, load Kitsune without a plugin exception, and accept a clean `stop` command.

## Verification plan

1. Resolve and compile `api` and `common` with Java 25.
2. Run platform-specific compile/test tasks for Paper, Spigot, Fabric, Forge, and NeoForge.
3. Build platform artifacts and confirm expected jars are produced.
4. Launch the actual Paper 26.2 server through `runServer` with Java 25.
5. Confirm the server reports 26.2, Kitsune enables, and no missing runtime dependency occurs.
6. Exercise the `/kitsune` command at least once, then stop the server cleanly.
7. Search tracked source, build, metadata, and active docs for stale 1.21.4 references; retain historical records only when clearly labeled as historical.
8. Re-run the relevant checks after final documentation/configuration edits.

## Acceptance criteria

- No active build or platform metadata pins Minecraft 1.21.4.
- All five platform modules resolve 26.2-compatible coordinates.
- Fabric compiles using official mappings rather than Yarn.
- Java 25 and the selected Gradle/Loom baseline are used consistently.
- Platform-specific tests/builds pass for the migrated modules.
- A real Paper 26.2 server boots, enables Kitsune, responds to `/kitsune`, and stops cleanly.
- The working tree contains no generated server state or unrelated changes.
