package com.maturin.env

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.maturin.exec.CommandLines
import com.maturin.exec.ConsoleRunner
import java.nio.file.Path
import javax.swing.JComponent

/**
 * "Help window" to allocate a Python environment with maturin (req 10):
 * picks a base interpreter and a target folder, then shells out to
 * `python -m venv <folder>` and `pip install maturin`.
 */
class CreateEnvDialog(private val project: Project) : DialogWrapper(project) {

    private val baseInterpreter = TextFieldWithBrowseButton().apply {
        text = "python3"
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withTitle("Select Base Python Interpreter"),
        )
    }

    private val venvFolder = TextFieldWithBrowseButton().apply {
        val base = project.basePath ?: System.getProperty("user.home")
        text = Path.of(base, ".venv").toString()
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Select Target venv Folder"),
        )
    }

    private val installMaturin = JBCheckBox("Install maturin into the new environment", true)

    init {
        title = "Create Python Environment"
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Base interpreter:") { cell(baseInterpreter).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
        row("Environment folder:") { cell(venvFolder).align(com.intellij.ui.dsl.builder.AlignX.FILL) }
        row { cell(installMaturin) }
        row {
            comment(
                "Runs <code>python -m venv &lt;folder&gt;</code>" +
                    (if (installMaturin.isSelected) " then <code>pip install maturin</code>." else "."),
            )
        }
    }

    /** Show the dialog; on OK, run creation in a console and return the env. */
    fun promptAndCreate(onCreated: (PythonEnv) -> Unit) {
        if (!showAndGet()) return
        val folder = Path.of(venvFolder.text.trim())
        val env = PythonEnv(folder)

        val steps = mutableListOf(
            listOf(baseInterpreter.text.trim(), "-m", "venv", folder.toString()),
        )
        if (installMaturin.isSelected) {
            steps += listOf(env.python.toString(), "-m", "pip", "install", "maturin")
        }
        val workDir = project.basePath?.let { Path.of(it) } ?: folder.parent
        ConsoleRunner.run(
            project = project,
            title = "Create Python environment",
            commandLine = CommandLines.chain(workDir, steps),
            onFinished = { exit -> if (exit == 0) onCreated(env) },
        )
    }
}
