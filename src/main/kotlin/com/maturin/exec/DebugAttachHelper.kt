package com.maturin.exec

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import java.awt.datatransfer.StringSelection

/**
 * Attaches RustRover's native (Rust) debugger to a running PID.
 *
 * The "real" auto-attach uses the platform's attach-debugger-provider extension
 * point, which is an internal/unstable surface — so it is driven entirely by
 * reflection and guarded by a broad catch. If anything goes wrong we fall back
 * to opening the standard "Attach to Process" dialog with the PID on the
 * clipboard so the user can confirm in one click.
 */
object DebugAttachHelper {

    fun attach(project: Project, pid: Int) {
        ApplicationManager.getApplication().invokeLater {
            val ok = try {
                tryAutoAttach(project, pid)
            } catch (t: Throwable) {
                thisLogger().info("Native auto-attach failed; using dialog fallback", t)
                false
            }
            if (ok) {
                MaturinNotifications.info(
                    project, "Maturin debug",
                    "Attached the native debugger to PID $pid. Set breakpoints, then press Enter in the run console.",
                )
            } else {
                openAttachDialog(project, pid)
            }
        }
    }

    /** Best-effort: drive `xdebugger.attachDebuggerProvider` for the local PID. */
    private fun tryAutoAttach(project: Project, pid: Int): Boolean {
        val hostClass = Class.forName("com.intellij.xdebugger.attach.LocalAttachHost")
        val host = hostClass.getField("INSTANCE").get(null)

        // List local processes and find the one matching our PID.
        val processList = host.javaClass.getMethod("getProcessList").invoke(host) as List<*>
        val processInfo = processList.firstOrNull { p ->
            runCatching { p!!.javaClass.getMethod("getPid").invoke(p) as Int }.getOrNull() == pid
        } ?: return false

        val epClass = Class.forName("com.intellij.openapi.extensions.ExtensionPointName")
        val createMethod = epClass.getMethod("create", String::class.java)
        val ep = createMethod.invoke(null, "com.intellij.xdebugger.attachDebuggerProvider")
        @Suppress("UNCHECKED_CAST")
        val providers = epClass.getMethod("getExtensionList").invoke(ep) as List<Any>

        val context = UserDataHolderBase()
        for (provider in providers) {
            val debuggers = runCatching {
                findMethod(provider.javaClass, "getAvailableDebuggers", 4)
                    .invoke(provider, project, host, processInfo, context) as? List<*>
            }.getOrNull().orEmpty().filterNotNull()
            if (debuggers.isEmpty()) continue

            val chosen = debuggers.firstOrNull { isNative(displayName(it)) } ?: debuggers.first()
            findMethod(chosen.javaClass, "attachDebugSession", 3)
                .invoke(chosen, project, host, processInfo)
            return true
        }
        return false
    }

    private fun displayName(debugger: Any): String =
        runCatching {
            findMethod(debugger.javaClass, "getDebuggerDisplayName", 0).invoke(debugger) as? String
        }.getOrNull().orEmpty()

    private fun isNative(name: String): Boolean {
        val n = name.lowercase()
        return listOf("native", "gdb", "lldb", "rust", "c/c++").any { it in n }
    }

    private fun findMethod(cls: Class<*>, name: String, paramCount: Int): java.lang.reflect.Method {
        var c: Class<*>? = cls
        while (c != null) {
            c.methods.firstOrNull { it.name == name && it.parameterCount == paramCount }?.let { return it }
            c = c.superclass
        }
        throw NoSuchMethodException("$name/$paramCount on ${cls.name}")
    }

    /** Reliable fallback: open the Attach-to-Process dialog with the PID copied. */
    private fun openAttachDialog(project: Project, pid: Int) {
        CopyPasteManager.getInstance().setContents(StringSelection(pid.toString()))
        MaturinNotifications.info(
            project, "Maturin debug",
            "Process is waiting at PID $pid (copied to clipboard). Opening 'Attach to Process' — " +
                "pick the native debugger, then press Enter in the run console. " +
                "If attach is denied, run: echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope",
        )
        val action = sequenceOf(
            "XDebugger.AttachToProcess",
            "Cidr.AttachToLocalProcessAction",
            "AttachToProcess",
        ).mapNotNull { ActionManager.getInstance().getAction(it) }.firstOrNull()

        if (action == null) {
            MaturinNotifications.warn(
                project, "Maturin debug",
                "Could not find an Attach-to-Process action. Use Run | Attach to Process and enter PID $pid.",
            )
            return
        }
        val dataContext = DataContext { dataId -> if (CommonDataKeys.PROJECT.`is`(dataId)) project else null }
        ActionUtil.invokeAction(action, dataContext, "MaturinToolWindow", null, null)
    }
}
