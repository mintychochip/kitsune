# Kitsune Multi-Platform Modules Design

**Date:** 2026-08-03
**Status:** Approved in design discussion; awaiting written-spec review
**Baseline:** Minecraft 1.21.4, Java 21

## Problem

Kitsune is currently a single Gradle Java plugin. Its indexing, persistence, search, embedding, protection, command, session, UI, and Bukkit integration code live in one source set. The existing `api` package is not a platform-neutral public API: it imports Bukkit types and refers to internal model classes. The current build also compiles directly against Paper, so it cannot produce usable Fabric, Forge, NeoForge, or Spigot artifacts.

The project needs separate modules with a shared internal engine, a stable Maven-facing API, and native integrations for Paper, Spigot, Fabric, Forge, and NeoForge.

## Goals

- Publish a platform-neutral `api` artifact for extension authors and consumers.
- Move indexing, persistence, search, embeddings, protection orchestration, nested traversal, configuration, and session behavior into a platform-independent `common` implementation module.
- Deliver separate 1.21.4 platform artifacts for Paper, Spigot, Fabric, Forge, and NeoForge.
- Preserve the current nearby-storage search behavior, including nested item traversal, access filtering, indexing lifecycle, and result presentation where the platform supports the capability.
- Keep platform dependencies out of `api` and `common`.
- Publish reproducible Maven artifacts to Maven Local and to an optional repository configured through Gradle properties or environment variables.
- Make lifecycle failures and capability gaps explicit rather than silently disabling behavior.

## Non-goals

- Supporting multiple Minecraft versions in the first modular release.
- Sharing loader-specific implementation code between Fabric, Forge, and NeoForge when their APIs are different.
- Keeping pre-1.0 internal package names or Bukkit-shaped API contracts as compatibility aliases.
- Hard-coding a Maven repository, credentials, or release-signing material.

## Approved architecture

The build becomes a Gradle multi-project build:

```text
api                  (JDK-only public contracts and immutable data)
common               (shared implementation; depends on api)
platform:bukkit      (internal shared lowest-common-denominator Bukkit adapters)
paper                (Paper entrypoint and Paper-only capabilities)
spigot               (Spigot entrypoint and Spigot-safe adapters)
fabric               (Fabric loader entrypoint and native adapters)
forge                (Forge entrypoint and native adapters)
neoforge             (NeoForge entrypoint and native adapters)
```

The dependency direction is:

```text
paper ───────┐
spigot ──────┤
fabric ──────┤──> common ──> api
forge ───────┤
neoforge ────┘
```

`platform:bukkit` depends on the neutral contracts and is consumed by Paper and Spigot entrypoints. It is not the abstraction boundary for Fabric, Forge, or NeoForge and is not a public target artifact.

`api` contains no Bukkit, Paper, Adventure, Fabric, Forge, NeoForge, or loader classes. Public values use Java primitives, immutable records, bounded collections, and platform-neutral identifiers. Public providers cannot retain native platform objects after a call returns.

`common` may depend on JDK libraries and the existing SQLite implementation, but never on a platform package. Its implementation is expressed through ports for server-thread execution, scheduling, world and container access, item description, access control, lifecycle, messaging, and change notifications.

The current `dev.jlo.kitsune.model` records and the useful extension contracts will be moved into the `api` project in neutral form. Engine classes such as indexing, search, embedding, persistence, configuration, and sessions will move to `common` packages. Bukkit-only classes and plugin lifecycle code will move to the internal bridge or the Paper/Spigot modules. Because the project is still `0.1.0-SNAPSHOT`, this is a clean cutover rather than a compatibility-alias migration.

## Public API boundary

The public API will expose the stable concepts needed by consumers and extension authors:

- immutable item descriptors and bounded metadata;
- root, block, chunk, item-path, and search value objects;
- access decisions and access-provider contracts;
- nested-content and item-feature provider contracts;
- embedding and embedding-provider contracts;
- neutral search request/result contracts where external integrations need them;
- explicit capability and lifecycle contracts where a platform integration must report support.

Public contracts will use neutral identifiers such as namespaced strings and coordinate records instead of `Player`, `Block`, `ItemStack`, `World`, `Component`, or loader-specific objects. Text exposed through the API will be represented as bounded plain text or neutral structured values; Adventure components remain platform code.

The API artifact is the compile-time integration surface. `common` is an implementation dependency and may be embedded into platform runtime jars. Internal classes are not re-exported as public extension contracts.

## Common engine ports

The shared engine will consume these responsibilities through narrow interfaces:

- **Item access:** enumerate container contents and nested contents, and produce a neutral `ItemDescriptor` from a native stack.
- **World access:** resolve storage roots, inspect block/chunk state, and create safe snapshots for indexing and result navigation.
- **Server execution:** run world reads on the native server thread and background work on managed executors.
- **Access control:** determine whether a player/reference may inspect a root. Bukkit can register LWC and other providers through the neutral access-provider SPI.
- **Runtime:** expose configuration paths, lifecycle hooks, commands, messages, marker/navigation output, and platform capabilities.
- **Change listeners:** translate native block, inventory, entity, and container events into common dirty-root notifications.

The common engine retains responsibility for indexing queues, reconciliation, SQLite schema/repository, nested traversal limits, search policy, embeddings, protection filtering, session cleanup, and result ranking. Platform adapters only translate native data and register native hooks.

## Platform strategy

### Paper and Spigot

Paper and Spigot receive separate entrypoints and metadata. Shared Bukkit code is limited to APIs present in the selected Spigot 1.21.4 baseline. The Paper module owns Paper-only integrations, including the current `CrafterCraftEvent` hook, Paper registry lookup behavior, Paper persistent-data views, and Paper shulker snapshot behavior. The Spigot module uses Bukkit-safe equivalents and contains no Paper imports or transitive Paper dependency.

The Bukkit adapter boundary will be checked by compiling the shared bridge against Spigot rather than Paper. Any behavior that cannot be represented by the lowest common API is an explicit platform capability with a documented fallback, not a reference to a Paper class from shared code.

Bukkit protection providers such as LWC remain optional platform adapters. Their absence does not break common indexing; access decisions still flow through the configured neutral access-control port.

### Fabric

Fabric provides a native loader entrypoint, lifecycle registration, commands, event translation, server-thread scheduling, level/chunk access, container access, item/NBT-to-descriptor conversion, and neutral access-control integration. Fabric metadata and dependencies remain in the Fabric module. No Bukkit compatibility layer is used.

### Forge and NeoForge

Forge and NeoForge each receive a separate module and entrypoint because their event, command, lifecycle, metadata, and mapping APIs differ. Each adapter implements the same common ports using its native level, block entity, menu/container, item-stack, event bus, and scheduler APIs. Forge and NeoForge code is not cross-imported merely to reduce file count.

## Runtime packaging and publication

Coordinates retain the repository group and shared version:

- `dev.jlo:kitsune-api`
- `dev.jlo:kitsune-common` as an implementation artifact when required by publication resolution;
- `dev.jlo:kitsune-platform-bukkit` as an internal bridge artifact;
- `dev.jlo:kitsune-paper`;
- `dev.jlo:kitsune-spigot`;
- `dev.jlo:kitsune-fabric`;
- `dev.jlo:kitsune-forge`;
- `dev.jlo:kitsune-neoforge`.

Platform dependency versions and mappings are centralized in Gradle configuration and remain overrideable. The first release targets 1.21.4 and Java 21. The existing Paper baseline is retained; the Spigot module uses the corresponding Spigot API, while Fabric, Forge, and NeoForge versions are pinned independently to the same Minecraft baseline.

`maven-publish` provides sources, javadocs, and reproducible POM metadata. `publishToMavenLocal` requires no credentials. An additional Maven repository is configured only when a repository URL property is supplied, and credentials come from Gradle properties or environment variables. No secret is stored in source control.

Platform runtime jars are self-contained for their selected loader by embedding `common` and required implementation code. `kitsune-api` remains independently consumable at compile time and is not coupled to platform classes. Loader metadata is packaged only by the corresponding platform module: `plugin.yml` for Paper/Spigot, `fabric.mod.json` for Fabric, and the appropriate Forge-family metadata for Forge and NeoForge.

## Testing and verification

Tests are moved with their owning code rather than copied into every module:

1. **API tests** verify immutability, validation, bounded values, neutral serialization-safe data, and provider contracts.
2. **Common tests** use deterministic fake ports to cover indexing, reconciliation, persistence, nested traversal, embeddings, search ranking, access filtering, session lifecycle, and failure propagation without a game server.
3. **Adapter tests** verify native-to-neutral translation, event-to-dirty-root conversion, lifecycle cleanup, metadata packaging, and platform-specific capability behavior. The common contract suite is reused against adapter fixtures where feasible.
4. **Build checks** compile every module and verify that `api` and `common` have no forbidden platform dependencies. A clean build exercises the platform packaging tasks and publication metadata.

A required port or initialization failure stops that platform with a clear error. Optional integrations and platform capability gaps are reported through explicit capability state and logs. They cannot introduce a compile dependency leak or silently change common search semantics. Shutdown must stop workers, close the index repository, unregister listeners, and release executors for every platform.

## Acceptance criteria

- `settings.gradle.kts` includes `api`, `common`, the internal Bukkit bridge, and all five requested platform projects.
- `api` compiles with only Java/platform-neutral dependencies; no public type mentions a native game or loader class.
- `common` compiles without any platform API on its compile classpath and owns the current shared behavior.
- Paper, Spigot, Fabric, Forge, and NeoForge each have a loadable 1.21.4 entrypoint and platform-local metadata.
- Paper-only imports are isolated to Paper code; the shared Bukkit bridge compiles against Spigot APIs.
- Existing behavior tests remain green after relocation, and new neutral contract tests protect the module boundary.
- Maven Local publication produces the API and consumable platform artifacts with sources/javadocs and no embedded credentials.
- A focused smoke/build run demonstrates initialization and shutdown for every platform module or its deterministic adapter fixture.
