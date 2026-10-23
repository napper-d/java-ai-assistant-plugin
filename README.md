# Course AI Assistant

IntelliJ IDEA plugin for CST3492 Assignment A, Group C (F0 + F2).

## Current status

Stage 1 establishes the build baseline and draft module contracts. The generated
sample tool window is retained: click **Shuffle** to display a random number.
RAG, LLM calls, code-context actions and reviewed changes are not implemented yet.

## Development baseline

| Component | Version |
| --- | --- |
| JDK / Java language target | 21 |
| Gradle Wrapper | 9.0.0 |
| IntelliJ Platform Gradle Plugin | 2.10.2 |
| Target IntelliJ IDEA | 2025.2.4 (IU) |
| Kotlin Gradle Plugin / JVM target | 2.1.20 / 21 |
| Plugin ID | `edu.teamc.course-ai-assistant` |

Use the checked-in Wrapper. Keep target versions unchanged until a compatibility
review; the IDEA used to edit the plugin may be newer than the target IDEA.

## Open and configure

1. Install JDK 21, for example Eclipse Temurin.
2. Open **this directory**, containing `settings.gradle.kts`, as a Gradle project.
3. Set **Project SDK** to JDK 21 and **Language level** to 21 without preview.
4. Set **Gradle JVM** to JDK 21 or the daemon criteria supplied by this project.
5. Reload Gradle. Initial dependency downloads need network access.

Java/Kotlin toolchains and daemon criteria require 21. No machine-specific JDK path
is committed. Install a local JDK 21; automatic JDK download repositories are not configured.

## Build and verify

From a terminal at this directory:

```powershell
.\gradlew.bat --version
.\gradlew.bat buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

On macOS/Linux use `./gradlew`. `--version` may report a different **Launcher JVM**
when the shell's `JAVA_HOME` differs; the daemon and compilation must use 21.
The project's daemon criteria select that daemon.

The installable ZIP is generated under `build/distributions/`. It is the plugin
baseline, not the assignment's complete source/report/slides package. The two
verification tasks check project configuration and plugin structure, not UI
behavior or compatibility with every IDEA version.

Once dependencies are cached, add `--offline` if needed. If a dependency is absent,
retry online rather than changing versions.

## Run the sample

1. Run **Run IDE with Plugin**, or execute `.\gradlew.bat runIde`.
2. Wait for the separate development IDEA to open.
3. Open a small Java project in that IDEA.
4. Select **View > Tool Windows > My Tool Window** (ID: `MyToolWindow`).
5. Check that **The random number is: ?** and **Shuffle** appear.
6. Click **Shuffle** and check that an integer from 0 to 999 appears.
7. Close the development IDEA to finish `runIde`.

Source-code comments in a sample Java file are not this plugin's sample UI.

Sandbox log:

```text
build/idea-sandbox/IU-2025.2.4/log/idea.log
```

The shared run configuration points to this path. Update it if the target platform
is deliberately changed later.

Searchable-settings index generation is disabled because this baseline provides
no custom settings pages. Re-enable `buildSearchableOptions` when a plugin
`Configurable` is added. The platform's bundled Kotlin standard library is used
instead of packaging a duplicate.

## Source layout and language policy

```text
.run/                   Shared IDE run configuration
gradle/                 Wrapper and JDK 21 daemon criteria
src/main/kotlin/        Generated sample and message-bundle helper
src/main/java/          Planned Java code; create with the first Java class
src/main/resources/     Plugin descriptor, icon and message resources
docs/                   Module boundaries, draft contracts and review notes
build/                  Generated output and sandbox (ignored)
```

New application code is planned in Java 21; retain Kotlin build scripts. Preserve
the Kotlin sample during Stage 1; replace it in the UI stage.

## Collaboration and stage reviews

- [Architecture and ownership](docs/architecture.md)
- [Draft service contracts](docs/interfaces.md)
- [Stage 1 review and verification](docs/stage-1-review.md)

Each member owns their module and documentation. Coordinate interface changes
with affected members. Complete one stage, report checks and limitations, then
wait for the user's review before proceeding.

## References

- [IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/developing-plugins.html)
- [Gradle daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria)
- [Gradle toolchains](https://docs.gradle.org/current/userguide/toolchains.html)
- [IntelliJ Platform Gradle tasks](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html)
