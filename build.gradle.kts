// This file sets the shared build rules, Java toolchain, and static checks for every module.
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.spotless)
}

allprojects {
    group = "io.tallyhook"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "com.diffplug.spotless")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    ktlint {
        version.set("1.3.0")
        android.set(false)
        outputToConsole.set(true)
        ignoreFailures.set(false)
    }

    detekt {
        config.setFrom(files("${rootProject.rootDir}/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        allRules = false
        autoCorrect = false
    }

    spotless {
        kotlin {
            target("**/*.kt")
            ktlint("1.3.0").setEditorConfigPath("${rootProject.rootDir}/.editorconfig")
        }
        kotlinGradle {
            target("**/*.gradle.kts")
            ktlint("1.3.0")
        }
        format("markdown") {
            target("**/*.md")
            trimTrailingWhitespace()
            endWithNewline()
        }
        format("yaml") {
            target("**/*.{yml,yaml}")
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}
