package com.maturin.model

import java.nio.file.Path

/**
 * One maturin "project task": a folder that contains both `Cargo.toml` and
 * `pyproject.toml`. The names are parsed once at scan time; [moduleName] is the
 * importable Python module (used by `import`, `pip uninstall` and debug_entry).
 */
data class MaturinTask(
    /** Absolute path of the folder holding Cargo.toml + pyproject.toml. */
    val rootPath: Path,
    /** Display name (the folder name). */
    val name: String,
    /** Importable Python module name (pyproject `module-name` ?: `project.name` ?: folder). */
    val moduleName: String,
    /** Cargo `[lib] name` or `[package] name`, for reference. */
    val cargoName: String?,
) {
    /** Stable key used to persist per-task settings. */
    val key: String get() = rootPath.toString()
}
