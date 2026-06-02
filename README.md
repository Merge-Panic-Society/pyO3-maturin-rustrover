# Maturin for RustRover

A side-panel plugin for **RustRover** (and other IntelliJ-platform IDEs) that turns
[maturin](https://www.maturin.rs) / [PyO3](https://pyo3.rs) projects into a
Gradle-style task list: detect every Rust↔Python extension crate, build it with
`maturin develop`, run it, and **natively debug the Rust side from a one-click
GUI debug session** — no manual "Attach to Process", no PID juggling.

---

## What it does

- **Auto-detects tasks.** Scans the project for every folder that directly
  contains both `Cargo.toml` and `pyproject.toml`, and lists each as a task in a
  right-anchored **Maturin** tool window.
- **Run.** `maturin develop` to build + install the extension into the selected
  venv, then runs your chosen Python start file.
- **Debug (native, GUI).** Builds, then launches the venv Python **as the native
  debuggee under RustRover's LLDB/GDB driver**. RustRover owns a full
  `XDebugSession` (frames, variables, stepping) from the first instruction, so
  breakpoints in `src/lib.rs` bind *before* any Rust runs. No attach dialog, no
  manual Enter, no `ptrace_scope` to fight.
- **Manage Python environments.** Create a venv (`python -m venv` + optional
  `pip install maturin`) from a small dialog, and pick which venv each task uses.
- **Clean up.** `cargo clean` to drop compiled artifacts, or `pip uninstall` to
  remove the installed module from a venv's site-packages.

---

## Requirements

| | |
|---|---|
| IDE | RustRover 2025.3+ (`sinceBuild = 253`); developed against 2026.1 |
| Native debugging | The bundled **Native Debugging Support** plugin (`com.intellij.nativeDebug`) — ships with RustRover/CLion |
| On `PATH` | `python` / `python3`, `maturin` (or installed in the venv), `cargo` |
| OS | Built and tested on Linux/x86-64; the CIDR launch path is cross-platform but unverified elsewhere |

> RustRover does **not** bundle the Python plugin, so this plugin never depends
> on it — it shells out to `python`/`pip`/`maturin` and reaches the native
> debugger through runtime discovery.

---

## Usage

1. Open a project that contains one or more maturin crates.
2. Open the **Maturin** tool window (right edge). Tasks appear automatically; use
   **Refresh** to re-scan.
3. Select a task and fill in the detail panel:
   - **Start file** — the `.py` to run (e.g. `example.py`).
   - **Program args** — whitespace-separated arguments passed to the start file.
   - **Python env** — a discovered venv, or create one with **New env…**.
4. Click **Run** or **Debug**.
   - For **Debug**: set breakpoints in `src/lib.rs` first, then click Debug. A
     build console appears, then the native debug session stops at your Rust
     breakpoint.

### Tip: breakpoints on macro lines

A breakpoint on a line that *is* a macro (`format!`, `println!`, `vec!`, …) can
fire several times for one logical call, because the macro expands into multiple
instructions all attributed back to that one source line. Put the breakpoint on
the function signature line or a plain (non-macro) statement to stop exactly once.

---

## Building & running

The project builds against a **locally installed** RustRover (the 2025.2+ modular
IDE layout isn't validated by the Gradle plugin's downloaded-SDK transform):

```bash
# Uses the default Toolbox path; override as needed.
./gradlew runIde -PrustRoverPath=/path/to/rustrover

# Build the distributable plugin zip
./gradlew buildPlugin            # -> build/distributions/

# Compile / test
./gradlew compileKotlin
./gradlew test
```

Set `rustRoverPath` once in `gradle.properties` to avoid passing `-P` each time.

---

## How native debugging works (the interesting bit)

Instead of attaching to an already-running Python process, the plugin runs the
venv Python *as the debuggee*:

1. `maturin develop` builds + installs the extension (debug profile → symbols).
2. `MaturinRunner.debug` constructs `python <startFile> <args…>` with the venv
   activation environment and working directory.
3. `NativeDebugLauncher` wires up RustRover's CIDR debugger **by reflection**
   (the CIDR classes live in the `com.intellij.nativeDebug` plugin, loaded via
   that plugin's own class loader — no compile-time dependency):
   `LLDBDriverConfiguration` → `TrivialInstaller` → `TrivialRunParameters` →
   `CidrLocalDebugProcess`, started through `XDebuggerManager.startSessionAndShowTab`.
   It then calls CIDR's `CidrDebugProcess.start()` to actually launch the inferior.
4. If the native launch is unavailable (plugin missing or CIDR internals shifted),
   it falls back to the legacy "wait-for-attach on `PID = N`" flow.

---

## Project layout

```
src/main/kotlin/com/maturin/
├── detect/MaturinProjectScanner.kt   # find Cargo.toml + pyproject.toml folders
├── model/MaturinTask.kt              # one detected task
├── env/
│   ├── PythonEnvManager.kt           # discover venvs, resolve python/maturin
│   └── CreateEnvDialog.kt            # create a venv (+ pip install maturin)
├── exec/
│   ├── MaturinRunner.kt              # run / debug orchestration
│   ├── NativeDebugLauncher.kt        # launch-under-debugger via CIDR (reflection)
│   ├── DebugAttachHelper.kt          # legacy attach-on-PID fallback
│   ├── CommandLines.kt               # shell-chain / single command builders
│   ├── ConsoleRunner.kt              # run a command in a Run console tab
│   ├── DestroyActions.kt             # cargo clean / pip uninstall
│   └── MaturinNotifications.kt       # balloon notifications
├── service/MaturinSettingsService.kt # per-task persisted UI state
├── toolWindow/                       # the Maturin side panel
└── MaturinBundle.kt                  # i18n message bundle
```

---

## License

See repository for license details.
