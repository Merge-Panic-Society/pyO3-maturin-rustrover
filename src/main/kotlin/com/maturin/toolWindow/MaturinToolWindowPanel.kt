package com.maturin.toolWindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.maturin.detect.MaturinProjectScanner
import com.maturin.env.CreateEnvDialog
import com.maturin.env.PythonEnv
import com.maturin.env.PythonEnvManager
import com.maturin.exec.DestroyActions
import com.maturin.exec.MaturinNotifications
import com.maturin.exec.MaturinRunner
import com.maturin.model.MaturinTask
import com.maturin.service.MaturinSettingsService
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The Maturin side panel: a tree of detected tasks on top and a per-task
 * action panel (start file, venv, Run/Debug/Clean/Uninstall) below.
 */
class MaturinToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val settings = MaturinSettingsService.getInstance(project)

    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }

    private val startFileField = TextFieldWithBrowseButton()
    private val venvCombo = ComboBox<String>()
    private val argField = JBTextField()
    private val detailHeader = JBLabel("Select a task")

    private var selectedTask: MaturinTask? = null
    private var venvs: List<PythonEnv> = emptyList()

    init {
        toolbar = buildToolbar()
        setContent(buildContent())
        tree.cellRenderer = object : com.intellij.ui.ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                t: javax.swing.JTree, value: Any?, selected: Boolean,
                expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                val task = (value as? DefaultMutableTreeNode)?.userObject as? MaturinTask ?: return
                icon = AllIcons.Nodes.Module
                append(task.name)
                append("  ${task.moduleName}", com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        tree.addTreeSelectionListener { onTreeSelection() }
        reload()
    }

    // ---- layout ---------------------------------------------------------

    private fun buildToolbar(): JPanel {
        val group = DefaultActionGroup().apply {
            add(simpleAction("Refresh", "Re-scan for maturin tasks", AllIcons.Actions.Refresh) { reload() })
            add(simpleAction("Create Python Environment", "Create a venv with maturin", AllIcons.General.Add) {
                CreateEnvDialog(project).promptAndCreate { refreshVenvs() }
            })
        }
        val actionToolbar = ActionManager.getInstance().createActionToolbar("MaturinToolbar", group, true)
        actionToolbar.targetComponent = this
        return JBUI.Panels.simplePanel(actionToolbar.component)
    }

    private fun buildContent(): OnePixelSplitter {
        val splitter = OnePixelSplitter(true, 0.45f)
        splitter.firstComponent = JBScrollPane(tree)
        splitter.secondComponent = buildDetailPanel()
        return splitter
    }

    private fun buildDetailPanel(): JPanel {
        val runBtn = JButton("Run", AllIcons.Actions.Execute).apply { addActionListener { launch(debug = false) } }
        val debugBtn = JButton("Debug", AllIcons.Actions.StartDebugger).apply { addActionListener { launch(debug = true) } }
        val cleanBtn = JButton("Clean build", AllIcons.Actions.GC).apply { addActionListener { cleanBuild() } }
        val uninstallBtn = JButton("Uninstall from venv", AllIcons.Actions.Uninstall).apply { addActionListener { uninstall() } }
        val newEnvBtn = JButton("New env…").apply {
            addActionListener { CreateEnvDialog(project).promptAndCreate { refreshVenvs() } }
        }

        // Browse the project file tree, restricted to a single .py file.
        val startFileDescriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle("Select Start File")
            .withDescription("Choose the Python entry script to run or debug")
            .withFileFilter { it.extension.equals("py", ignoreCase = true) }
        startFileField.addBrowseFolderListener(project, startFileDescriptor)
        startFileField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = persistStartFile()
        })
        venvCombo.addActionListener { persistVenv() }

        val buttons = JPanel().apply {
            add(runBtn); add(debugBtn); add(cleanBtn); add(uninstallBtn)
        }

        return FormBuilder.createFormBuilder()
            .addComponent(detailHeader)
            .addLabeledComponent("Start file:", startFileField)
            .addLabeledComponent("Program args:", argField)
            .addLabeledComponent("Python env:", JBUI.Panels.simplePanel(venvCombo).addToRight(newEnvBtn))
            .addComponent(buttons)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .also { it.border = JBUI.Borders.empty(8) }
    }

    // ---- data -----------------------------------------------------------

    private fun reload() {
        val tasks = MaturinProjectScanner.scan(project)
        rootNode.removeAllChildren()
        for (task in tasks) rootNode.add(DefaultMutableTreeNode(task))
        treeModel.reload()
        refreshVenvs()
        if (tasks.isEmpty()) {
            detailHeader.text = "No Cargo.toml + pyproject.toml folders found"
        }
    }

    private fun refreshVenvs() {
        venvs = PythonEnvManager.discoverVenvs(project)
        val items = venvs.map { it.root.toString() }.toMutableList()
        settings.defaultVenvPath?.let { if (it !in items) items.add(it) }
        venvCombo.model = javax.swing.DefaultComboBoxModel(items.toTypedArray())
        selectedTask?.let { restoreVenvSelection(it) }
    }

    private fun onTreeSelection() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        val task = node?.userObject as? MaturinTask
        selectedTask = task
        if (task == null) {
            detailHeader.text = "Select a task"
            return
        }
        detailHeader.text = "${task.name}  (module: ${task.moduleName})"
        populateStartFiles(task)
        restoreVenvSelection(task)
        argField.text = settings.forTask(task.key).debugArg
    }

    private fun populateStartFiles(task: MaturinTask) {
        // The user picks the start file via the Browse button (project file tree,
        // .py only). Prefill with the saved choice, falling back to a best-guess
        // entry script discovered under the task folder so the field isn't empty.
        val saved = settings.forTask(task.key).startFile?.takeIf { Path.of(it).isRegularFile() }
        val initial = saved ?: firstPythonFile(task.rootPath)
        startFileField.text = initial ?: ""
    }

    /** A best-guess entry script: the first `.py` discovered under the task folder. */
    private fun firstPythonFile(root: Path): String? {
        val files = sortedSetOf<String>()
        collectPythonFiles(root, 0, files)
        return files.firstOrNull()
    }

    /** Collect `.py` files under [dir], skipping build dirs, venvs and hidden dirs. */
    private fun collectPythonFiles(dir: Path, depth: Int, out: MutableSet<String>) {
        if (depth > 5) return
        val children = runCatching {
            java.nio.file.Files.list(dir).use { it.toList() }
        }.getOrElse { return }
        for (child in children) {
            when {
                child.isRegularFile() && child.name.endsWith(".py") -> out.add(child.toString())
                child.isDirectory()
                    && child.name !in SKIP_DIRS
                    && !child.name.startsWith(".")
                    && !child.resolve("pyvenv.cfg").isRegularFile() ->
                        collectPythonFiles(child, depth + 1, out)
            }
        }
    }

    private fun restoreVenvSelection(task: MaturinTask) {
        val saved = settings.forTask(task.key).venvPath ?: settings.defaultVenvPath
        if (saved != null) {
            val model = venvCombo.model
            for (i in 0 until model.size) {
                if (model.getElementAt(i) == saved) {
                    venvCombo.selectedItem = saved
                    return
                }
            }
        }
    }

    private fun persistStartFile() {
        val task = selectedTask ?: return
        settings.forTask(task.key).startFile = startFileField.text.ifBlank { null }
    }

    private fun persistVenv() {
        val task = selectedTask ?: return
        val path = venvCombo.selectedItem as? String
        settings.forTask(task.key).venvPath = path
        if (settings.defaultVenvPath == null) settings.defaultVenvPath = path
    }

    // ---- actions --------------------------------------------------------

    private fun resolveEnv(): PythonEnv? {
        val path = venvCombo.selectedItem as? String ?: return null
        val env = PythonEnv(Path.of(path))
        return if (env.isValid) env else null
    }

    private fun launch(debug: Boolean) {
        val task = selectedTask ?: return warnNoTask()
        val env = resolveEnv()
        if (env == null) {
            offerCreateEnv("No valid Python environment is selected.")
            return
        }
        if (!PythonEnvManager.hasMaturin(env)) {
            offerCreateEnv("maturin is not installed in ${env.displayName}.")
            return
        }
        val startFileStr = startFileField.text.ifBlank { null }
        if (startFileStr == null) {
            MaturinNotifications.warn(project, "Maturin", "Pick a start Python file first.")
            return
        }
        val startFile = Path.of(startFileStr)
        settings.forTask(task.key).debugArg = argField.text.ifBlank { "world" }

        if (debug) {
            MaturinRunner.debug(project, task, env, startFile, argField.text.ifBlank { "world" })
        } else {
            MaturinRunner.run(project, task, env, startFile)
        }
    }

    private fun cleanBuild() {
        val task = selectedTask ?: return warnNoTask()
        DestroyActions.cleanBuild(project, task)
    }

    private fun uninstall() {
        val task = selectedTask ?: return warnNoTask()
        val env = resolveEnv() ?: return offerCreateEnv("No valid Python environment is selected.")
        DestroyActions.uninstallFromVenv(project, task, env)
    }

    private fun offerCreateEnv(reason: String) {
        val create = Messages.showYesNoDialog(
            project, "$reason\n\nCreate a Python environment with maturin now?",
            "Maturin", Messages.getQuestionIcon(),
        ) == Messages.YES
        if (create) CreateEnvDialog(project).promptAndCreate { refreshVenvs() }
    }

    private fun warnNoTask() {
        MaturinNotifications.warn(project, "Maturin", "Select a task in the tree first.")
    }

    private fun simpleAction(text: String, description: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : AnAction(text, description, icon) {
            override fun actionPerformed(e: AnActionEvent) = run()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private companion object {
        val SKIP_DIRS = setOf("target", ".git", "node_modules", ".idea", "build", "__pycache__")
    }
}
