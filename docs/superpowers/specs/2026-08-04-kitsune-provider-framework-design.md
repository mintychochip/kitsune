# Kitsune Provider Framework and OpenAI-Compatible Embeddings

**Date:** 2026-08-04  
**Status:** Approved first-slice scope for implementation

## Goal

Add a configurable provider framework and a generic OpenAI-compatible embedding provider while preserving the deterministic sparse provider as the default and offline path.

## First-slice scope

The first slice includes:

- A public provider-factory and credential-resolution abstraction.
- A catalog for constructing configured embedding providers.
- A bounded dense-vector implementation.
- A generic OpenAI-compatible HTTP embeddings provider using Java `HttpClient`.
- Stable descriptor serialization and configurable document/query prefixes.
- API-key resolution through a non-secret credential reference.
- Mock-server integration tests for requests, batching, parsing, retries, and failures.
- Correct raw cosine handling for dense vectors, including negative scores.

The first slice does not include:

- In-game `/kitsune provider ...` commands.
- OAuth or refresh-token handling.
- Browser setup or a hosted credential broker.
- Profile-scoped shadow vectors, staged cutover, rollback, or schema migration.
- Gemini-native transport or other vendor-specific adapters.
- ANN or vector-database retrieval.

## Provider architecture

`EmbeddingProvider` remains the runtime contract for item and query embeddings. A new `EmbeddingProviderFactory` constructs providers from non-secret settings and an `EmbeddingCredentialResolver`. A common `EmbeddingProviderCatalog` owns factory registration and creation; platform runtimes can adopt it later without changing the search pipeline.

The built-in factory remains `builtin:sparse-v1`. The first remote factory is `remote:openai-compatible`. Its runtime provider identity contains a deterministic non-secret fingerprint of the endpoint, model, prefixes, dimensions, and adapter protocol version. This ensures a model/profile change cannot silently reuse vectors from a different configuration under the existing provider ID/version checks.

## Configuration and credentials

Remote settings are represented by an immutable validated configuration object:

- embeddings endpoint URI;
- model name;
- non-secret credential reference;
- document prefix and query prefix;
- request timeout;
- maximum batch size;
- maximum request and response bytes;
- bounded retry count.

The credential reference is resolved only at provider construction or request time. The first resolver implementation supports environment references such as `env:KITSUNE_EMBEDDING_API_KEY` and a no-credential mode for explicitly permitted local endpoints. Raw keys are never stored in configuration objects, SQLite, logs, or provider identity fingerprints.

HTTPS is the default endpoint requirement. Local HTTP endpoints are accepted only through explicit configuration. Endpoint-policy enforcement and administrator commands are later work; this slice validates URI shape and credential references without opening a server-side setup surface.

## OpenAI-compatible HTTP contract

The provider sends a POST request with the standard shape:

```json
{
  "model": "model-name",
  "input": ["document one", "document two"],
  "encoding_format": "float"
}
```

The adapter sends `Authorization: Bearer <credential>` when a credential resolves. It accepts a response containing indexed embedding objects, requires exactly one vector per input, restores response order by index, and rejects malformed, missing, non-finite, zero-norm, or dimension-inconsistent vectors.

The provider supports batch item embedding. A single query is sent as a one-item batch. Requests have bounded connect/read timeouts, serialized request-size limits, response-body limits, and retries for HTTP 429 and transient 5xx responses. Retry counts and delays are bounded; arbitrary failures are surfaced without fallback to another provider.

Item descriptors are converted to deterministic text using fixed field labels and their already-canonical sorted collections. Query text is passed through the configured query prefix. Document text is passed through the configured document prefix. Prefixes are part of provider identity because changing them changes the vector space.

## OpenRouter compatibility

OpenRouter exposes an OpenAI-compatible embeddings endpoint at `https://openrouter.ai/api/v1/embeddings`. Kitsune can use it through the generic `remote:openai-compatible` factory with an OpenRouter API key credential. Example settings map:

```java
Map.of(
  "endpoint", "https://openrouter.ai/api/v1/embeddings",
  "model", "openai/text-embedding-3-small",
  "credential-reference", "env:KITSUNE_OPENROUTER_API_KEY",
  "document-prefix", "",
  "query-prefix", "",
  "request-timeout-millis", "15000",
  "max-batch-size", "32",
  "max-response-bytes", "4194304",
  "max-request-bytes", "1048576",
  "max-retries", "2",
  "dimensions", "0"
)
```

## Dense vector contract

`DenseEmbedding` stores bounded finite float components, computes a double-precision norm and raw cosine, and encodes a versioned binary payload. It validates provider ID and version compatibility before comparison.

Raw cosine is mathematically defined in `[-1, 1]`. `SearchService` must accept that range instead of treating negative dense cosine as invalid. Existing sparse vectors remain in their current nonnegative subset, and the default minimum score remains `0.30` until provider-specific quality evaluation justifies a separate threshold policy.

## Verification

Tests must prove:

- factory construction and credential resolution;
- deterministic provider identity changes when non-secret model settings change;
- dense-vector round trips, dimension rejection, zero-norm behavior, and negative cosine;
- request method, URL, headers, model, prefixes, and batch ordering;
- valid response parsing and malformed response rejection;
- bounded retry on 429/5xx and failure after retry exhaustion;
- request and response body-size enforcement and timeout behavior;
- existing sparse embedding and search behavior remains green.

A local JDK HTTP server is used for integration tests. Tests do not contact real provider services or contain credentials.
