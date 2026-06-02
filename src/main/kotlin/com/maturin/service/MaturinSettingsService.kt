package com.maturin.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/** Per-task UI state that should survive restarts. */
class TaskState {
    var startFile: String? = null
    var debugArg: String = "world"
    var venvPath: String? = null
}

class MaturinState {
    /** Default venv path used when a task has none. */
    var defaultVenvPath: String? = null

    /** Keyed by [com.maturin.model.MaturinTask.key] (the task folder path). */
    var tasks: MutableMap<String, TaskState> = mutableMapOf()
}

/**
 * Project-level persistent settings for the Maturin tool window.
 */
@Service(Service.Level.PROJECT)
@State(name = "MaturinSettings", storages = [Storage("maturin.xml")])
class MaturinSettingsService : PersistentStateComponent<MaturinState> {

    private var state = MaturinState()

    override fun getState(): MaturinState = state

    override fun loadState(loaded: MaturinState) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    fun forTask(key: String): TaskState =
        state.tasks.getOrPut(key) { TaskState() }

    var defaultVenvPath: String?
        get() = state.defaultVenvPath
        set(value) {
            state.defaultVenvPath = value
        }

    companion object {
        fun getInstance(project: Project): MaturinSettingsService = project.service()
    }
}
