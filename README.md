# Kitsune

Kitsune is a **semantic nearby storage search** plugin for Paper Minecraft servers.
Players describe what they want in plain words and Kitsune finds the matching items
inside chests, barrels, shulker boxes, bundles, and other nearby containers.

[![License](https://img.shields.io/github/license/mintychochip/kitsune)](https://github.com/mintychochip/kitsune/blob/master/LICENSE)
[![Version](https://img.shields.io/badge/version-0.1.0--SNAPSHOT-lightgrey)](https://github.com/mintychochip/kitsune)
[![Paper](https://img.shields.io/badge/Paper-26.2-blue)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)](https://openjdk.org)
[![Gradle](https://img.shields.io/badge/Gradle-9.5.1-02303A?logo=gradle)](https://gradle.org)

## Features

- **Natural-language item search** — type what you are looking for instead of memorizing container locations.
- **Nested container support** — searches through shulker boxes, bundles, and other nested inventories.
- **Protection-aware** — optional soft-dependency on [LWC](https://github.com/Hidendra/LWC) prevents players from seeing items they cannot access.
- **World markers** — highlights matching containers briefly so players can find them.
- **Embeddings-based ranking** — uses a configurable embedding provider to score how similar an item is to your query.
- **SQLite-backed index** — persists the index across restarts and reuses it for fast repeated searches.
- **Async, tick-budgeted indexing** — spreads chunk and container scanning across ticks to avoid lag.
- **Configurable radius, scoring, and limits** — tune search behavior for your server.

## Requirements

- Paper **26.2**
- Java **25**

LWC is optional. Without it, Kitsune respects no protection beyond vanilla access checks.

## Installation

1. Download `kitsune-paper-*.jar` from the latest release.
2. Place the jar in your server's `plugins/` directory.
3. Start the server. A default `config.yml` is generated in `plugins/Kitsune/`.

## Commands

| Command | Description | Permission |
|---|---|---|
| `/kitsune <query>` | Search nearby storage for items matching `query`. | `kitsune.search` |
| `/kitsune --verbose <query>` | Show the same search with extra score and path details. | `kitsune.search` |

The default command usage is `/kitsune [--verbose] <query>`.

## Configuration

The generated `plugins/Kitsune/config.yml` controls search and indexing.

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

- `search.radius` — starting search radius around the player.
- `search.max-radius` — farthest the search is allowed to expand.
- `search.minimum-score` — similarity threshold; lower values return more results.
- `search.max-results` — total matches shown.
- `search.max-paths-per-root` — distinct paths kept per container root.
- `search.warmup-timeout-seconds` — how long a search waits for the index to warm up.
- `markers.duration-seconds` — how long matching container markers last.
- `index.*` — per-tick chunk and root budgets, reconciliation cadence, and traversal limits.
- `embedding.provider` — embedding provider to use. `builtin:sparse-v1` is included; OpenAI-compatible providers can be configured separately.

## Building from source

```bash
./gradlew :kitsune-paper:build
```

The plugin jar is written to `kitsune-paper/build/libs/kitsune-paper-*.jar`.

The project uses a Gradle 9.5.1 wrapper and Java 25. Other modules (`kitsune-api`, `kitsune-common`, `platform:bukkit`) are shared between Paper and future platform entry points.

## License

[MIT](https://github.com/mintychochip/kitsune/blob/master/LICENSE)
