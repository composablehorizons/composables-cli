package com.composables.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.exists
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import assertk.assertions.isDirectory
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue

class CliTest {

    @Test
    fun `cloneGradleProject renders jvm template and replaces placeholders`() {
        withTempDir { targetDir ->
            cloneGradleProject(
                targetDir = targetDir.absolutePath,
                dirName = "newApp",
                packageName = "com.composables.demo",
                moduleName = "desktopApp",
                appName = "The App",
                targets = setOf(JVM)
            )

            val projectDir = File(targetDir, "newApp")
            val buildFile = File(projectDir, "desktopApp/build.gradle.kts")
            val appFile = File(projectDir, "desktopApp/src/commonMain/kotlin/com/composables/demo/App.kt")

            assertThat(projectDir.isDirectory, "Generated project directory should exist").isTrue()
            assertThat(buildFile.exists(), "Generated module build file should exist").isTrue()
            assertThat(appFile.exists(), "App source should be moved to the requested package").isTrue()

            assertThat(File(projectDir, "iosDesktopApp").exists(), "iOS app scaffold should be skipped for JVM-only template runs").isFalse()
            assertThat(File(projectDir, "desktopApp/src/androidMain").exists(), "Android sources should be omitted for JVM-only template runs").isFalse()
            assertThat(File(projectDir, "desktopApp/src/webMain").exists(), "Web sources should be omitted for JVM-only template runs").isFalse()

            val buildContent = buildFile.readText()
            val appContent = appFile.readText()

            assertThat(buildContent).contains("jvm()")
            assertThat(buildContent).doesNotContain("androidTarget {")
            assertThat(buildContent).doesNotContain("iosArm64()")
            assertThat(buildContent).doesNotContain("wasmJs {")
            assertThat(buildContent).contains("mainClass = \"com.composables.demo.MainKt\"")
            assertThat(buildContent).doesNotContain("{{module_name}}")

            assertThat(appContent).contains("package com.composables.demo")
            assertThat(appContent).doesNotContain("{{app_name}}")
            assertThat(appContent).doesNotContain("{{namespace}}")
        }
    }

    @Test
    fun `updateRootBuildFile adds missing plugins once`() {
        withTempDir { targetDir ->
            File(targetDir, "build.gradle.kts").writeText(
                """
                plugins {
                    alias(libs.plugins.jetbrains.kotlin.multiplatform) apply false
                }
                """.trimIndent()
            )

            updateRootBuildFile(targetDir.absolutePath, setOf(ANDROID))
            updateRootBuildFile(targetDir.absolutePath, setOf(ANDROID))

            val content = File(targetDir, "build.gradle.kts").readText()

            assertThat(content.countOccurrences("alias(libs.plugins.jetbrains.kotlin.multiplatform) apply false")).isEqualTo(1)
            assertThat(content.countOccurrences("alias(libs.plugins.jetbrains.compose) apply false")).isEqualTo(1)
            assertThat(content.countOccurrences("alias(libs.plugins.jetbrains.compose.compiler) apply false")).isEqualTo(1)
            assertThat(content.countOccurrences("alias(libs.plugins.jetbrains.compose.hotreload) apply false")).isEqualTo(1)
            assertThat(content.countOccurrences("alias(libs.plugins.android.application) apply false")).isEqualTo(1)
        }
    }

    @Test
    fun `updateVersionCatalog adds android entries once`() {
        withTempDir { targetDir ->
            File(targetDir, "gradle").mkdirs()
            File(targetDir, "gradle/libs.versions.toml").writeText(
                """
                [versions]
                kotlin = "2.2.21"

                [libraries]

                [plugins]
                jetbrains-kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
                """.trimIndent() + "\n"
            )

            updateVersionCatalog(targetDir.absolutePath, setOf(ANDROID))
            updateVersionCatalog(targetDir.absolutePath, setOf(ANDROID))

            val content = File(targetDir, "gradle/libs.versions.toml").readText()

            assertThat(content.countOccurrences("""agp = "8.11.2"""")).isEqualTo(1)
            assertThat(content.countOccurrences("""androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }""")).isEqualTo(1)
            assertThat(content.countOccurrences("""android-application = { id = "com.android.application", version.ref = "agp" }""")).isEqualTo(1)
            assertThat(content).contains("""compose = "1.9.1"""")
            assertThat(content).contains("""composeHotReload = "1.0.0"""")
        }
    }

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("composables-cli-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun String.countOccurrences(value: String): Int = split(value).size - 1
}
