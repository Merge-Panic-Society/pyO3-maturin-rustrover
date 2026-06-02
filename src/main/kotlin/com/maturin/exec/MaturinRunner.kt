package com.maturin.exec

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.maturin.env.PythonEnv
import com.maturin.model.MaturinTask
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Builds and launches the `maturin develop` (+ Python) workflow for a task,
 * in both non-debug and native-debug modes.
 */
object MaturinRunner {

    /** Non-debug: `maturin develop` then run the chosen start file. */
    fun run(project: Project, task: MaturinTask, env: PythonEnv, startFile: Path) {
        val cmd = CommandLines.chain(
            workDir = task.rootPath,
            steps = listOf(
                maturinDevelop(env),
                listOf(env.python.toString(), startFile.toString()),
            ),
            environment = env.activationEnv(),
        )
        ConsoleRunner.run(project, "Maturin run: ${task.name}", cmd)
    }

    /**
     * Debug: build with `maturin develop`, then launch the chosen start file's
     * Python *as the native debuggee* under RustRover's LLDB/GDB driver. The
     * debugger owns the process from the first instruction, so breakpoints in
     * `src/lib.rs` bind before any Rust runs — no PID scrape, no manual attach,
     * no Enter. If the native launch is unavailable, fall back to the legacy
     * wait-for-attach flow.
     *
     * @param debugArgs whitespace-separated program arguments for the start file.
     */
    fun debug(project: Project, task: MaturinTask, env: PythonEnv, startFile: Path, debugArgs: String) {
        val buildCmd = GeneralCommandLine(maturinDevelop(env))
            .withWorkDirectory(task.rootPath.toFile())
        buildCmd.withEnvironment(env.activationEnv())

        ConsoleRunner.run(
            project = project,
            title = "Maturin build: ${task.name}",
            commandLine = buildCmd,
            onFinished = { code ->
                if (code != 0) {
                    MaturinNotifications.error(project, "Maturin debug", "`maturin develop` failed (exit $code).")
                    return@run
                }
                val debuggee = GeneralCommandLine(
                    buildList {
                        add(env.python.toString())
                        add(startFile.toString())
                        addAll(splitArgs(debugArgs))
                    },
                ).withWorkDirectory(task.rootPath.toFile())
                debuggee.withEnvironment(env.activationEnv())

                val started = NativeDebugLauncher.launch(project, "Maturin debug: ${task.name}", debuggee)
                if (!started) {
                    MaturinNotifications.warn(
                        project, "Maturin debug",
                        "Native launch-under-debugger is unavailable; falling back to attach-on-PID.",
                    )
                    debugViaAttach(project, task, env, startFile, debugArgs.ifBlank { "world" })
                }
            },
        )
    }

    /** Split a user-entered argument string on whitespace, dropping blanks. */
    private fun splitArgs(args: String): List<String> =
        args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * Legacy fallback: `maturin develop` then run a wait-for-attach entry under
     * a PTY. When the script prints `PID = N`, the native Rust debugger is
     * attached to the still-waiting process.
     */
    private fun debugViaAttach(project: Project, task: MaturinTask, env: PythonEnv, startFile: Path, debugArg: String) {
        val entry = resolveDebugEntry(project, startFile)
        val pythonStep = if (entry.isWaitScript) {
            // A debug_entry-style script takes the module name + arg as argv.
            listOf(env.python.toString(), entry.path.toString(), task.moduleName, debugArg)
        } else {
            listOf(env.python.toString(), entry.path.toString())
        }
        val cmd = CommandLines.chain(
            workDir = task.rootPath,
            steps = listOf(maturinDevelop(env), pythonStep),
            environment = env.activationEnv(),
        )

        val attached = java.util.concurrent.atomic.AtomicBoolean(false)
        ConsoleRunner.run(
            project = project,
            title = "Maturin debug: ${task.name}",
            commandLine = cmd,
            pty = true,
            onText = { text ->
                if (!attached.get()) {
                    val pid = PID_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
                    if (pid != null && attached.compareAndSet(false, true)) {
                        DebugAttachHelper.attach(project, pid)
                    }
                }
            },
        )
    }

    private fun maturinDevelop(env: PythonEnv): List<String> {
        val maturin = env.maturin
        return if (maturin != null) {
            listOf(maturin.toString(), "develop")
        } else {
            listOf(env.python.toString(), "-m", "maturin", "develop")
        }
    }

    private data class DebugEntry(val path: Path, val isWaitScript: Boolean)

    /**
     * Decide which script to run for debugging. If the chosen start file already
     * waits for attach (prints PID + `input()`), use it. Otherwise materialize the
     * bundled `debug_entry.py` at the project root.
     */
    private fun resolveDebugEntry(project: Project, startFile: Path): DebugEntry {
        if (startFile.isRegularFile() && isWaitScript(startFile)) {
            return DebugEntry(startFile, isWaitScript = true)
        }
        val base = project.basePath?.let { Path.of(it) } ?: startFile.parent
        val target = base.resolve("debug_entry.py")
        if (!target.isRegularFile()) {
            val template = MaturinRunner::class.java.getResourceAsStream("/template/debug_entry.py")
                ?.bufferedReader()?.readText() ?: FALLBACK_TEMPLATE
            target.writeText(template)
        }
        return DebugEntry(target, isWaitScript = true)
    }

    private fun isWaitScript(file: Path): Boolean {
        if (file.name.contains("debug_entry")) return true
        return try {
            val text = file.readText()
            text.contains("input(") && text.contains("getpid")
        } catch (_: Exception) {
            false
        }
    }

    private val PID_REGEX = Regex("""PID\s*=\s*(\d+)""")

    private val FALLBACK_TEMPLATE = """
        import importlib, os, sys
        mod_name = sys.argv[1] if len(sys.argv) > 1 else "stage_01_hello"
        arg      = sys.argv[2] if len(sys.argv) > 2 else "world"
        mod = importlib.import_module(mod_name)
        print(f"PID = {os.getpid()}")
        input("Attach RustRover's debugger to this PID, set breakpoints, then press Enter… ")
        print(mod.hello(arg))
    """.trimIndent()
}
