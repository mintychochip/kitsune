# Kitsune Provider Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a configurable provider factory/catalog, dense embeddings, and a tested generic OpenAI-compatible embeddings provider while preserving the sparse provider as the runtime default.

**Architecture:** Keep `EmbeddingProvider` as the runtime search contract. Add public factory and credential-resolution interfaces in `api`, concrete catalog/settings/serialization/provider classes in `common`, and use Java `HttpClient` plus Jackson for bounded JSON HTTP. Provider identity includes a non-secret settings fingerprint so existing provider mismatch handling cannot silently reuse vectors from a changed endpoint/model profile.

**Tech Stack:** Java 21, Gradle Kotlin DSL, JUnit 5, `java.net.http.HttpClient`, JDK `HttpServer`, Jackson databind, existing SQLite/index/search pipeline.

## Global Constraints

- Preserve `builtin:sparse-v1` as the default provider and offline path.
- Do not add provider commands, OAuth, browser setup, hosted credential brokerage, profile-shadow schema, staged cutover, or ANN retrieval in this plan.
- Never put API keys in configuration files, SQLite, logs, test fixtures, or provider identity fingerprints.
- Remote HTTP calls must run through bounded timeouts, serialized request-size limits, response-body limits, and retry limits.
- Dense cosine is raw mathematical cosine in `[-1, 1]`; search validation must accept that range.
- Write tests before production code and observe each new test fail for the intended missing behavior.
- Do not contact real provider services; use a local JDK HTTP server.

---

### Task 1: Add provider factory and credential contracts

**Files:**
- Create: `api/src/main/java/dev/jlo/kitsune/api/embedding/EmbeddingCredentialResolver.java`
- Create: `api/src/main/java/dev/jlo/kitsune/api/embedding/EmbeddingProviderFactory.java`
- Create: `common/src/main/java/dev/jlo/kitsune/embedding/EmbeddingProviderCatalog.java`
- Modify: `api/src/test/java/dev/jlo/kitsune/api/PlatformNeutralApiTest.java`
- Create: `common/src/test/java/dev/jlo/kitsune/embedding/EmbeddingProviderCatalogTest.java`

**Interfaces:**
- `EmbeddingCredentialResolver` exposes `Optional<String> resolve(String reference)`.
- `EmbeddingProviderFactory` exposes `String id()` and `EmbeddingProvider create(Map<String, String> settings, EmbeddingCredentialResolver credentials)`.
- `EmbeddingProviderCatalog` accepts factory registrations, rejects null/blank/duplicate IDs, exposes immutable factories, and creates a provider by factory ID.
- The catalog does not store credentials.

- [ ] **Step 1: Write the failing tests**

Add tests that assert:

```java
@Test
void catalogCreatesProviderThroughRegisteredFactory() {
    EmbeddingProviderFactory factory = new DummyFactory("test");
    EmbeddingProvider provider = new EmbeddingProviderCatalog(List.of(factory))
        .create("test", Map.of("model", "demo"), reference -> Optional.of("secret"));
    assertEquals("test", provider.id());
}

@Test
void catalogRejectsDuplicateFactoryIds() {
    EmbeddingProviderFactory first = new DummyFactory("same");
    EmbeddingProviderFactory second = new DummyFactory("same");
    assertThrows(IllegalArgumentException.class,
        () -> new EmbeddingProviderCatalog(List.of(first, second)));
}
```

Update the API boundary allowlist only for the two new public API types.

- [ ] **Step 2: Run the focused tests and verify the expected failure**

Run:

```bash
./gradlew :common:test --tests '*EmbeddingProviderCatalogTest' --rerun-tasks
```

Expected: compilation/test failure because the new contracts and catalog do not exist.

- [ ] **Step 3: Implement the minimal contracts and catalog**

Implement the two API interfaces with null-safe method contracts. Implement the catalog using a `LinkedHashMap`, immutable snapshots, duplicate detection, and a `create` method that passes settings and the resolver directly to the selected factory.

- [ ] **Step 4: Run the focused tests and verify green**

Run the same command. Expected: the catalog tests and API boundary tests pass.

- [ ] **Step 5: Commit the coherent provider-framework unit**

Stage only the API contracts, catalog, and their tests. Commit with a message such as:

```bash
git add api/src/main/java/dev/jlo/kitsune/api/embedding/EmbeddingCredentialResolver.java \
  api/src/main/java/dev/jlo/kitsune/api/embedding/EmbeddingProviderFactory.java \
  api/src/test/java/dev/jlo/kitsune/api/PlatformNeutralApiTest.java \
  common/src/main/java/dev/jlo/kitsune/embedding/EmbeddingProviderCatalog.java \
  common/src/test/java/dev/jlo/kitsune/embedding/EmbeddingProviderCatalogTest.java
git commit -m "feat: add embedding provider factory catalog"
```

---

### Task 2: Implement dense embeddings and raw cosine validation

**Files:**
- Create: `common/src/main/java/dev/jlo/kitsune/embedding/DenseEmbedding.java`
- Modify: `common/src/main/java/dev/jlo/kitsune/search/SearchService.java:311-316`
- Create: `common/src/test/java/dev/jlo/kitsune/embedding/DenseEmbeddingTest.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/search/SearchServiceTest.java` only if an existing score-bound test encodes the old upper/lower contract.

**Interfaces:**
- `DenseEmbedding` implements `Embedding` and exposes a public constructor accepting provider ID, provider version, and finite `float[]` components.
- `DenseEmbedding.decode(String providerId, int providerVersion, byte[] payload, double expectedNorm)` reconstructs the versioned payload.
- `cosine` returns raw `[-1, 1]` cosine, returns `0.0` for a zero-norm operand, and rejects provider/version/dimension/type mismatches.

- [ ] **Step 1: Write failing dense-vector tests**

Cover:

```java
@Test
void roundTripsFiniteComponentsAndNorm() { ... }

@Test
void returnsNegativeCosineForOppositeVectors() { ... }

@Test
void rejectsMismatchedDimensions() { ... }

@Test
void rejectsCorruptPayloadAndNormMismatch() { ... }
```

Use real vectors such as `[1f, 0f]` and `[-1f, 0f]`; assert the opposite cosine is `-1.0`.

- [ ] **Step 2: Run the focused tests and verify the expected failure**

Run:

```bash
./gradlew :common:test --tests '*DenseEmbeddingTest' --rerun-tasks
```

Expected: compilation/test failure because `DenseEmbedding` does not exist.

- [ ] **Step 3: Implement the bounded binary vector**

Use a fixed magic/version header, dimension, and big-endian float payload. Enforce a maximum dimension of 16,384, finite components, exact payload length, and finite nonnegative expected norm. Use double accumulation for norm/dot product and clamp only floating-point drift beyond `[-1, 1]`.

- [ ] **Step 4: Update search score validation**

Change `SearchService` to reject only non-finite scores or scores outside `[-1.0, 1.0]`. Do not transform scores and do not change the sparse provider’s output.

- [ ] **Step 5: Run dense and existing search tests**

Run:

```bash
./gradlew :common:test --tests '*DenseEmbeddingTest' --tests '*SearchServiceTest' --rerun-tasks
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit dense-vector behavior with its tests**

```bash
git add common/src/main/java/dev/jlo/kitsune/embedding/DenseEmbedding.java \
  common/src/main/java/dev/jlo/kitsune/search/SearchService.java \
  common/src/test/java/dev/jlo/kitsune/embedding/DenseEmbeddingTest.java \
  common/src/test/java/dev/jlo/kitsune/search/SearchServiceTest.java
git commit -m "feat: support dense embedding cosine vectors"
```

---

### Task 3: Add remote settings, credential resolution, and descriptor serialization

**Files:**
- Create: `common/src/main/java/dev/jlo/kitsune/embedding/remote/OpenAiCompatibleEmbeddingSettings.java`
- Create: `common/src/main/java/dev/jlo/kitsune/embedding/remote/EnvironmentCredentialResolver.java`
- Create: `common/src/main/java/dev/jlo/kitsune/embedding/remote/EmbeddingTextSerializer.java`
- Create: `common/src/test/java/dev/jlo/kitsune/embedding/remote/OpenAiCompatibleEmbeddingSettingsTest.java`
- Create: `common/src/test/java/dev/jlo/kitsune/embedding/remote/EnvironmentCredentialResolverTest.java`
- Create: `common/src/test/java/dev/jlo/kitsune/embedding/remote/EmbeddingTextSerializerTest.java`

**Interfaces:**
- `OpenAiCompatibleEmbeddingSettings` is an immutable validated record with endpoint URI, model, credential reference, document/query prefixes, timeout, batch size, response-byte limit, and retry count.
- `EnvironmentCredentialResolver` resolves only `env:<NAME>` references and returns empty for blank/unsupported/missing references.
- `EmbeddingTextSerializer.document(ItemDescriptor)` returns deterministic labeled text; `query(String)` returns normalized non-null query text without inventing semantic aliases.

- [ ] **Step 1: Write failing settings/resolver/serialization tests**

Assert endpoint/model validation, bounded numeric settings, environment reference behavior, secret non-leakage from profile identity material, and deterministic output regardless of insertion order in descriptor collections.

- [ ] **Step 2: Run the focused tests and verify the expected failure**

```bash
./gradlew :common:test --tests '*OpenAiCompatibleEmbeddingSettingsTest' \
  --tests '*EnvironmentCredentialResolverTest' \
  --tests '*EmbeddingTextSerializerTest' --rerun-tasks
```

Expected: compilation failure because the new types do not exist.

- [ ] **Step 3: Implement validated settings and resolver**

Require an absolute `https` endpoint by default; permit `http` only when `allowInsecureHttp` is explicitly true for local development. Reject userinfo, blank model names, nonpositive timeout/batch/limit values, retries above three, and dimensions outside the dense-vector bound. Resolve environment variables without logging their values.

- [ ] **Step 4: Implement deterministic descriptor text**

Emit stable labels for material, display text, lore, enchantment keys, attribute keys, traits, scalar metadata, and custom tags. Use the descriptor’s already sorted immutable collections, newline-delimited fields, and bounded text. Exclude amount from the semantic text.

- [ ] **Step 5: Run the focused tests and commit**

```bash
./gradlew :common:test --tests '*OpenAiCompatibleEmbeddingSettingsTest' \
  --tests '*EnvironmentCredentialResolverTest' \
  --tests '*EmbeddingTextSerializerTest' --rerun-tasks
git add common/src/main/java/dev/jlo/kitsune/embedding/remote \
  common/src/test/java/dev/jlo/kitsune/embedding/remote
git commit -m "feat: add remote embedding settings and credentials"
```

---

### Task 4: Implement the OpenAI-compatible HTTP provider

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `common/build.gradle.kts`
- Create: `common/src/main/java/dev/jlo/kitsune/embedding/remote/OpenAiCompatibleEmbeddingProvider.java`
- Create: `common/src/main/java/dev/jlo/kitsune/embedding/remote/OpenAiCompatibleEmbeddingProviderFactory.java`
- Create: `common/src/test/java/dev/jlo/kitsune/embedding/remote/OpenAiCompatibleEmbeddingProviderTest.java`

**Interfaces:**
- Factory ID: `remote:openai-compatible`.
- Factory construction consumes the settings map and `EmbeddingCredentialResolver` and returns an `EmbeddingProvider`.
- Provider exposes a package-visible batch method used by tests and `embed(ItemDescriptor)` delegates through a one-item batch.
- Provider ID contains a deterministic non-secret hash of endpoint/model/prefix/dimension/protocol settings; provider version is the adapter protocol version.

**OpenRouter compatibility:**
- The generic `remote:openai-compatible` factory is sufficient for OpenRouter.
- Use endpoint `https://openrouter.ai/api/v1/embeddings`, an OpenRouter model such as `openai/text-embedding-3-small`, and `env:KITSUNE_OPENROUTER_API_KEY` for the credential reference.
- Example settings map is documented in the provider framework design doc.

- [ ] **Step 1: Add failing mock-server integration tests**

Use `com.sun.net.httpserver.HttpServer` on an ephemeral port. Test:

1. POST path and JSON model/input/encoding format.
2. Bearer header from a resolver-provided secret without logging it.
3. Batch response ordering by `index`.
4. `embedQuery` uses the query prefix and `embed` uses descriptor serialization plus document prefix.
5. A 429 followed by a successful response retries once.
6. Malformed/missing vectors and exhausted retries fail.
7. Response bodies exceeding the configured limit fail.

- [ ] **Step 2: Run the integration tests and verify the expected failure**

```bash
./gradlew :common:test --tests '*OpenAiCompatibleEmbeddingProviderTest' --rerun-tasks
```

Expected: compilation failure because the provider and Jackson dependency are absent.

- [ ] **Step 3: Add the smallest JSON dependency and provider implementation**

Add a pinned Jackson databind version to the version catalog and `common` implementation dependencies. Use one `ObjectMapper` per provider, construct JSON only from validated values, enforce the configured serialized request-size limit while batching, send `HttpRequest` with bounded timeout and `Authorization` when available, read at most the configured response limit, and parse `data[index].embedding` as finite numeric arrays.

Retry only HTTP 429 and 500/502/503/504, with a bounded attempt count and bounded delay. Never retry malformed JSON, 4xx other than 429, or credential failures.

- [ ] **Step 4: Implement factory identity and construction**

Parse the settings map into `OpenAiCompatibleEmbeddingSettings`, resolve the configured credential reference, and construct the provider. Compute the provider identity hash from only non-secret canonical settings.

- [ ] **Step 5: Run provider tests and existing embedding tests**

```bash
./gradlew :common:test --tests '*OpenAiCompatibleEmbeddingProviderTest' \
  --tests '*SparseTagEmbeddingProviderTest' --tests '*EmbeddingProviderCatalogTest' \
  --rerun-tasks
```

Expected: all selected tests pass, including the local HTTP integration server tests.

- [ ] **Step 6: Commit the adapter and tests**

```bash
git add gradle/libs.versions.toml common/build.gradle.kts \
  common/src/main/java/dev/jlo/kitsune/embedding/remote/OpenAiCompatibleEmbeddingProvider.java \
  common/src/main/java/dev/jlo/kitsune/embedding/remote/OpenAiCompatibleEmbeddingProviderFactory.java \
  common/src/test/java/dev/jlo/kitsune/embedding/remote/OpenAiCompatibleEmbeddingProviderTest.java
git commit -m "feat: add OpenAI-compatible embedding provider"
```

---

### Task 5: Integrate the factory into the catalog and verify the full focused slice

**Files:**
- Modify: `common/src/main/java/dev/jlo/kitsune/embedding/EmbeddingProviderCatalog.java`
- Modify: `common/src/test/java/dev/jlo/kitsune/embedding/EmbeddingProviderCatalogTest.java`
- Modify: `docs/superpowers/specs/2026-08-04-kitsune-provider-framework-design.md` only if implementation details require a factual correction.

**Interfaces:**
- The catalog always includes the sparse factory and the OpenAI-compatible factory exactly once.
- Explicit external registrations may add non-colliding factories.
- The catalog remains construction-only; runtime command/config/profile switching is intentionally not added here.

- [ ] **Step 1: Write the failing built-in catalog test**

Assert that the catalog exposes both `builtin:sparse-v1` and `remote:openai-compatible`, and that creating the sparse provider still returns the existing provider identity.

- [ ] **Step 2: Run the focused test and verify the expected failure**

```bash
./gradlew :common:test --tests '*EmbeddingProviderCatalogTest' --rerun-tasks
```

Expected: failure because the remote factory is not registered by default.

- [ ] **Step 3: Register both built-in factories**

Add the sparse and OpenAI-compatible factories in deterministic order. Preserve duplicate detection for external registrations.

- [ ] **Step 4: Run the complete verification suite**

```bash
./gradlew :api:test :common:test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL` with all API/common tests passing.

- [ ] **Step 5: Review the final diff and commit the catalog integration**

```bash
git status --short
git diff --check
git add common/src/main/java/dev/jlo/kitsune/embedding/EmbeddingProviderCatalog.java \
  common/src/test/java/dev/jlo/kitsune/embedding/EmbeddingProviderCatalogTest.java
git commit -m "feat: register default embedding provider factories"
```

The final diff must contain only the focused provider framework slice and its tests; no command, OAuth, profile-schema, or unrelated formatting changes.
