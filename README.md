# Exception Seam Gradle Plugin

Finds every `catch` seam in your Java sources, works out which exceptions can reach it, and fails (or warns) on
catches that are too broad, wider than needed, empty, or dead. Spring-aware. Suggests a narrower catch where it can. Reports as console text, JSON, HTML, SARIF and Markdown.

## Why you might need this

A `catch` that is broader than it needs to be does more than look untidy. It decides, for every exception that can reach
it, that carrying on is safe. For some exceptions that decision is wrong, and the plugin exists to show you where it is made.

### Java `Error` should almost never be caught

`Throwable` splits into `Exception` and `Error`. An `Error` means the JVM, or the program's ability to run, is
compromised: `OutOfMemoryError`, `StackOverflowError` and `InternalError` (the `VirtualMachineError` family), the
`LinkageError` family (`NoClassDefFoundError`, `NoSuchMethodError`, `UnsatisfiedLinkError`), and `AssertionError`.

After an `OutOfMemoryError` the next allocation, possibly inside your own handler, can fail as well. A
`NoClassDefFoundError` means the deployment is wrong, and no handler will fix that. The robust response is to log and let
the process die, so an orchestrator can restart it. The JVM has flags for this (`-XX:+ExitOnOutOfMemoryError`,
`-XX:+CrashOnOutOfMemoryError`). Application code should not throw `Error` either.

The one reasonable place to catch `Throwable` is a top-level supervisor (the `main` thread, a job runner, a request
handler) that logs the failure and shuts down cleanly. Every other `catch (Throwable)` is in the plugin's broad set, and
a `catch (Exception)` is nearly as bad when the body carries on.

### Swallowing a bug leaves inconsistent state

A catch that logs and carries on turns a failed operation into an apparently successful one. The system then runs on
from a state nobody intended:

- **Messaging.** A listener catches `Exception`, logs it and returns. The message is acknowledged but was never processed
  to completion, so it is lost for good. Failing instead would have left it on the queue for redelivery or a dead-letter queue.
- **Transactions.** Step three of five throws and the code catches it and carries on. Steps one and two are committed,
  four and five run against half-updated data, and nothing records that anything went wrong. Letting the exception
  propagate would have rolled the whole transaction back.
- **Objects.** A method that threw halfway has already mutated its receiver. Code that goes on using that object is working
  with an invariant that no longer holds.

Failing is the safe outcome: the caller or the platform sees the failure, rolls back or retries, and an operator is told.
That is why the plugin rates a broad catch that does not rethrow on every path as `SWALLOWED_EXCEPTION`, at CRITICAL,
even when it logs the exception or records a failure result. A catch that has to stay (a deliberate boundary) is marked
with `// exception-seam:safe`.

Wrapping is not free either. Turning a checked exception into a `RuntimeException` removes the compiler's reminder that
the call can fail, and a bare `new RuntimeException(e)` hides the type a caller might have recovered from. Wrap in a
specific unchecked type and keep the original as the cause. When a broad catch throws a new exception without the
caught one as its cause, the plugin's suggestion says to pass it.

### Exceptions that usually mean a bug

Most checked exceptions describe external conditions a caller might recover from. The ones below usually mean the
code, or its configuration, is wrong. They should be effectively resolved, or left to propagate to a clean failure. They should not be
caught broadly and ignored.

**Unchecked (`RuntimeException`)**

| Exception | What it usually means |
|---|---|
| `IllegalStateException` | An operation was attempted in the wrong state: a closed resource, the wrong lifecycle phase, a broken invariant. Work done before the throw may already have changed state. |
| `ConcurrentModificationException` | A collection was changed during iteration. The JDK documents it as a bug detector, and behaviour afterwards is undefined. |
| `NullPointerException` | A value was `null` where one was required. |
| `IllegalArgumentException` | A caller passed an invalid argument. |
| `IndexOutOfBoundsException`, `ArrayIndexOutOfBoundsException` | A bad index. |
| `ClassCastException` | The wrong type. |
| `ArithmeticException` | For example an integer divide by zero. |
| `UnsupportedOperationException` | The object does not support the operation. |
| `NoSuchElementException` | An iterator, stack or `Optional` was misused. |

**Checked**

| Exception | What it usually means |
|---|---|
| `CloneNotSupportedException` | `clone()` was called on a class that is not `Cloneable`, or the implementation is wrong. |
| `ClassNotFoundException` | A wrong class name, a missing dependency or a classpath problem. |
| `InstantiationException` | Reflection tried to create an abstract class, an interface or a class with no accessible constructor. |
| `IllegalAccessException` | Reflection reached a member that is not accessible. |
| `NoSuchMethodException`, `NoSuchFieldException` | A wrong name or signature in reflection. |
| `InvocationTargetException` | Wraps whatever the reflective call threw, and the cause may itself be a bug. |
| `UnsupportedEncodingException` | A hard-coded, invalid charset name. Use `StandardCharsets`. |
| `ParseException` | Bad external input, but also output your own code generated that its parser rejects. |

By contrast, `IOException` and its subclasses, and `SQLException`, normally describe external failures. Swallowing
`InterruptedException` without restoring the interrupt flag is a bug of its own.

## Requirements

- **Java 17 or later** to run Gradle. The plugin is compiled for Java 17.
- **Gradle 9.6.1.** This is the version the plugin's tests run against. Other versions are untested.
- **The `java` plugin** on the project you apply it to. The task reads the `main` source set (and `test` if `includeTests` is on).
- **Java sources only.** Kotlin and other JVM languages are not analysed. Source is parsed at up to the Java 25 language level.

## Quick start

```kotlin
plugins {
    java
    id("io.github.johncoleman-thoughtworks.exception-seam") version "1.0.0"
}

exceptionSeam {
    failOnSeverity.set("HIGH")
    reportFormats.addAll("html", "sarif")
}
```

`./gradlew checkExceptionSeams` runs the analysis; `check` depends on it.

### Configuring subprojects from the root build

In a multi-project build, apply the plugin in the root `plugins { }` block (this puts it on the build classpath), then
apply and configure it for every subproject from `subprojects { }`:

```kotlin
// root build.gradle.kts
plugins {
    java
    id("io.github.johncoleman-thoughtworks.exception-seam") version "1.0.0"
}

exceptionSeam {
    failOnSeverity.set("NONE")
    reportFormats.addAll("console", "html")
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.github.johncoleman-thoughtworks.exception-seam")

    extensions.configure<com.johncolemantw.exceptionseam.ExceptionSeamExtension> {
        failOnSeverity.set("NONE")
        reportFormats.addAll("console", "html")
    }
}
```

The type-safe `exceptionSeam { }` accessor is not generated inside `subprojects { }` for a plugin applied with
`apply(plugin = ...)`, so configure it through `extensions.configure<ExceptionSeamExtension>` using the fully qualified
class name. The root project and each subproject have their own extension, so the settings must be repeated for both, as
above. Each subproject then gets its own `checkExceptionSeams` task and report.

## Using the plugin in a project

Apply it in each module's own `plugins { }` block. That is also what makes the `exceptionSeam { }` accessor available in
Kotlin DSL; applying it from `subprojects { }`, `apply(plugin = ...)` or an init script gives "Unresolved reference
'exceptionSeam'" unless you configure it via `extensions.configure` (see
[Configuring subprojects from the root build](#configuring-subprojects-from-the-root-build)).

| Route | Use it for |
|---|---|
| Gradle Plugin Portal | Public use. Nothing to configure; `id(...) version "..."` is enough. |
| Private Maven repository | Internal use. Add the repository under `pluginManagement { repositories { ... } }` in `settings.gradle.kts`. |
| `includeBuild` | Trying it or developing it. In `settings.gradle.kts`, first thing: `pluginManagement { includeBuild("/path/to/exception-seam-plugin") }`, then use the plugin id with no version. |
| `publishToMavenLocal` | Testing the published artifact. Add `mavenLocal()` to `pluginManagement.repositories`. |

To try it on an existing codebase, start with `failOnSeverity.set("NONE")` and read the HTML report before tuning
`allowList`.

## What it flags

| Issue | Default severity | Meaning |
|---|---|---|
| `SWALLOWED_EXCEPTION` | CRITICAL | Empty catch body, or a catch of `Exception`, `RuntimeException` or `Throwable` that does not rethrow on every path (logging and returning a default counts as swallowing). The most severe defect, listed before everything else. |
| `BROAD_CATCH` | HIGH (MEDIUM if the body wraps the exception, or it is a `RuntimeException` catch with nothing known behind it) | Catches `Exception`, `RuntimeException` or `Throwable` and throws on every path. A broad catch that can swallow is reported as `SWALLOWED_EXCEPTION` instead, not as both. A block that does cleanup and then only ever throws the caught exception itself (`throw e;`) is not reported, because the caller sees the original exception. |
| `OVERLY_WIDE_CATCH` | MEDIUM | Catches a supertype although only narrower subtypes can reach it. Not reported when the block only ever rethrows the caught exception unchanged (for example rollback, then `throw e;`). |
| `DEAD_CATCH` | LOW | Catches a project-defined exception that nothing reachable throws. |

Example console output:

```
src/main/java/com/example/OrderService.java:87
  [HIGH] catch (Exception e) is overly broad
  Reachable: DataAccessException, IOException, IllegalArgumentException
  Suggestion: catch IOException and let DataAccessException | IllegalArgumentException (unchecked) propagate unless you can recover from them, or translate to a domain exception
  Suggested:  catch (java.io.IOException e)
```

### Suggested catch

For broad and overly wide catches the plugin proposes a replacement catch header, shown in every report format
(`suggestedCatch` in JSON and SARIF properties):

- For a broad catch, checked exceptions (and ones whose hierarchy is unknown, typically library exceptions) go into the
  suggested catch. Unchecked exceptions are named separately as ones that can usually propagate.
- Related types are reduced to the most general one, so the suggestion is always a legal multi-catch
  (`CharacterCodingException` and `MalformedInputException` become just `CharacterCodingException`).
- Other alternatives of an original multi-catch are kept.
- Types are written fully qualified (bare for `java.lang`), so the line compiles without new imports.
- Nothing is suggested when no reachable exception was found.
- The `SWALLOWED_EXCEPTION` finding on a broad catch carries the same reachable list and suggested catch, so the CRITICAL entry says what to catch instead.

The suggestion reflects what the analysis could see. Treat it as a starting point and check it against runtime data
where you have it.

### Likely bugs

When a reachable exception is one that usually means a bug rather than an external failure (the tables in
[Why you might need this](#why-you-might-need-this): `IllegalStateException`, `NullPointerException`,
`ConcurrentModificationException`, reflection exceptions and so on), the report adds a line naming it and why:

```
  Likely bugs: IllegalStateException (operation in the wrong state; earlier work may already have changed state) - fix, or let them fail; do not swallow
```

It appears in the console, HTML and Markdown reports, and as `likelyBugs` in JSON and in SARIF properties. It is
informational: it never changes a severity, and it only lists exceptions the analysis found reachable from the catch.

## Configuration

| Property | Default | Description |
|---|---|---|
| `failOnSeverity` | `"HIGH"` | `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` or `NONE`. The task fails if any active issue is at or above this level. |
| `includeTests` | `false` | Also analyse the test source set. |
| `maxCallDepth` | `5` | How many method calls deep the reachability walk goes. |
| `excludePackages` | `java.`, `javax.`, `jakarta.`, `org.springframework` | Callee types with these name prefixes are not entered. |
| `springAware` | `true` | Apply the Spring rules below. |
| `classpathAware` | `true` | Read the `throws` clauses of JDK and library methods and constructors (Jackson's `writeValueAsString`, `Files.readString`, `Thread.sleep`, ...) from the compile classpath. See [Library calls](#library-calls). |
| `reportFormats` | `console`, `json` | Any of `console`, `json`, `html`, `sarif`, `md`. |
| `allowList` | empty | Places where broad catches are intentional (see below). |
| `runtimeDataFile` | unset | JSON of exceptions observed at runtime, merged into the static results (see below). |

Reports are written to `build/reports/exception-seam/`: `exception-seam.json`, `index.html`, `exception-seam.sarif`, `exception-seam.md`.
Markdown suits PR comments and job summaries; SARIF suits code-scanning tools; HTML is for reading locally.

### Choosing formats on the command line

`--report-format` overrides `reportFormats` for one run. Repeat it or comma-separate the values:

```bash
./gradlew checkExceptionSeams --report-format=html --report-format=sarif
./gradlew checkExceptionSeams --report-format=console,json
```

The option belongs to the task, so it goes right after `checkExceptionSeams` (it does not work with `check`).
Unknown formats fail the build with the list of supported ones.

### Allow-list

Each entry is a glob on the file path relative to the root project, optionally followed by `#` and a glob on the method name.
A pattern with no `/` matches the file name only. Only `BROAD_CATCH` issues are suppressed; they remain in the
reports, marked as allow-listed (and as SARIF `suppressions`).

```kotlin
exceptionSeam {
    allowList.addAll(
        "GlobalExceptionHandler.java",
        "src/main/java/com/example/Main.java#main",
    )
}
```

### Turning a catch off in the source

For a false positive, or a catch that is intentional, put a comment inside the catch block that starts with
`exception-seam:safe`:

```java
try {
    roles = objectMapper.writeValueAsString(roleList);
} catch (Exception exp) {
    // exception-seam:safe - audit logging must never break the request
    log.error("Failed to serialize roles", exp);
}
```

- Any `//` or `/* */` comment after the catch opens counts, as long as its text starts with the marker (case-insensitive).
  Text after the marker is for people: say why it is safe. A comment that only mentions the marker mid-sentence does not count.
- That catch is not scanned at all: no analysis and no findings of any kind, so nothing appears in the reports for it,
  and it does not count among the catch seams analysed.
- A marker above the `try` or above the `catch`, or in the try body, does nothing. It only counts inside the catch block.
- It applies to its own catch. A marker inside a catch nested in the block turns off the nested catch, not the outer one.

Unlike the allow-list, which only covers `BROAD_CATCH` and keeps showing the issue as allow-listed, this removes the
catch from the analysis entirely.

## Runtime data

Static analysis cannot see everything (library calls, proxies). If you can record which exceptions actually arrive at
each catch, point the plugin at the file and they are merged into the reachable set:

```json
{"observations": [
  {"file": "src/main/java/com/example/OrderService.java", "line": 87,
   "exceptions": ["java.io.IOException", {"type": "com.example.Boom", "count": 3}]}
]}
```

`file` matches the end of the seam's root-relative path, and `line` can be any line of the catch clause. Exceptions
the static pass also found are marked as observed; the rest are added with origin `RUNTIME_OBSERVED`. They feed the
same evaluation, so an observed exception also stops a catch being reported as dead. Changing the file re-runs the task.

## Library calls

With `classpathAware` on, a call to a method that is not in your sources is looked up on the compile classpath (and the JDK
running Gradle), and the exceptions in its `throws` clause become reachable, so `objectMapper.writeValueAsString(x)` brings
in `JsonProcessingException` and a catch of `Exception` around it is suggested as `catch (com.fasterxml.jackson.core.JsonProcessingException e)`.

- The receiver's type must be known: a variable, parameter or field with a declared type, a static call on a class
  name, or `new` for constructors. Chained calls (`a.b().c()`) and `var` receivers are not resolved.
- Overloads are chosen by argument count and, where it is evident (literals, `X.class`, casts, `new`, declared
  variables), by argument type. If several overloads still fit, only the exceptions all of them declare are used, so a
  suggestion never names an exception the chosen overload might not throw.
- `throws X` with a type variable (`Optional.orElseThrow`) says nothing about what is thrown and is skipped.
- Only the declared signature is read: library bodies are not walked, and unchecked exceptions that are not declared
  are not known. `excludePackages` does not apply to this lookup, only to walking into source methods.
- Library classes are loaded without being initialised, from a class loader that sees only the JDK and the compile
  classpath. A class that cannot be loaded (missing dependency, newer class-file version) is treated as unknown.
- The classpath is a task input, so changing a dependency re-runs the check.
- When a type used as a receiver cannot be read, the build log gets one warning naming it and the reason (for example
  "not found on the compile classpath" or a `NoClassDefFoundError`), so a missing suggestion can be diagnosed.

## Spring awareness

With `springAware` on, exceptions that are invisible in the source are added where a call crosses a bean boundary
(a call from a class to itself bypasses the Spring proxy, so nothing is added):

- calls into `@Transactional` methods or classes add `TransactionException`;
- Spring Data repositories, `JdbcTemplate`/`JdbcClient` and `@Repository` beans add `DataAccessException`;
- calls into `@Validated` classes or methods add `ConstraintViolationException`;
- exceptions covered by a specific `@ExceptionHandler` in a `@ControllerAdvice` (or in the same controller) are marked as
  already handled. Catch-all handlers (`Exception`, `RuntimeException`, `Throwable`) are ignored.

## CI: uploading SARIF to GitHub code scanning

```yaml
- run: ./gradlew checkExceptionSeams --continue
  continue-on-error: true
- uses: github/codeql-action/upload-sarif@v3
  with:
    sarif_file: build/reports/exception-seam/exception-seam.sarif
```

## Note: exception containment in modular monoliths

A broad catch is sometimes right, at the boundary of a module. The aim there is the bulkhead pattern: a failing module
should take itself out, not the whole application. Inside one JVM this is partial isolation, built from a few layers:

- **Isolate resources.** Give each module its own thread pools and connection pools, so one module cannot starve the others.
- **Contain failures at the boundary.** Use timeouts and circuit breakers on cross-module calls, and catch internal
  exceptions at the module's public API, returning a typed result or a module-specific exception. Mark a deliberate
  boundary catch with `// exception-seam:safe`.
- **Make modules switchable.** A per-module kill switch and health indicator let operators disable one module without
  taking the application down.
- **Decouple communication.** Prefer asynchronous events between modules over synchronous calls, and catch exceptions
  inside each module's schedulers and listeners.
- **Enforce the boundaries.** Use architecture tests (ArchUnit, Spring Modulith) so modules do not reach into each other's internals.

One process cannot isolate memory exhaustion, runaway CPU loops, JVM-wide failures or shared static state. Modules that
must survive those need to be separate deployables.

## Limitations

The analysis is a static approximation over your Java sources.

- Method calls are resolved by name, argument count and the receiver's declared type. Project methods are walked into;
  for library and JDK methods only the declared `throws` clause is read (see [Library calls](#library-calls)). Undeclared
  library exceptions are not seen unless a Spring rule adds them.
- Unchecked exceptions appear only when explicitly thrown or listed in a `throws` clause.
- Lambda bodies, anonymous classes and local classes are not entered; try-with-resources `close()` exceptions are not modelled.
- Source is parsed at the Java 25 language level (or the newest level the bundled JavaParser supports, if it has no `JAVA_25`). Files that still fail to parse are skipped with a warning. Kotlin sources are not analysed.
- `DEAD_CATCH` is limited to project-defined exceptions for the same reason.
- An exception whose class is not known to the plugin (typically a third-party checked exception) is assumed to be
  covered by `catch (Exception)` and `catch (Throwable)`, and not by narrower catches.
- Whether a JDK or library exception is checked, and how exceptions relate, is read by reflection from the compile classpath
  and the JDK running Gradle. The same sources can therefore give different results on a different JDK version, and the JDK version is not a task
  input, so build-cache entries can be shared across JDKs. Otherwise the analysis is deterministic: no AI, no
  randomness, sorted file and issue order.
- Whether a catch body counts as log-only, rethrow or wrap is a name-based pattern (loggers named `log`, `logger`, ...).

## Publishing

```
./gradlew build
./gradlew publishPlugins -PreleaseVersion=1.0.0        # Gradle Plugin Portal (gradle.publish.key / gradle.publish.secret)
./gradlew publish -PinternalRepoUrl=https://repo.example.com/maven \
    -PinternalRepoUser=... -PinternalRepoPassword=...   # internal Maven repository
```

The build also publishes a plugin marker artifact (`<plugin id>:<plugin id>.gradle.plugin`), which is what lets
consumers write `id("...") version "..."` instead of a classpath dependency.

The Portal rejects `-SNAPSHOT` versions.

### Releasing

Pushing a tag such as `v1.0.0` runs [`.github/workflows/release.yml`](.github/workflows/release.yml). It builds and
tests, publishes to the Gradle Plugin Portal with the tag as the version, and creates a GitHub Release with the jars
attached. It needs two repository secrets, `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`, from your Plugin Portal
API Keys page.

```
git tag v1.0.1
git push origin v1.0.1
```

## Development

```
./gradlew build            # unit tests + TestKit functional tests
./gradlew functionalTest
```

## Licence

Apache License 2.0. See [LICENSE](LICENSE).

## Disclaimer

This is an independent, unofficial, personal open-source project. It is not endorsed, sponsored, supported or
approved by Thoughtworks or any other employer or organisation, and it is not a Thoughtworks product or service. Its
name, group and GitHub account name do not imply any affiliation. Any views or opinions expressed here are the
author's own.

The software is provided under the Apache License 2.0 "AS IS", without warranties or conditions of any kind, express
or implied, including any warranty of merchantability, fitness for a particular purpose or non-infringement. The
analysis is a static approximation (see [Limitations](#limitations)): it can miss real problems and report
false positives, so do not rely on it as your only safeguard. You use it at your own risk, and the authors and
contributors are not liable for any damages arising from its use. See sections 7 and 8 of the licence for the
full terms.
