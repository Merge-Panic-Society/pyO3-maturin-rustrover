package com.maturin.exec

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The test IDE carries no Native Debugging Support plugin — the same shape as
 * a user install where it is missing or its internals have shifted. launch()
 * must swallow that and report failure so MaturinRunner can fall back to the
 * legacy attach flow, never surface an exception to the caller.
 */
class NativeDebugLauncherPlatformTest : BasePlatformTestCase() {

    fun testLaunchReportsFailureWithoutNativeDebugPlugin() {
        val started = NativeDebugLauncher.launch(
            project,
            "Maturin debug: test",
            GeneralCommandLine("true"),
        )
        assertFalse(started)
    }
}
