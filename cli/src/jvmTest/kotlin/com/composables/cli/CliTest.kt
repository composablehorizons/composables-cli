package com.composables.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.exists
import assertk.assertions.isDirectory
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CliTest {

    @Test
    fun `cloneGradleProject renders jvm template and replaces placeholders`() {
        withTempDir { targetDir ->
            cloneGradleProject(
                targetDir = targetDir.absolutePath,
                dirName = "newApp",
                packageName = "com.composables.demo",
                moduleName = "shared",
                appName = "The App",
                targets = setOf(JVM),
            )

            val projectDir = File(targetDir, "newApp")
            val sharedBuildFile = File(projectDir, "shared/build.gradle.kts")
            val desktopBuildFile = File(projectDir, "desktopApp/build.gradle.kts")
            val rootBuildFile = File(projectDir, "build.gradle.kts")
            val settingsFile = File(projectDir, "settings.gradle.kts")
            val appFile = File(projectDir, "shared/src/commonMain/kotlin/com/composables/demo/App.kt")
            val desktopMainFile = File(projectDir, "desktopApp/src/jvmMain/kotlin/com/composables/demo/main.kt")

            assertThat(projectDir.isDirectory, "Generated project directory should exist").isTrue()
            assertThat(sharedBuildFile.exists(), "Generated shared module build file should exist").isTrue()
            assertThat(desktopBuildFile.exists(), "Generated desktop module build file should exist").isTrue()
            assertThat(settingsFile.exists(), "Generated settings file should exist").isTrue()
            assertThat(appFile.exists(), "App source should be moved to the requested package").isTrue()
            assertThat(desktopMainFile.exists(), "Desktop launcher source should exist").isTrue()

            assertThat(File(projectDir, "iosApp").exists(), "iOS app scaffold should be skipped for JVM-only template runs").isFalse()
            assertThat(File(projectDir, "androidApp").exists(), "Android app scaffold should be skipped for JVM-only template runs").isFalse()
            assertThat(File(projectDir, "webApp").exists(), "Web app scaffold should be skipped for JVM-only template runs").isFalse()
            assertThat(File(projectDir, "shared/src/iosMain").exists(), "iOS sources should be omitted for JVM-only template runs").isFalse()

            val sharedBuildContent = sharedBuildFile.readText()
            val desktopBuildContent = desktopBuildFile.readText()
            val rootBuildContent = rootBuildFile.readText()
            val settingsContent = settingsFile.readText()
            val appContent = appFile.readText()

            assertThat(sharedBuildContent).contains("jvm()")
            assertThat(sharedBuildContent).contains("implementation(libs.composables.ui)")
            assertThat(sharedBuildContent).contains("implementation(libs.compose.ui.tooling.preview)")
            assertThat(sharedBuildContent).doesNotContain("androidLibrary {")
            assertThat(sharedBuildContent).doesNotContain("android {")
            assertThat(sharedBuildContent).doesNotContain("iosArm64()")
            assertThat(sharedBuildContent).doesNotContain("wasmJs()")
            assertThat(sharedBuildContent).doesNotContain("{{shared_module_name}}")
            assertThat(desktopBuildContent).contains("implementation(projects.shared)")
            assertThat(desktopBuildContent).contains("mainClass = \"com.composables.demo.MainKt\"")

            assertThat(rootBuildContent).doesNotContain("composeCompatibilityBrowserDistribution")
            assertThat(rootBuildContent).doesNotContain("jsBrowserDistribution")
            assertThat(rootBuildContent).doesNotContain("wasmJsBrowserDistribution")
            assertThat(rootBuildContent).doesNotContain("js-preloads")
            assertThat(rootBuildContent).doesNotContain("wasm-preloads")
            assertThat(settingsContent).contains("""rootProject.name = "newApp"""")
            assertThat(settingsContent).contains("""include(":shared")""")
            assertThat(settingsContent).contains("""include(":desktopApp")""")

            assertThat(appContent).contains("package com.composables.demo")
            assertThat(appContent).contains("import androidx.compose.ui.tooling.preview.Preview")
            assertThat(appContent).contains("Hello Beautiful World!")
            assertThat(appContent).contains("Go to App.kt to edit your app")
            assertThat(appContent).contains("Pro tip: Use the `dev` configuration in your IDE to auto-reload your app when you edit your code")
            assertThat(appContent).doesNotContain("{{app_name}}")
            assertThat(appContent).doesNotContain("{{namespace}}")
        }
    }

    @Test
    fun `init derives project name from absolute directory path`() {
        val workingDir = File("workspace").absolutePath
        val projectPath = File("sample-app").absolutePath
        val targetDir = resolveTargetDirectory(
            workingDir = workingDir,
            projectPath = projectPath,
        )

        assertThat(targetDir.absolutePath).isEqualTo(projectPath)
        assertThat(targetDir.name).isEqualTo("sample-app")
    }

    @Test
    fun `cloneGradleProject renders Android as a separate app module`() {
        withTempDir { targetDir ->
            cloneGradleProject(
                targetDir = targetDir.absolutePath,
                dirName = "newApp",
                packageName = "com.composables.demo",
                moduleName = "sharedUi",
                appName = "The App",
                targets = setOf(ANDROID, JVM, IOS, WASM),
            )

            val projectDir = File(targetDir, "newApp")
            val sharedBuildFile = File(projectDir, "sharedUi/build.gradle.kts")
            val androidAppBuildFile = File(projectDir, "androidApp/build.gradle.kts")
            val settingsFile = File(projectDir, "settings.gradle.kts")
            val mainActivityFile = File(projectDir, "androidApp/src/main/kotlin/com/composables/demo/MainActivity.kt")

            assertThat(sharedBuildFile).exists()
            assertThat(androidAppBuildFile).exists()
            assertThat(mainActivityFile).exists()
            assertThat(File(projectDir, "sharedUi/src/androidMain").exists()).isFalse()

            val sharedBuildContent = sharedBuildFile.readText()
            val androidAppBuildContent = androidAppBuildFile.readText()
            val settingsContent = settingsFile.readText()

            assertThat(sharedBuildContent).contains("alias(libs.plugins.android.kotlin.multiplatform.library)")
            assertThat(sharedBuildContent).contains("android {")
            assertThat(sharedBuildContent).doesNotContain("androidLibrary {")
            assertThat(sharedBuildContent).contains("""namespace = "com.composables.demo.sharedUi"""")
            assertThat(sharedBuildContent).contains("androidRuntimeClasspath(libs.compose.ui.tooling)")
            assertThat(sharedBuildContent).doesNotContain("alias(libs.plugins.android.application)")
            assertThat(sharedBuildContent).doesNotContain("androidMain.dependencies")
            assertThat(sharedBuildContent).doesNotContain("defaultConfig {")

            assertThat(androidAppBuildContent).contains("alias(libs.plugins.android.application)")
            assertThat(androidAppBuildContent).contains("buildFeatures {")
            assertThat(androidAppBuildContent).contains("implementation(projects.sharedUi)")
            assertThat(settingsContent).contains("""include(":androidApp")""")
            assertThat(settingsContent).contains("""include(":desktopApp")""")
            assertThat(settingsContent).contains("""include(":webApp")""")
        }
    }

    @Test
    fun `parseTargets normalizes and de-duplicates targets`() {
        val targets = parseTargets("JVM, android, jvm, ios")

        assertThat(targets).isEqualTo(linkedSetOf(JVM, ANDROID, IOS))
    }

    @Test
    fun `parseTargets rejects unknown targets`() {
        val error = assertFailsWith<IllegalArgumentException> {
            parseTargets("android,desktop")
        }

        assertThat(error.message ?: "").contains("Unknown targets: desktop")
    }

    @Test
    fun `cloneGradleProject renders wasm preload wiring only when wasm target is selected`() {
        withTempDir { targetDir ->
            cloneGradleProject(
                targetDir = targetDir.absolutePath,
                dirName = "newApp",
                packageName = "com.composables.demo",
                moduleName = "shared",
                appName = "The App",
                targets = setOf(WASM),
            )

            val projectDir = File(targetDir, "newApp")
            val rootBuildContent = File(projectDir, "build.gradle.kts").readText()
            val sharedBuildContent = File(projectDir, "shared/build.gradle.kts").readText()
            val webAppBuildContent = File(projectDir, "webApp/build.gradle.kts").readText()

            assertThat(rootBuildContent).contains("wasmJsBrowserDistribution")
            assertThat(rootBuildContent).contains("wasm-preloads")
            assertThat(rootBuildContent).doesNotContain("composeCompatibilityBrowserDistribution")
            assertThat(rootBuildContent).doesNotContain("jsBrowserDistribution")
            assertThat(rootBuildContent).doesNotContain("js-preloads")
            assertThat(sharedBuildContent).contains("wasmJs {")
            assertThat(sharedBuildContent).contains("browser()")
            assertThat(sharedBuildContent).doesNotContain("js {")
            assertThat(webAppBuildContent).contains("implementation(libs.compose.ui)")
            assertThat(webAppBuildContent).contains("wasmJs {")
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
                """.trimIndent(),
            )

            updateRootBuildFile(targetDir.absolutePath, setOf(ANDROID))
            updateRootBuildFile(targetDir.absolutePath, setOf(ANDROID))

            val content = File(targetDir, "build.gradle.kts").readText()

            assertThat(content.countOccurrences("alias(libs.plugins.jetbrains.kotlin.multiplatform) apply false")).isEqualTo(1)
            assertThat(content.countOccurrences("alias(libs.plugins.jetbrains.compose) apply false")).isEqualTo(1)
            assertThat(content.countOccurrences("alias(libs.plugins.jetbrains.compose.compiler) apply false")).isEqualTo(1)
            assertThat(content.countOccurrences("alias(libs.plugins.jetbrains.compose.hotreload) apply false")).isEqualTo(1)
            assertThat(content.countOccurrences("alias(libs.plugins.android.application) apply false")).isEqualTo(1)
            assertThat(content.countOccurrences("alias(libs.plugins.android.kotlin.multiplatform.library) apply false")).isEqualTo(1)
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
                """.trimIndent() + "\n",
            )

            updateVersionCatalog(targetDir.absolutePath, setOf(ANDROID))
            updateVersionCatalog(targetDir.absolutePath, setOf(ANDROID))

            val content = File(targetDir, "gradle/libs.versions.toml").readText()

            assertThat(content.countOccurrences("""agp = "9.2.1"""")).isEqualTo(1)
            assertThat(content.countOccurrences("""androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }""")).isEqualTo(1)
            assertThat(content.countOccurrences("""composables-ui = { group = "com.composables", name = "ui", version.ref = "composablesUi" }""")).isEqualTo(1)
            assertThat(content.countOccurrences("""compose-ui = { group = "org.jetbrains.compose.ui", name = "ui", version.ref = "compose" }""")).isEqualTo(1)
            assertThat(content.countOccurrences("""android-application = { id = "com.android.application", version.ref = "agp" }""")).isEqualTo(1)
            assertThat(content.countOccurrences("""android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }""")).isEqualTo(1)
            assertThat(content).contains("""compose = "1.11.1"""")
            assertThat(content).contains("""composeHotReload = "1.1.0"""")
            assertThat(content).contains("""composablesUi = "0.1.0"""")
        }
    }

    @Test
    fun `gradleScript uses batch file on windows`() {
        withOsName("Windows 11") {
            assertThat(gradleScript).isEqualTo("gradlew.bat")
        }
    }

    @Test
    fun `gradleScript uses shell script on unix`() {
        withOsName("Linux") {
            assertThat(gradleScript).isEqualTo("./gradlew")
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

    private fun withOsName(value: String, block: () -> Unit) {
        val original = System.getProperty("os.name")
        try {
            System.setProperty("os.name", value)
            block()
        } finally {
            System.setProperty("os.name", original)
        }
    }

    private fun String.countOccurrences(value: String): Int = split(value).size - 1
}
