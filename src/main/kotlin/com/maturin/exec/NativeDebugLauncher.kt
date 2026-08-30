package com.maturin.exec

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilder
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpointType

/**
 * Launches the venv Python *as the native debuggee* under RustRover's LLDB/GDB
 * driver. RustRover then owns a full GUI debug session (frames, variables,
 * stepping) from the first instruction, and breakpoints in `src/lib.rs` bind
 * before any Rust code runs — no PID scraping, no "Attach to Process", no
 * manual Enter, and no `ptrace_scope` to fight.
 *
 * The CIDR debugger classes live in the "Native Debugging Support" plugin
 * (`com.intellij.nativeDebug`), which this plugin deliberately does not take a
 * compile-time dependency on (it targets only the stable platform). So the
 * debuggee is wired up by reflection, loaded through that plugin's own class
 * loader; the only platform types touched directly are XDebuggerManager and
 * XDebugProcessStarter. Any failure returns false so the caller can fall back.
 */
object NativeDebugLauncher {

    private const val NATIVE_DEBUG_PLUGIN = "com.intellij.nativeDebug"

    private const val LLDB_CONFIG = "com.jetbrains.cidr.execution.debugger.backend.lldb.LLDBDriverConfiguration"
    private const val GDB_CONFIG = "com.jetbrains.cidr.execution.debugger.backend.gdb.GDBDriverConfiguration"
    private const val DRIVER_CONFIG = "com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration"
    private const val INSTALLER = "com.jetbrains.cidr.execution.Installer"
    private const val TRIVIAL_INSTALLER = "com.jetbrains.cidr.execution.TrivialInstaller"
    private const val RUN_PARAMETERS = "com.jetbrains.cidr.execution.RunParameters"
    private const val TRIVIAL_RUN_PARAMETERS = "com.jetbrains.cidr.execution.TrivialRunParameters"
    private const val LOCAL_DEBUG_PROCESS = "com.jetbrains.cidr.execution.debugger.CidrLocalDebugProcess"

    /**
     * Build and start a native debug session for [commandLine]. Must be called
     * on the EDT.
     *
     * @return true if the session started; false if the native-debug plugin is
     *         unavailable or its internal API has shifted.
     */
    fun launch(project: Project, sessionName: String, commandLine: GeneralCommandLine): Boolean = try {
        val params = buildRunParameters(commandLine)
        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess =
                newLocalDebugProcess(params, session, project)
        }
        XDebuggerManager.getInstance(project)
            .startSessionAndShowTab(sessionName, null, null, false, starter)
        true
    } catch (t: Throwable) {
        thisLogger().warn("Native launch-under-debugger failed", t)
        false
    }

    /**
     * Class loader that can see the CIDR debugger classes. Through 2026.1 the
     * nativeDebug plugin shipped as one jar, so its main plugin class loader
     * sufficed. Since 2026.2 the plugin uses the modular layout
     * (`lib/modules/intellij.cidr.debugger.core.jar` …): the classes live in
     * content modules with their *own* loaders, invisible from the main one.
     * The module loader is recovered from any CIDR breakpoint type registered
     * into the stable `xdebugger.breakpointType` EP — those instances are
     * created by `intellij.cidr.debugger.core`'s loader, which (via module
     * dependencies) sees every class needed here. A single loader is used for
     * all lookups so reflective signatures stay class-identity consistent.
     */
    private fun cidrLoader(): ClassLoader {
        val candidates = candidateLoaders(
            extensions = XBreakpointType.EXTENSION_POINT_NAME.extensionList,
            pluginLoader = PluginManagerCore.getPlugin(PluginId.getId(NATIVE_DEBUG_PLUGIN))?.pluginClassLoader,
        )
        if (candidates.isEmpty()) {
            error("Native Debugging Support plugin ($NATIVE_DEBUG_PLUGIN) is not installed")
        }
        return pickLoader(candidates, LOCAL_DEBUG_PROCESS)
            ?: error("No class loader in $NATIVE_DEBUG_PLUGIN can see the CIDR debugger classes")
    }

    /**
     * Candidate loaders, most-specific first: loaders of CIDR extension
     * instances (the content-module loaders), then the plugin's main loader
     * (pre-modular layouts).
     */
    internal fun candidateLoaders(extensions: List<Any>, pluginLoader: ClassLoader?): List<ClassLoader> =
        buildList {
            extensions
                .filter { it.javaClass.name.startsWith("com.jetbrains.cidr.") }
                .mapTo(this) { it.javaClass.classLoader }
            pluginLoader?.let { add(it) }
        }.distinct()

    /** First candidate that can resolve [anchor] (without initializing it), or null. */
    internal fun pickLoader(candidates: List<ClassLoader>, anchor: String): ClassLoader? =
        candidates.firstOrNull { loader ->
            runCatching { Class.forName(anchor, false, loader) }.isSuccess
        }

    private fun load(name: String): Class<*> = Class.forName(name, true, cidrLoader())

    /** `new TrivialRunParameters(new LLDBDriverConfiguration(), new TrivialInstaller(cmd))`. */
    private fun buildRunParameters(cmd: GeneralCommandLine): Any {
        val driver = newDriverConfiguration()
        val installer = load(TRIVIAL_INSTALLER)
            .getConstructor(GeneralCommandLine::class.java)
            .newInstance(cmd)
        return load(TRIVIAL_RUN_PARAMETERS)
            .getConstructor(load(DRIVER_CONFIG), load(INSTALLER))
            .newInstance(driver, installer)
    }

    /** Prefer LLDB (bundled with RustRover); fall back to GDB. */
    private fun newDriverConfiguration(): Any {
        val cls = runCatching { load(LLDB_CONFIG) }.getOrNull() ?: load(GDB_CONFIG)
        return cls.getConstructor().newInstance()
    }

    /**
     * `new CidrLocalDebugProcess(params, session, consoleBuilder)`, then call its
     * CIDR-specific `start()` to actually launch the inferior. The platform only
     * calls `sessionInitialized()` (which spins up the LLDB/GDB driver); the
     * target process is launched/resumed by `CidrDebugProcess.start()`, exactly
     * as `CidrRunner.startDebugSession` does. Without this the debugger attaches
     * but the program never runs.
     */
    private fun newLocalDebugProcess(params: Any, session: XDebugSession, project: Project): XDebugProcess {
        val console: TextConsoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project)
        val process = load(LOCAL_DEBUG_PROCESS)
            .getConstructor(load(RUN_PARAMETERS), XDebugSession::class.java, TextConsoleBuilder::class.java)
            .newInstance(params, session, console)
        process.javaClass.getMethod("start").invoke(process)
        return process as XDebugProcess
    }
}
