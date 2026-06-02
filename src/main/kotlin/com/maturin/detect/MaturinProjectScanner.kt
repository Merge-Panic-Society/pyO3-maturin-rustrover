package com.maturin.detect

import com.intellij.openapi.project.Project
import com.maturin.model.MaturinTask
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Finds every folder under the project base that directly contains both
 * `Cargo.toml` and `pyproject.toml`. Each such folder is one [MaturinTask].
 */
object MaturinProjectScanner {

    private val SKIP_DIRS = setOf("target", ".git", "node_modules", ".idea", "build", "__pycache__")

    fun scan(project: Project): List<MaturinTask> {
        val basePath = project.basePath?.let { Path.of(it) } ?: return emptyList()
        val tasks = mutableListOf<MaturinTask>()
        walk(basePath, 0, tasks)
        return tasks.sortedBy { it.name }
    }

    private fun walk(dir: Path, depth: Int, out: MutableList<MaturinTask>) {
        if (depth > 8 || !dir.isDirectory()) return
        // A venv (folder with pyvenv.cfg) is not a project task and not worth descending.
        if (dir.resolve("pyvenv.cfg").isRegularFile()) return

        val cargo = dir.resolve("Cargo.toml")
        val pyproject = dir.resolve("pyproject.toml")
        if (cargo.isRegularFile() && pyproject.isRegularFile()) {
            out += buildTask(dir, cargo, pyproject)
            // A maturin task folder won't contain nested tasks; stop descending.
            return
        }

        val children = try {
            Files.list(dir).use { stream -> stream.toList() }
        } catch (_: Exception) {
            return
        }
        for (child in children) {
            if (child.isDirectory() && child.name !in SKIP_DIRS && !child.name.startsWith(".")) {
                walk(child, depth + 1, out)
            }
        }
    }

    private fun buildTask(dir: Path, cargo: Path, pyproject: Path): MaturinTask {
        val pyText = readOrEmpty(pyproject)
        val cargoText = readOrEmpty(cargo)
        val moduleName = tomlValue(pyText, "module-name")
            ?: sectionValue(pyText, "project", "name")
            ?: dir.name
        val cargoName = sectionValue(cargoText, "lib", "name")
            ?: sectionValue(cargoText, "package", "name")
        return MaturinTask(rootPath = dir, name = dir.name, moduleName = moduleName, cargoName = cargoName)
    }

    private fun readOrEmpty(p: Path): String = try {
        p.readText()
    } catch (_: Exception) {
        ""
    }

    /** First `key = "value"` anywhere in the file (good enough for module-name). */
    private fun tomlValue(text: String, key: String): String? =
        Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*["']([^"']+)["']""")
            .find(text)?.groupValues?.get(1)

    /** `key = "value"` that appears under a `[section]` (or `[tool.section]`) header. */
    private fun sectionValue(text: String, section: String, key: String): String? {
        var inSection = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                val header = line.trim('[', ']').trim()
                inSection = header == section || header.endsWith(".$section")
                continue
            }
            if (inSection) {
                val m = Regex("""^${Regex.escape(key)}\s*=\s*["']([^"']+)["']""").find(line)
                if (m != null) return m.groupValues[1]
            }
        }
        return null
    }
}
