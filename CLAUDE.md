# CLAUDE.md

Guidance for working in this repository.

## What this is

**Maturin for RustRover** — an IntelliJ-platform plugin (Kotlin) that adds a
"Maturin" tool window for [maturin](https://www.maturin.rs) / PyO3 projects:
detect tasks, build/run with `maturin develop`, natively debug the Rust side,
manage venvs, and clean up artifacts. Plugin id `com.maturin`, package root
`com.maturin`.

## Build & run

```bash
./gradlew compileKotlin          # fast compile check (preferred for verifying edits)
./gradlew test                   # unit tests (JUnit4 + Platform test framework)
./gradlew runIde                 # launch a sandbox RustRover with the plugin
./gradlew buildPlugin            # produce the distributable zip
```

- **Builds against a *locally installed* RustRover**, not a downloaded SDK
  (`local(...)` in `build.gradle.kts`). Default path:
  `/home/tanawin/.local/share/JetBrains/Toolbox/apps/rustrover`. Override with
  `-PrustRoverPath=/path` or a `gradle.properties` entry. The 2025.2+ modular IDE
  layout is not validated by the Gradle plugin's downloaded-SDK transform, which
  is why the local install is required.
- Kotlin **2.3.0**, IntelliJ Platform Gradle plugin **2.16.0**, JVM toolchain via
  foojay resolver. `sinceBuild = 253` (RustRover 2025.3); developed on 2026.1.
- After editing Kotlin, prefer `./gradlew compileKotlin` to confirm — don't
  re-read files just to check an edit landed.

## Hard constraints (do not break)

- **No compile-time dependency on the Rust or Python plugins.** RustRover does
  not even bundle Python. The plugin **shells out** to `python` / `pip` /
  `maturin` / `cargo`, and reaches the native debugger via **runtime reflection**.
  `plugin.xml` only `<depends>com.intellij.modules.platform</depends>`.
- **Reach CIDR/native-debug classes by reflection through the nativeDebug plugin's
  classloader**, never by importing them. See `NativeDebugLauncher.kt`: resolve
  `PluginManagerCore.getPlugin(PluginId.getId("com.intellij.nativeDebug"))
  .pluginClassLoader` and `Class.forName(name, true, loader)`. The only platform
  types used directly there are `XDebuggerManager` / `XDebugProcessStarter`.
- **Stick to stable platform APIs** elsewhere so the `untilBuild = null` open range
  holds. `DebugAttachHelper.kt` already drives an unstable EP entirely by
  reflection inside a broad catch — keep that pattern for anything internal.

## Architecture map

- `detect/MaturinProjectScanner.kt` — walks the project base for folders with both
  `Cargo.toml` + `pyproject.toml`; light regex TOML parsing for `module-name` /
  `[lib] name`. Skips venvs (`pyvenv.cfg`) and build dirs.
- `exec/MaturinRunner.kt` — orchestrates **run** and **debug**.
  - `debug()`: `maturin develop` (via `ConsoleRunner`, `onFinished`) → on exit 0,
    build `python <startFile> <args>` and call `NativeDebugLauncher.launch`. On
    failure, fall back to `debugViaAttach` (legacy PID-scrape + `DebugAttachHelper`).
- `exec/NativeDebugLauncher.kt` — the launch-under-debugger path (primary).
  Must call `CidrDebugProcess.start()` after constructing `CidrLocalDebugProcess`,
  or LLDB attaches but the inferior never runs.
- `exec/DebugAttachHelper.kt` — legacy fallback; attaches to a running PID.
- `exec/CommandLines.kt` — `chain()` (POSIX/`cmd` `&&` sequence, single Stop
  button) and `single()`; POSIX/Windows quoting.
- `exec/ConsoleRunner.kt` — runs a `GeneralCommandLine` in a Run console tab
  (`RunContentExecutor`); optional PTY, `onText` tap, `onFinished` callback (EDT).
- `env/` — `PythonEnvManager` (discover venvs by `pyvenv.cfg`, resolve
  `python`/`maturin`, `activationEnv()` = `VIRTUAL_ENV` + `PATH`) and
  `CreateEnvDialog` (`python -m venv` + optional `pip install maturin`).
- `service/MaturinSettingsService.kt` — `@State` persisted per-task UI (start
  file, program args, venv); keyed by task folder path.
- `toolWindow/` — `MaturinToolWindowPanel` (tree + detail form: Start file /
  Program args / Python env / Run·Debug·Clean·Uninstall) and its factory.
- `exec/DestroyActions.kt` — `cargo clean`, `pip uninstall <module>` (both confirmed).

## Conventions

- Singletons as Kotlin `object`; UI/EDT work via `ApplicationManager.invokeLater`.
- User-facing messages go through `MaturinNotifications` (balloons) or `Messages`
  dialogs; keep them actionable.
- Match the surrounding KDoc density — files carry a short "why" comment at the top
  and on non-obvious methods.

## Gotchas

- A breakpoint on a Rust macro line (`format!`, `println!`, …) hits multiple times
  per call (macro expands to several instructions tagged to one source line). This
  is expected Rust/LLDB behavior, **not** a plugin bug — advise breaking on a
  non-macro line.
- `debug_entry.py` (bundled template + project copy) is only used by the legacy
  attach fallback. The primary native path runs the user's selected start file
  directly; do not reintroduce a project-scaffold requirement.
- Sandbox IDE logs for diagnosing debug sessions:
  `.intellijPlatform/sandbox/Maturin for RustRover/RR-*/log/idea.log` — grep for
  `c.j.c.e.debugger` lines.
