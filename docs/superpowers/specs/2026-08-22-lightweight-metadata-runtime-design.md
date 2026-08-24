# Lightweight Metadata-First Runtime Design

**Date:** 2026-08-22  
**Status:** Approved

## Problem

Kitsune's current default is already offline: `builtin:sparse-v1` is a deterministic Java metadata scorer and does not require an LLM, downloaded model, or network service. The runtime-cost concern is therefore not an LLM runtime.

There are two concrete costs:

1. The OpenAI-compatible remote provider is implemented in `common`, so installations that do not use it still compile and package remote-provider classes and their supporting code.
2. The default local search represents every indexed semantic descriptor as an embedding, persists vectors, resolves cached vectors, loads candidate vectors, and computes cosine scores. This is unnecessary when search is fundamentally metadata-driven.

The default should minimize CPU and heap while retaining useful Minecraft-domain search quality. General semantic recall is an explicit tradeoff rather than an implicit promise.

## Decision

Make a lexical SQLite FTS metadata index the default search path. Keep local sparse ranking as an optional quality-oriented profile. Move remote OpenAI-compatible ranking into a separately packaged optional addon.

The core plugin must function without an embedding provider, model, credentials, HTTP client code, or embedding cache.

## Runtime profiles

### Lexical default

The core indexes canonical item metadata in SQLite FTS. Search uses:

- material identifiers and display names;
- lore;
- enchantments;
- attributes;
- tags and traits;
- curated aliases and category vocabulary;
- exact, prefix, phrase, and weighted field matches.

The core stores item paths, amounts, root identities, canonical descriptors, root fingerprints, and searchable metadata. It does not create `Embedding` values, maintain vector columns, or use the `embeddings` cache table.

The query tokenizer must treat player input as plain text. It must tokenize and escape terms before constructing an FTS query; raw player text must never be passed directly as SQLite MATCH syntax. Empty or punctuation-only input is rejected or handled as a deterministic no-result condition.

The local ranker applies radius, chunk-loaded, root-validity, and protection authorization filtering before returning visible results. It uses bounded result collection rather than sorting an unbounded candidate set.

### Optional sparse profile

The existing `builtin:sparse-v1` scorer remains available behind the ranking extension boundary for administrators who prefer its current synonym and weighted-feature behavior. It remains fully offline and requires no model. It is not required by the core lexical profile.

### Optional remote profile

The OpenAI-compatible implementation moves from `common` into a separate addon module/JAR. The core does not load or reference its classes on the default path.

Remote ranking receives only a bounded set of candidates already filtered by radius and protection authorization. It reranks those candidates and falls back to lexical ordering on timeout, malformed responses, unavailable credentials, or other provider failure.

Reranking cannot recover candidates omitted by lexical retrieval. To avoid silently overstating semantic quality, the implementation must document that remote ranking improves ordering, not recall. A bounded low-overlap fallback may inspect additional nearby authorized roots before remote reranking, subject to explicit candidate and CPU limits.
The reranking candidate contract carries immutable root identity, item path, amount, canonical descriptor, and local score. Optional providers consume that payload directly and may only reorder or remove supplied candidates; they cannot introduce or mutate candidates, reload Bukkit state, or query unrestricted repository data.


## Data flow

```text
Minecraft mutation
  -> snapshot and canonical metadata
  -> SQLite item/root tables plus FTS metadata row

Player query
  -> safe tokenizer and alias expansion
  -> parameterized FTS candidate lookup
  -> radius/chunk/root/protection filtering
  -> weighted lexical ranking
  -> optional sparse or remote reranking
  -> markers and chat rendering
```

The core retains existing server-thread snapshot boundaries, index-worker scheduling, root reconciliation, protection checks, session lifecycle, and marker invalidation policy.

## Module boundaries

The core module owns model types, metadata normalization, tokenizer/alias vocabulary, FTS persistence, lexical ranking, search policy, and platform-neutral indexing contracts.

The sparse addon owns `SparseTagEmbeddingProvider`, sparse vector serialization, and sparse-specific ranking integration if those classes cannot remain in the core without forcing vector dependencies into the default path.

The remote addon owns the OpenAI-compatible HTTP provider, credential resolution, remote settings, retries, response validation, and provider registration. Its absence must not prevent core startup.

There must be one extension mechanism for ranking providers. The existing provider catalog/registration design and the older provider registry must not remain as competing runtime paths.

## Quality tradeoff

Lexical FTS is strongest when the query shares terms with indexed metadata: `oak planks`, `enchanted pickaxe`, `fire protection`, or aliased categories such as `food` and `building blocks`. It is weaker for paraphrases whose concepts are absent from metadata or aliases, such as an indirect request for underwater equipment.

The curated vocabulary should recover common Minecraft-domain language without pretending to provide unrestricted semantic understanding. Sparse and remote profiles remain available for servers that value recall or ranking quality over minimum runtime cost.

## Migration

1. Add an FTS schema/profile identity and metadata projection. The FTS candidate query must apply persisted root/world/chunk constraints and the requested search-radius bounds as early as possible, before materializing large candidate sets. Protection checks remain mandatory before results are returned because authorization can depend on live server state. Deleted, unloaded, stale, or unauthorized roots must never reach rendering or reranking.

2. Add explicit schema/profile migration. On opening an older vector-profile database, run a transactional rebuild from canonical stored descriptors, populate the metadata and FTS projection, verify row counts and root references, then switch the profile marker. Retire vector columns and `embeddings` rows only after verification. A fresh lexical database never creates vector tables. Sparse-profile databases remain identifiable and require an explicit profile rebuild; they are not silently treated as lexical data.

3. Ensure root replacement/deletion removes stale FTS postings and that migration is restart-safe. Partial migration must not advertise lexical readiness.

4. Populate FTS rows during root replacement using the same canonical descriptors already produced by snapshots.

5. Add lexical search and safe query construction.

6. Add profile selection with lexical as the default.

7. Preserve existing authorization, radius, live-root, session, and marker behavior.


8. Move remote provider code and dependencies into the optional addon.

9. Migrate all callers to one ranking extension boundary and remove obsolete default-path vector/cache wiring.

The sparse profile may retain its existing persistence path while it is optional. The lexical profile must not initialize or query the embedding cache.

## Verification requirements

Tests must prove observable contracts:

- tokenizer behavior for case, punctuation, Unicode, quoted phrases, empty input, and repeated terms;
- aliases and category expansion for representative Minecraft vocabulary;
- FTS query construction safely handles raw player input containing MATCH operators, quotes, parentheses, dashes, and wildcard characters;
- lexical ranking weights exact identifiers and names above secondary metadata;
- radius and protection authorization filtering occur before results are exposed;
- unloaded, deleted, and stale roots are excluded;
- low-overlap queries use the documented fallback behavior and bounded limits;
- lexical mode performs no embedding-provider calls and does not require credentials;
- existing sparse behavior remains available when the sparse profile is selected;
- remote addon absence does not prevent core startup;
- remote failure returns local results and does not make search unavailable;
- FTS rows survive restart and root replacement without stale postings;
- existing session, marker, index lifecycle, and platform-boundary tests remain valid.

No real external API calls belong in tests. Remote addon tests use a local HTTP test server, as existing provider tests do.

## Non-goals

- Building or bundling a local LLM or embedding model.
- Claiming lexical search provides general semantic understanding.
- Removing SQLite from the core runtime; FTS reuses the existing persistence dependency.
- Implementing ANN/vector-database retrieval.
- Adding a remote fallback that scans the entire world or bypasses radius/protection checks.
- Retaining two parallel provider-registration systems.
