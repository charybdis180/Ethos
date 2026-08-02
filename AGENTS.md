# AGENTS.md

## Cursor Cloud specific instructions

This repository is a **NeoForge Minecraft mod** (the NeoForge MDK template) for Minecraft 1.21.1, built with Gradle. There are no web services, databases, or network backends — "running the app" means launching Minecraft (client/server) with the mod loaded via the Gradle wrapper.

- **JDK:** Requires Java 21 (already installed in the cloud image; `java -version` reports 21). The Gradle build pins the toolchain to Java 21.
- **Build/deps:** `./gradlew build` compiles the mod, produces the jar (`build/libs/examplemod-<version>.jar`), and runs `check`. The startup update script pre-resolves the runtime classpath, and the NeoForge/Minecraft/mapping artifacts are already cached in the environment snapshot; the first build in a fresh checkout only re-derives Minecraft artifacts from cache. Use `./gradlew --refresh-dependencies` to force re-download.
- **Tests/lint:** `./gradlew check` (aliased by `build`). The template ships no unit tests (`:test NO-SOURCE`); in-game gametests would run via `./gradlew runGameTestServer`, but that task crashes if no gametests are registered (true for the unmodified template).
- **Run configs** (defined in `build.gradle` `runs {}`): `runClient` (GUI client — needs OpenGL/display, not suitable for headless VMs), `runServer` (headless dedicated server), `runGameTestServer` (headless, needs registered gametests), `runData` (generates resources into `src/generated/resources`).

### Running the dedicated server headlessly (recommended E2E check)
- Minecraft requires EULA acceptance: create `run/eula.txt` containing `eula=true` before `./gradlew runServer` (the `run/` directory is gitignored). Without it the server exits immediately.
- Successful load logs (from `com.example.examplemod.ExampleMod`): `HELLO FROM COMMON SETUP`, `The magic number is... 42`, `HELLO from server starting`, and `Done (Xs)!`.
- A benign `NoSuchFileException: server.properties` is logged on the first run before the file is generated; it does not indicate a failure.
- **Console stdin is NOT forwarded** through the Gradle run task, so typing server commands (`list`, `stop`, etc.) into the terminal has no effect. Stop the server by sending Ctrl-C to the Gradle process (e.g. the tmux pane) instead.
