package com.maturin.exec

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

/**
 * Runs a single command line in a console tab inside the Run tool window
 * (with a Stop button), optionally tapping stdout and reporting completion.
 */
object ConsoleRunner {

    /**
     * @param pty when true, the process runs under a pseudo-terminal so that
     *            interactive `input()` calls (used by debug_entry) work.
     * @param onText invoked for each chunk of process output (for PID scraping).
     * @param onFinished invoked on the EDT with the exit code (null if not started).
     */
    fun run(
        project: Project,
        title: String,
        commandLine: GeneralCommandLine,
        pty: Boolean = false,
        onText: ((String) -> Unit)? = null,
        onFinished: ((Int) -> Unit)? = null,
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val effective = if (pty) PtyCommandLine(commandLine) else commandLine
                val handler = OSProcessHandler(effective)
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        onText?.invoke(event.text)
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        onFinished?.let { cb ->
                            ApplicationManager.getApplication().invokeLater { cb(event.exitCode) }
                        }
                    }
                })

                RunContentExecutor(project, handler)
                    .withTitle(title)
                    .withActivateToolWindow(true)
                    .run()
            } catch (e: Exception) {
                thisLogger().warn("Failed to launch: ${commandLine.commandLineString}", e)
                MaturinNotifications.error(project, "Maturin", "Failed to launch: ${e.message}")
                onFinished?.invoke(-1)
            }
        }
    }
}
