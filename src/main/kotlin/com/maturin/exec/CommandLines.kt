package com.maturin.exec

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path

/** Helpers to build command lines, including shell-chained sequences. */
object CommandLines {

    /**
     * Build a command that runs [steps] in order, stopping at the first failure
     * (`&&` semantics), in [workDir]. Using a shell keeps the whole sequence as
     * a single process so the Run console gets one clean Stop button.
     */
    fun chain(
        workDir: Path,
        steps: List<List<String>>,
        environment: Map<String, String> = emptyMap(),
    ): GeneralCommandLine {
        val script = steps.joinToString(" && ") { step -> step.joinToString(" ") { shellQuote(it) } }
        val cmd = if (SystemInfo.isWindows) {
            GeneralCommandLine("cmd.exe", "/c", script)
        } else {
            GeneralCommandLine("/bin/sh", "-c", script)
        }
        cmd.setWorkDirectory(workDir.toFile())
        cmd.environment.putAll(environment)
        return cmd
    }

    /** Single command line in [workDir]. */
    fun single(workDir: Path, parts: List<String>): GeneralCommandLine =
        GeneralCommandLine(parts).withWorkDirectory(workDir.toFile())

    private fun shellQuote(s: String): String {
        if (SystemInfo.isWindows) {
            return if (s.any { it == ' ' || it == '"' }) "\"${s.replace("\"", "\\\"")}\"" else s
        }
        // POSIX single-quote: wrap and escape embedded single quotes.
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
