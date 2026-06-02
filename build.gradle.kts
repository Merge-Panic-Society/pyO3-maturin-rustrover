import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // Build against a locally installed RustRover. The 2025.2+ modular IDE
        // layout is not validated by the downloaded-SDK transform of the gradle
        // plugin, so we point at the local Toolbox install (override with
        // -PrustRoverPath=/path or a gradle.properties entry).
        local(
            providers.gradleProperty("rustRoverPath")
                .orElse("/home/tanawin/.local/share/JetBrains/Toolbox/apps/rustrover")
        )

        // NOTE: the plugin deliberately shells out to python/pip/maturin and
        // reaches the native debugger via runtime discovery, so it needs no
        // compile-time dependency on the Rust or Python plugins (Python is not
        // even bundled in RustRover).

        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Built against RustRover 2026.1 but only uses stable platform APIs,
            // so we keep compatibility back to 2025.3 (the oldest local install).
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
}
