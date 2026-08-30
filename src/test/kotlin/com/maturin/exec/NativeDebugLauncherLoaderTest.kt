package com.maturin.exec

import com.jetbrains.cidr.fake.FakeCidrExtension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the class-loader resolution that backs the native
 * launch-under-debugger path. Since RustRover 2026.2 the nativeDebug plugin is
 * modular, so the right loader must be *found* (module loaders first, main
 * plugin loader as pre-modular fallback) and *probed* against an anchor class
 * — these tests pin both steps down without booting the platform.
 */
class NativeDebugLauncherLoaderTest {

    /** Parent-less loader: resolves bootstrap classes only, sees no plugin/test code. */
    private val blindLoader = object : ClassLoader(null) {}

    /** Anchor that only loaders delegating to the test classpath can resolve. */
    private val anchor = NativeDebugLauncher::class.java.name

    // --- candidateLoaders -------------------------------------------------

    @Test
    fun `keeps only cidr-packaged extensions and appends the plugin loader last`() {
        val candidates = NativeDebugLauncher.candidateLoaders(
            extensions = listOf(Any(), FakeCidrExtension(), StringBuilder()),
            pluginLoader = blindLoader,
        )
        assertEquals(listOf(FakeCidrExtension::class.java.classLoader, blindLoader), candidates)
    }

    @Test
    fun `null plugin loader contributes nothing`() {
        val candidates = NativeDebugLauncher.candidateLoaders(
            extensions = listOf(FakeCidrExtension()),
            pluginLoader = null,
        )
        assertEquals(listOf(FakeCidrExtension::class.java.classLoader), candidates)
    }

    @Test
    fun `no cidr extensions and no plugin loader yields empty list`() {
        assertTrue(NativeDebugLauncher.candidateLoaders(listOf(Any()), null).isEmpty())
    }

    @Test
    fun `duplicate loaders collapse to one entry`() {
        // Both instances and the "plugin loader" share the test classpath loader.
        val shared = FakeCidrExtension::class.java.classLoader
        val candidates = NativeDebugLauncher.candidateLoaders(
            extensions = listOf(FakeCidrExtension(), FakeCidrExtension()),
            pluginLoader = shared,
        )
        assertEquals(listOf(shared), candidates)
    }

    // --- pickLoader -------------------------------------------------------

    @Test
    fun `skips loaders that cannot see the anchor class`() {
        val sighted = javaClass.classLoader
        assertSame(sighted, NativeDebugLauncher.pickLoader(listOf(blindLoader, sighted), anchor))
    }

    @Test
    fun `prefers the first loader that can see the anchor class`() {
        val first = object : ClassLoader(javaClass.classLoader) {}
        val second = javaClass.classLoader
        assertSame(first, NativeDebugLauncher.pickLoader(listOf(first, second), anchor))
    }

    @Test
    fun `returns null when no loader can see the anchor class`() {
        assertNull(NativeDebugLauncher.pickLoader(listOf(blindLoader), anchor))
        assertNull(NativeDebugLauncher.pickLoader(emptyList(), anchor))
    }
}
