package com.maturin.env

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** A resolved Python virtual environment. */
data class PythonEnv(val root: Path) {
    private val binDir: Path = if (SystemInfo.isWindows) root.resolve("Scripts") else root.resolve("bin")

    val python: Path
        get() = if (SystemInfo.isWindows) binDir.resolve("python.exe") else binDir.resolve("python")

    /** maturin executable inside the venv, if installed. */
    val maturin: Path?
        get() {
            val exe = if (SystemInfo.isWindows) binDir.resolve("maturin.exe") else binDir.resolve("maturin")
            return if (exe.isRegularFile()) exe else null
        }

    val isValid: Boolean get() = python.isRegularFile()

    val displayName: String get() = root.name

    /**
     * Environment variables that mimic `activate`, so tools like `maturin
     * develop` can discover this venv. maturin looks for `VIRTUAL_ENV` (or a
     * `.venv` in a parent folder); just invoking its binary from `bin/` is not
     * enough. We also prepend `bin/` to `PATH` like activation does.
     */
    fun activationEnv(): Map<String, String> {
        val path = binDir.toString() + java.io.File.pathSeparator + (System.getenv("PATH") ?: "")
        return mapOf(
            "VIRTUAL_ENV" to root.toString(),
            "PATH" to path,
        )
    }
}

object PythonEnvManager {

    private val SKIP_DIRS = setOf("target", ".git", "node_modules", ".idea", "build", "__pycache__")

    /** Find venvs (folders with a `pyvenv.cfg`) anywhere under the project base. */
    fun discoverVenvs(project: Project): List<PythonEnv> {
        val base = project.basePath?.let { Path.of(it) } ?: return emptyList()
        val found = mutableListOf<PythonEnv>()
        walk(base, 0, found)
        return found.sortedBy { it.displayName }
    }

    private fun walk(dir: Path, depth: Int, out: MutableList<PythonEnv>) {
        if (depth > 6 || !dir.isDirectory()) return
        if (dir.resolve("pyvenv.cfg").isRegularFile()) {
            val env = PythonEnv(dir)
            if (env.isValid) out += env
            return
        }
        val children = try {
            Files.list(dir).use { it.toList() }
        } catch (_: Exception) {
            return
        }
        for (child in children) {
            if (!child.isDirectory() || child.name in SKIP_DIRS) continue
            // Descend into hidden dirs only when they look like a venv: `.venv`
            // is the maturin/PEP-405 convention, so blanket-skipping dot-dirs
            // would hide the project's own environment. Other hidden dirs
            // (.git/.idea/.pytest_cache/…) are still skipped as noise.
            val looksLikeVenv = child.resolve("pyvenv.cfg").isRegularFile()
            if (!looksLikeVenv && child.name.startsWith(".")) continue
            walk(child, depth + 1, out)
        }
    }

    /** True if maturin is importable/runnable from this env. */
    fun hasMaturin(env: PythonEnv): Boolean {
        if (env.maturin != null) return true
        return try {
            val cmd = GeneralCommandLine(env.python.toString(), "-m", "maturin", "--version")
            val process = cmd.createProcess()
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    /** Command line that invokes maturin from the env (prefers the bin shim). */
    fun maturinCommand(env: PythonEnv, vararg args: String): GeneralCommandLine {
        val maturin = env.maturin
        return if (maturin != null) {
            GeneralCommandLine(maturin.toString(), *args)
        } else {
            GeneralCommandLine(env.python.toString(), "-m", "maturin", *args)
        }
    }
}
