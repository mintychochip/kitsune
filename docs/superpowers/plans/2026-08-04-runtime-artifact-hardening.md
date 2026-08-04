# Runtime Artifact and Empty-Index Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the three non-Bukkit runtime jars self-contained for common runtime dependencies and make Fabric report readiness after a successful empty scan, without wiring remote provider startup.

**Architecture:** Preserve the current manual jar assembly for Fabric, Forge, and NeoForge, but unpack every external jar from `:common`'s runtime classpath while excluding the separately copied `:api` artifact. Add one root archive contract task that checks the resulting jars. Extract the Fabric scan-readiness policy into a small package-private helper so an empty successful scan is covered by a focused unit test; the worker's exceptional path remains unchanged.

**Tech Stack:** Gradle 9.3 Kotlin DSL, Java 21, JUnit 5, `java.util.zip.ZipFile`, existing Fabric/Forge/NeoForge jar tasks.

## Global Constraints

- Preserve `builtin:sparse-v1` as the default and only runtime-selected provider in this change.
- Do not add endpoint/model/credential configuration, provider factory construction, remote startup behavior, or remote HTTP calls.
- Keep the existing API/common class-copy layout and SQLite bundling behavior.
- Treat a successful empty Fabric scan as ready; only worker failure remains exceptional.
- Tests must be written and observed failing before production behavior is changed.
- Keep packaging and Fabric readiness in separate atomic commits.

---

### Task 1: Add the failing artifact packaging contract

**Files:**
- Modify: `build.gradle.kts` after the existing project-wide publishing configuration
- Test command: root Gradle task `verifyBundledRuntimeDependencies`

**Interfaces:**
- Produces a root task named `verifyBundledRuntimeDependencies`.
- The task depends on `:fabric:jar`, `:forge:jar`, and `:neoforge:jar`.
- It checks each archive for `org/sqlite/JDBC.class` and `com/fasterxml/jackson/databind/ObjectMapper.class`.

- [ ] **Step 1: Add the failing verification task before changing jar assembly**

Add these imports at the top of `build.gradle.kts`:

```kotlin
import java.util.zip.ZipFile
```

Add this root task after the existing `subprojects` publishing block:

```kotlin
val bundledRuntimeModules = listOf(":fabric", ":forge", ":neoforge")
val requiredBundledRuntimeEntries = listOf(
    "org/sqlite/JDBC.class",
    "com/fasterxml/jackson/databind/ObjectMapper.class"
)

val verifyBundledRuntimeDependencies = tasks.register("verifyBundledRuntimeDependencies") {
    dependsOn(bundledRuntimeModules.map { "$it:jar" })
    doLast {
        bundledRuntimeModules.forEach { modulePath ->
            val artifact = project(modulePath).tasks.named("jar").get().outputs.files.singleFile
            ZipFile(artifact).use { archive ->
                requiredBundledRuntimeEntries.forEach { entry ->
                    check(archive.getEntry(entry) != null) {
                        "$modulePath artifact ${artifact.name} is missing $entry"
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyBundledRuntimeDependencies)
}
```

- [ ] **Step 2: Run the contract and verify the expected red result**

Run:

```bash
./gradlew verifyBundledRuntimeDependencies --rerun-tasks --console=plain
```

Expected: `BUILD FAILED` because the existing Fabric/Forge/NeoForge jars contain SQLite but omit `com/fasterxml/jackson/databind/ObjectMapper.class`.

- [ ] **Step 3: Keep the failing contract as the regression test**

Do not weaken the required-entry list or skip any of the three modules. The task is the packaging regression guard and will become green only after all three jar tasks include the common runtime closure.

### Task 2: Implement artifact dependency closure

**Files:**
- Modify: `fabric/build.gradle.kts:10-18`
- Modify: `forge/build.gradle.kts:11-19`
- Modify: `neoforge/build.gradle.kts:10-18`

**Interfaces:**
- Each `jar` task continues to copy API and common class outputs.
- Each `jar` task unpacks every external jar in `project(":common").configurations["runtimeClasspath"]` and excludes the `api-*` project artifact.
- The root verification task from Task 1 becomes green.

- [ ] **Step 1: Broaden the existing common dependency filter**

In each of the three `tasks.jar` blocks, replace the SQLite-only filter:

```kotlin
.filter { it.name.startsWith("sqlite-jdbc-") }
```

with:

```kotlin
.filter { it.extension == "jar" && !it.name.startsWith("api-") }
```

Keep the existing `from(zipTree(it))` call unchanged. This includes SQLite plus Jackson databind/core/annotations while avoiding duplicate API classes that are already copied from `:api`.

- [ ] **Step 2: Run the packaging contract**

Run:

```bash
./gradlew verifyBundledRuntimeDependencies --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL`; all three archives contain both required entries.

- [ ] **Step 3: Inspect the archive closure directly**

Run:

```bash
python3 - <<'PY'
import zipfile
from pathlib import Path
for module in ("fabric", "forge", "neoforge"):
    jars = sorted((Path(module) / "build" / "libs").glob(f"{module}-*.jar"))
    jar = next(path for path in jars if "sources" not in path.name and "javadoc" not in path.name)
    with zipfile.ZipFile(jar) as archive:
        assert archive.getinfo("org/sqlite/JDBC.class")
        assert archive.getinfo("com/fasterxml/jackson/databind/ObjectMapper.class")
    print(module, jar.name, "runtime closure verified")
PY
```

Expected: one `runtime closure verified` line per module.

- [ ] **Step 4: Commit the packaging unit**

```bash
git add build.gradle.kts fabric/build.gradle.kts forge/build.gradle.kts neoforge/build.gradle.kts
git commit -m "fix: bundle common runtime dependencies in mod jars"
```

### Task 3: Add the failing Fabric empty-scan regression

**Files:**
- Modify: `fabric/src/test/java/dev/jlo/kitsune/fabric/FabricAdapterContractTest.java`
- Modify: `fabric/src/main/java/dev/jlo/kitsune/fabric/FabricRuntime.java:220-260`

**Interfaces:**
- Adds package-private static method:

```java
static boolean scanIsReady(List<?> current, Map<?, ?> previous)
```

- `scanIsReady` returns `true` for every successful scan, including empty `current` and `previous` collections.
- `scanAndSubmit` calls this policy after the worker successfully applies the scan.

- [ ] **Step 1: Write the regression test before adding the helper**

Add imports to `FabricAdapterContractTest`:

```java
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
```

Add this test method:

```java
@Test
void emptySuccessfulScanIsReady() {
    assertTrue(FabricRuntime.scanIsReady(List.of(), Map.of()));
}
```

- [ ] **Step 2: Run the focused test and verify the expected red result**

Run:

```bash
./gradlew :fabric:test --tests '*FabricAdapterContractTest.emptySuccessfulScanIsReady' --rerun-tasks --console=plain
```

Expected: test compilation fails because `FabricRuntime.scanIsReady(List, Map)` does not exist yet.

- [ ] **Step 3: Implement the minimal readiness policy**

Add this package-private method to `FabricRuntime`:

```java
static boolean scanIsReady(List<?> current, Map<?, ?> previous) {
    Objects.requireNonNull(current, "Current scan must not be null");
    Objects.requireNonNull(previous, "Previous index state must not be null");
    return true;
}
```

Replace:

```java
if (!current.isEmpty() || !previous.isEmpty()) indexReady.complete(null);
```

with:

```java
if (scanIsReady(current, previous)) indexReady.complete(null);
```

The method intentionally ignores collection contents: the worker reaching this point proves the scan succeeded, and an empty result is valid state.

- [ ] **Step 4: Run the focused test and verify green**

Run:

```bash
./gradlew :fabric:test --tests '*FabricAdapterContractTest.emptySuccessfulScanIsReady' --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL` and the new test passes.

- [ ] **Step 5: Commit the Fabric readiness unit**

```bash
git add fabric/src/main/java/dev/jlo/kitsune/fabric/FabricRuntime.java fabric/src/test/java/dev/jlo/kitsune/fabric/FabricAdapterContractTest.java
git commit -m "fix: complete Fabric readiness after empty scans"
```

### Task 4: Full verification and scope audit

**Files:**
- No source changes expected; inspect the two implementation commits and generated archives.

- [ ] **Step 1: Run the full clean build and checks**

Run:

```bash
./gradlew clean build --console=plain
./gradlew check --rerun-tasks --console=plain
```

Expected: both commands succeed with no test failures.

- [ ] **Step 2: Count test outcomes**

Parse the module `build/test-results/test/TEST-*.xml` reports and confirm zero failures, errors, and skipped tests.

- [ ] **Step 3: Run default-provider startup smoke only**

Run the existing Paper and Fabric default-provider server smoke paths. Confirm the logs contain `Kitsune enabled` / `Kitsune Fabric runtime enabled`. Do not modify configuration to `remote:openai-compatible` and do not test remote startup.

- [ ] **Step 4: Audit the final scope**

Confirm:

- Fabric/Forge/NeoForge jars contain Jackson and SQLite.
- Fabric empty scans complete readiness.
- Remote provider startup/configuration code is unchanged.
- `git status --short` contains no uncommitted implementation changes after the atomic commits.
