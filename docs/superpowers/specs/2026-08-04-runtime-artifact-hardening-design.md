# Runtime Artifact and Empty-Index Hardening

**Date:** 2026-08-04  
**Status:** Design written; user review pending

## Goal

Harden the two identified non-startup issues found in the repository while preserving the current runtime startup contract:

1. Fabric, Forge, and NeoForge artifacts must contain the external runtime dependencies required by the common code they already bundle.
2. Fabric must treat a successful empty scan as a ready index.

Remote embedding provider construction and configuration inside platform startup are explicitly deferred. `builtin:sparse-v1` remains the only runtime-selected provider in this slice.

## Artifact dependency closure

The Fabric, Forge, and NeoForge `jar` tasks currently copy API/common classes and selectively unpack SQLite. That selective filter omits Jackson even though `common` declares Jackson as a runtime dependency and bundles the public OpenAI-compatible provider classes.

Each affected `jar` task will unpack all external jars resolved from `:common`'s `runtimeClasspath`, excluding the `:api` project artifact whose classes are copied separately. This keeps the existing self-contained artifact model, includes SQLite and Jackson transitives, and follows future common runtime dependencies without another filename-specific filter.

A root Gradle verification task will depend on the three jar tasks and inspect each archive. It will fail if required entries are absent, including:

- `org/sqlite/JDBC.class`
- `com/fasterxml/jackson/databind/ObjectMapper.class`

The check validates packaging, not remote startup. No provider settings, credentials, or runtime factory wiring will be added.

## Fabric readiness

`FabricRuntime.scanAndSubmit()` currently completes `indexReady` only when the scan contains a current or previously indexed container. That leaves an empty world permanently warming even after its scan job succeeds.

The successful worker callback will complete `indexReady` unconditionally. A successful empty scan is authoritative: it means the scanned state contains no indexable roots. Existing exceptional completion behavior remains unchanged.

A focused regression test will exercise the readiness decision for an empty successful scan. The test will be written and observed failing before the production change.

## Error handling and compatibility

- Dependency packaging failures fail the Gradle verification task with the module and missing archive entry.
- Scan worker failures still complete `indexReady` exceptionally and preserve current search error mapping.
- Existing default provider behavior remains unchanged.
- No startup integration, configuration schema, credential resolution, or remote HTTP behavior changes.

## Verification

1. Add the artifact packaging regression check and the Fabric empty-scan regression test.
2. Run each focused check and observe the expected pre-fix failure.
3. Apply the minimal build/runtime changes.
4. Re-run focused checks and the complete `clean build`/`check` suite.
5. Inspect Fabric, Forge, and NeoForge jars for the required classes.
6. Run the existing Paper and Fabric default-provider startup smoke paths only; do not test or add remote-provider startup.
