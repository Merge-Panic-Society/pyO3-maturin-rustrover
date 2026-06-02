package com.maturin.exec

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.maturin.env.PythonEnv
import com.maturin.model.MaturinTask

/** "Destroy" actions: remove compiled build artifacts and/or the installed module. */
object DestroyActions {

    /** Req 11: delete the project's compiled output (`cargo clean` removes target/). */
    fun cleanBuild(project: Project, task: MaturinTask) {
        val ok = Messages.showYesNoDialog(
            project,
            "Run `cargo clean` in ${task.rootPath}?\nThis deletes the compiled Rust artifacts (target/).",
            "Clean Build — ${task.name}",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!ok) return

        val cmd = CommandLines.single(task.rootPath, listOf("cargo", "clean"))
        ConsoleRunner.run(project, "Maturin clean: ${task.name}", cmd)
    }

    /** Req 12: remove the compiled module from the venv's site-packages. */
    fun uninstallFromVenv(project: Project, task: MaturinTask, env: PythonEnv) {
        val ok = Messages.showYesNoDialog(
            project,
            "Uninstall module '${task.moduleName}' from ${env.displayName}?\n" +
                "This removes the compiled extension from the environment's site-packages.",
            "Uninstall From venv — ${task.name}",
            Messages.getQuestionIcon(),
        ) == Messages.YES
        if (!ok) return

        val cmd = CommandLines.single(
            task.rootPath,
            listOf(env.python.toString(), "-m", "pip", "uninstall", "-y", task.moduleName),
        )
        ConsoleRunner.run(project, "Maturin uninstall: ${task.name}", cmd)
    }
}
