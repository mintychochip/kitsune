# Legacy api / common / bukkit Cutover into kitsune-* Design

**Date:** 2026-08-24
**Status:** Draft pending approval
**Depends on:** hybrid search implemented on `kitsune-api` / `kitsune-common` / `kitsune-paper` (`docs/superpowers/specs/2026-08-24-hybrid-search-design.md`)
**Related:** `docs/superpowers/specs/2026-08-03-kitsune-multi-platform-modules-design.md`

## Goal

Leave one Gradle tree: `kitsune-api`, `kitsune-common`, `kitsune-paper` (`dev.jlo.kitsune`). Remove the abandoned `api/`, `common/`, and `bukkit/` trees (`org.aincraft.kitsune`) after hybrid search lands in the canonical modules. Do not mash JVector/Guice/Milvus into the live engine.

## Why this is a cutover, not a file-by-file merge

`settings.gradle.kts` already includes only `:kitsune-api`, `:kitsune-common`, `:kitsune-paper`, `:kitsune-test`. Hybrid search, sparse embeddings, SQLite indexing, Paper commands, and protection adapters already live under `dev.jlo.kitsune` in those modules.

The leftover directories are a second product:

| Leftover | Package | Analogous live area |
|---|---|---|
| `api/` | `org.aincraft.kitsune` | `kitsune-api/src/main/java/dev/jlo/kitsune` |
| `common/` | `org.aincraft.kitsune` | `kitsune-common/src/main/java/dev/jlo/kitsune` |
| `bukkit/` | `org.aincraft.kitsune` | `kitsune-paper/src/main/java/dev/jlo/kitsune` (`bukkit/`, `index/`, `config/`, `paper/`) |

Same *areas* (api / common / paper), different architecture: Guice DI, JVector ANN, Milvus, Google embeddings, Oraxen tags, a second indexer. Copying them in would create two search stacks.

## Scope

**In scope (after hybrid search is merged and green)**

- Delete `api/`, `common/`, `bukkit/` source trees and their `build.gradle.kts`.
- Delete leftover `platform/` if it is empty after the Paper move.
- Grep the repo for `org.aincraft.kitsune`, `:api`, `:common`, `:bukkit`, `platform:bukkit` and remove dead references from README, plans, and Gradle files.
- Confirm `./gradlew :kitsune-api:test :kitsune-common:test :kitsune-paper:test` still PASS.
- Confirm no production code under `dev.jlo.kitsune` imports `org.aincraft`.

**Out of scope**

- Porting JVector, Milvus, Guice, or the org.aincraft ONNX/Google factories into `kitsune-common`.
- Porting Oraxen / data-component tag providers (separate spec if wanted).
- Fabric / Forge / NeoForge / Spigot artifacts from the 2026-08-03 multi-platform spec.
- Rewriting hybrid search onto the old modules.
- Relocating live `kitsune-paper/.../bukkit` adapters out of that package; they already sit in the Paper module, which is the correct similar area.

## Mapping (similar areas)

When something *must* move rather than delete, put it here — nowhere else:

| Kind | Destination |
|---|---|
| Public contracts, descriptors, SPI | `kitsune-api/src/main/java/dev/jlo/kitsune/{api,model}` |
| Index, search, embedding, SQLite, config records | `kitsune-common/src/main/java/dev/jlo/kitsune/{index,search,embedding,config}` |
| Paper plugin, Bukkit adapters, listeners, YAML | `kitsune-paper/src/main/java/dev/jlo/kitsune/{paper,bukkit,index,config,session}` |

Default action is **delete**, not move. Live Kitsune already has `SearchHistoryStorage`, `PlayerRadiusStorage`, local ONNX, OpenAI-compatible embeddings, LWC/Bolt/Lockette, nested shulker/bundle walkers, and `MarkerRenderer`.

## Functional requirements

**FR-001** The Gradle build shall not include `api`, `common`, or `bukkit` projects.
- **AC-1** `settings.gradle.kts` lists only kitsune-* (and `kitsune-test`).
- **AC-2** `./gradlew projects` does not print `:api`, `:common`, or `:bukkit`.

**FR-002** Those directories shall not exist in the repository after cutover.
- **AC-1** `api/src`, `common/src`, `bukkit/src` are gone.

**FR-003** Canonical packages remain `dev.jlo.kitsune`.
- **AC-1** `rg org.aincraft.kitsune -g '*.java'` returns no matches.
- **AC-2** Hybrid search types stay in `dev.jlo.kitsune.search` / `dev.jlo.kitsune.index`.

**FR-004** Tests of the live plugin shall stay green.
- **AC-1** `./gradlew :kitsune-api:test :kitsune-common:test :kitsune-paper:test` PASS.

## Non-goals that people might mistake for merge work

- Keeping `org.aincraft` as a compatibility alias.
- Dual-running JVector and FTS5.
- Moving `kitsune-paper` Bukkit adapters into a revived `platform/bukkit` module in this cutover.

## Verification

```bash
./gradlew :kitsune-api:test :kitsune-common:test :kitsune-paper:test
test ! -d api/src && test ! -d common/src && test ! -d bukkit/src
```

## Decisions log

| Date | Decision | Why |
|---|---|---|
| 2026-08-24 | Hybrid search is implemented only in kitsune-* | That is the live Paper 26.2 engine. |
| 2026-08-24 | Leftover api/common/bukkit are deleted after hybrid, not spliced | They are a superseded org.aincraft stack; similar *areas* already exist under kitsune-*. |
| 2026-08-24 | Cutover waits until hybrid tests are green | Avoid mixing a search rewrite with a tree deletion. |

## Open questions

- [ ] Confirm no wanted Oraxen/tag-provider behavior must be ported before delete.
