package com.composables.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class CliIntegrationTest {

    @Test
    fun `published shadow jar runs standalone`() {
        val shadowJar = shadowJarArtifact()

        val result = runProcess(
            command = listOf("java", "-jar", shadowJar.absolutePath, "--version"),
            workingDir = shadowJar.parentFile,
            timeoutSeconds = 60,
        )

        assertThat(result.finished).isTrue()
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(Regex("""\d+\.\d+\.\d+""").matches(result.output.trim())).isTrue()
        assertThat(result.output).doesNotContain("NoClassDefFoundError")
    }

    @Test
    fun `cli init creates a jvm project that compiles`() {
        val rootDir = Files.createTempDirectory("composables-cli-init").toFile()
        try {
            val projectDir = File(rootDir, "sample-app")
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(
                    launcher.absolutePath,
                    "init",
                    projectDir.absolutePath,
                    "--package",
                    "com.example.sampleapp",
                    "--app-name",
                    "Sample App",
                    "--targets",
                    "jvm",
                ),
                workingDir = rootDir,
                timeoutSeconds = 60,
            )

            assertThat(createResult.finished).isTrue()
            assertThat(createResult.exitCode).isEqualTo(0)
            assertThat(createResult.output).contains("Success! Your new Compose app is ready")
            assertThat(createResult.output).contains("${projectGradleScript()} :desktopApp:hotRunJvm --auto")
            assertJvmReadme(projectDir)

            val compileResult = runProcess(
                command = listOf(projectGradleScript(), ":shared:compileKotlinJvm"),
                workingDir = projectDir,
                timeoutSeconds = 180,
            )

            assertThat(compileResult.finished).isTrue()
            assertThat(compileResult.exitCode).isEqualTo(0)
            assertThat(compileResult.output).contains("BUILD SUCCESSFUL")
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `cli init creates an android-only project`() {
        val rootDir = Files.createTempDirectory("composables-cli-init-android-only").toFile()
        try {
            val projectDir = File(rootDir, "sample-app")
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(
                    launcher.absolutePath,
                    "init",
                    projectDir.absolutePath,
                    "--package",
                    "com.example.sampleapp",
                    "--app-name",
                    "Sample App",
                    "--targets",
                    "android",
                ),
                workingDir = rootDir,
                timeoutSeconds = 60,
            )

            assertThat(createResult.finished).isTrue()
            assertThat(createResult.exitCode).isEqualTo(0)
            assertThat(createResult.output).contains("Success! Your new Compose app is ready")
            assertAndroidReadme(projectDir)
            assertThat(File(projectDir, "shared").isDirectory).isTrue()
            assertThat(File(projectDir, "androidApp").isDirectory).isTrue()
            assertThat(File(projectDir, "desktopApp").exists()).isFalse()
            assertThat(File(projectDir, "iosApp").exists()).isFalse()
            assertThat(File(projectDir, "webApp").exists()).isFalse()

            val tasksResult = runProcess(
                command = listOf(projectGradleScript(), "tasks", "--all"),
                workingDir = projectDir,
                timeoutSeconds = 180,
            )

            assertThat(tasksResult.finished).isTrue()
            assertThat(tasksResult.exitCode).isEqualTo(0)
            assertThat(tasksResult.output).contains("androidApp:installDebug")
            assertThat(tasksResult.output).doesNotContain("webApp:wasmJsBrowserDevelopmentRun")
            assertThat(tasksResult.output).doesNotContain("desktopApp:run")
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `cli init creates a wasm-only project that compiles`() {
        val rootDir = Files.createTempDirectory("composables-cli-init-wasm-only").toFile()
        try {
            val projectDir = File(rootDir, "sample-app")
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(
                    launcher.absolutePath,
                    "init",
                    projectDir.absolutePath,
                    "--package",
                    "com.example.sampleapp",
                    "--app-name",
                    "Sample App",
                    "--targets",
                    "wasm",
                ),
                workingDir = rootDir,
                timeoutSeconds = 60,
            )

            assertThat(createResult.finished).isTrue()
            assertThat(createResult.exitCode).isEqualTo(0)
            assertThat(createResult.output).contains("Success! Your new Compose app is ready")
            assertWasmReadme(projectDir)

            val compileResult = runProcess(
                command = listOf(projectGradleScript(), ":shared:compileKotlinWasmJs", ":webApp:compileKotlinWasmJs"),
                workingDir = projectDir,
                timeoutSeconds = 180,
            )

            assertThat(compileResult.finished).isTrue()
            assertThat(compileResult.exitCode).isEqualTo(0)
            assertThat(compileResult.output).contains("BUILD SUCCESSFUL")
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `cli init with no args runs interactively and creates a jvm project that compiles`() {
        val rootDir = Files.createTempDirectory("composables-cli-init-interactive").toFile()
        try {
            val projectDir = File(rootDir, "sample-app")
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(launcher.absolutePath, "init"),
                workingDir = rootDir,
                stdin = "sample-app\ncom.example.sampleapp\nSample App\nn\ny\nn\nn\n",
                timeoutSeconds = 60,
            )

            assertThat(createResult.finished).isTrue()
            assertThat(createResult.exitCode).isEqualTo(0)
            assertThat(createResult.output).contains("Success! Your new Compose app is ready")
            assertThat(createResult.output).contains("${projectGradleScript()} :desktopApp:hotRunJvm --auto")
            assertJvmReadme(projectDir)

            val compileResult = runProcess(
                command = listOf(projectGradleScript(), ":shared:compileKotlinJvm"),
                workingDir = projectDir,
                timeoutSeconds = 180,
            )

            assertThat(compileResult.finished).isTrue()
            assertThat(compileResult.exitCode).isEqualTo(0)
            assertThat(compileResult.output).contains("BUILD SUCCESSFUL")
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `cli init with no args fails cleanly without stdin`() {
        val rootDir = Files.createTempDirectory("composables-cli-init-no-stdin").toFile()
        try {
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(launcher.absolutePath, "init"),
                workingDir = rootDir,
                timeoutSeconds = 60,
            )

            assertThat(createResult.finished).isTrue()
            assertThat(createResult.exitCode).isEqualTo(1)
            assertThat(createResult.output).contains("Interactive mode requires stdin")
            assertThat(createResult.output).doesNotContain("ReadAfterEOFException")
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `cli init requires overwrite for non-empty directories`() {
        val rootDir = Files.createTempDirectory("composables-cli-init-existing").toFile()
        try {
            val projectDir = File(rootDir, "sample-app").apply {
                mkdirs()
                File(this, "keep.txt").writeText("existing")
            }
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(
                    launcher.absolutePath,
                    "init",
                    projectDir.absolutePath,
                    "--package",
                    "com.example.sampleapp",
                    "--app-name",
                    "Sample App",
                    "--targets",
                    "jvm",
                ),
                workingDir = rootDir,
                timeoutSeconds = 60,
            )

            assertThat(createResult.finished).isTrue()
            assertThat(createResult.exitCode).isEqualTo(1)
            assertThat(createResult.output).contains("already exists and is not empty")
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `cli init with partial args fails without prompting`() {
        val rootDir = Files.createTempDirectory("composables-cli-init-partial").toFile()
        try {
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(
                    launcher.absolutePath,
                    "init",
                    "sample-app",
                    "--package",
                    "com.example.sampleapp",
                ),
                workingDir = rootDir,
                timeoutSeconds = 60,
            )

            assertThat(createResult.finished).isTrue()
            assertThat(createResult.exitCode).isEqualTo(1)
            assertThat(createResult.output).contains("When using init non-interactively")
            assertThat(createResult.output).contains("--app-name")
            assertThat(createResult.output).contains("--targets")
        } finally {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun `cli add module creates a nested app module group that compiles`() {
        val rootDir = Files.createTempDirectory("composables-cli-add-module").toFile()
        try {
            val projectDir = File(rootDir, "sample-app")
            val launcher = installedLauncher()

            val initResult = runProcess(
                command = listOf(
                    launcher.absolutePath,
                    "init",
                    projectDir.absolutePath,
                    "--package",
                    "com.example.sampleapp",
                    "--app-name",
                    "Sample App",
                    "--targets",
                    "android,jvm,wasm",
                ),
                workingDir = rootDir,
                timeoutSeconds = 60,
            )

            assertThat(initResult.finished).isTrue()
            assertThat(initResult.exitCode).isEqualTo(0)

            rewriteProjectToUiStyleConventions(projectDir)
            val rootBuildBeforeAdd = File(projectDir, "build.gradle.kts").readText()
            val versionCatalogBeforeAdd = File(projectDir, "gradle/libs.versions.toml").readText()

            val addResult = runProcess(
                command = listOf(
                    launcher.absolutePath,
                    "add",
                    "module",
                    "apps/feature-app",
                    "--package",
                    "com.example.featureapp",
                    "--app-name",
                    "Feature App",
                    "--targets",
                    "android,jvm,wasm",
                ),
                workingDir = projectDir,
                timeoutSeconds = 60,
            )

            assertThat(addResult.finished).isTrue()
            assertThat(addResult.exitCode).isEqualTo(0)
            assertThat(File(projectDir, "apps/feature-app/shared/build.gradle.kts").exists()).isTrue()
            assertThat(File(projectDir, "apps/feature-app/shared/src/commonMain/kotlin/com/example/featureapp/App.kt").exists()).isTrue()
            assertThat(File(projectDir, "apps/feature-app/androidApp/build.gradle.kts").exists()).isTrue()
            assertThat(File(projectDir, "apps/feature-app/desktopApp/build.gradle.kts").exists()).isTrue()
            assertThat(File(projectDir, "apps/feature-app/webApp/build.gradle.kts").exists()).isTrue()
            assertThat(File(projectDir, "apps/feature-app/iosApp").exists()).isFalse()
            val settingsContent = File(projectDir, "settings.gradle.kts").readText()
            assertThat(settingsContent).contains("""include(":apps:feature-app:shared")""")
            assertThat(settingsContent).contains("""include(":apps:feature-app:androidApp")""")
            assertThat(settingsContent).contains("""include(":apps:feature-app:desktopApp")""")
            assertThat(settingsContent).contains("""include(":apps:feature-app:webApp")""")
            assertThat(File(projectDir, "build.gradle.kts").readText()).isEqualTo(rootBuildBeforeAdd)
            assertThat(File(projectDir, "gradle/libs.versions.toml").readText()).isEqualTo(versionCatalogBeforeAdd)

            val sharedBuildFile = File(projectDir, "apps/feature-app/shared/build.gradle.kts").readText()
            val androidAppBuildFile = File(projectDir, "apps/feature-app/androidApp/build.gradle.kts").readText()
            val webBuildFile = File(projectDir, "apps/feature-app/webApp/build.gradle.kts").readText()
            val webIndexFile = File(projectDir, "apps/feature-app/webApp/src/wasmJsMain/resources/index.html").readText()
            assertThat(sharedBuildFile).contains("alias(libs.plugins.kotlin.multiplatform)")
            assertThat(sharedBuildFile).contains("alias(libs.plugins.compose)")
            assertThat(sharedBuildFile).contains("alias(libs.plugins.compose.compiler)")
            assertThat(sharedBuildFile).contains("alias(libs.plugins.android.kotlin.multiplatform.library)")
            assertThat(sharedBuildFile).contains("compileSdk = libs.versions.android.compile.sdk.get().toInt()")
            assertThat(sharedBuildFile).contains("minSdk = libs.versions.android.min.sdk.get().toInt()")
            assertThat(sharedBuildFile).contains("implementation(libs.composables.ui)")
            assertThat(androidAppBuildFile).contains("""implementation(project(":apps:feature-app:shared"))""")
            assertThat(webBuildFile).contains("injectWasmPreloads")
            assertThat(webIndexFile).contains("<title>Feature App</title>")

            val compileResult = runProcess(
                command = listOf(
                    projectGradleScript(),
                    ":apps:feature-app:shared:compileKotlinJvm",
                    ":apps:feature-app:shared:compileKotlinWasmJs",
                    ":apps:feature-app:desktopApp:compileKotlinJvm",
                    ":apps:feature-app:webApp:compileKotlinWasmJs",
                ),
                workingDir = projectDir,
                timeoutSeconds = 180,
            )

            assertThat(compileResult.finished).isTrue()
            assertThat(compileResult.exitCode).isEqualTo(0)
            assertThat(compileResult.output).contains("BUILD SUCCESSFUL")

            val distributionResult = runProcess(
                command = listOf(projectGradleScript(), ":apps:feature-app:webApp:wasmJsBrowserDistribution"),
                workingDir = projectDir,
                timeoutSeconds = 180,
            )

            assertThat(distributionResult.finished).isTrue()
            assertThat(distributionResult.exitCode).isEqualTo(0)
            val distributedIndexFile = File(
                projectDir,
                "apps/feature-app/webApp/build/dist/wasmJs/productionExecutable/index.html",
            ).readText()
            assertThat(distributedIndexFile).contains("<!-- wasm-preloads:start -->")
            assertThat(distributedIndexFile).contains("""rel="preload"""")
        } finally {
            rootDir.deleteRecursively()
        }
    }

    private fun rewriteProjectToUiStyleConventions(projectDir: File) {
        val replacements = listOf(
            "libs.plugins.jetbrains.kotlin.multiplatform" to "libs.plugins.kotlin.multiplatform",
            "libs.plugins.jetbrains.compose.compiler" to "libs.plugins.compose.compiler",
            "libs.plugins.jetbrains.compose" to "libs.plugins.compose",
            "libs.versions.android.compileSdk.get().toInt()" to "libs.versions.android.compile.sdk.get().toInt()",
            "libs.versions.android.minSdk.get().toInt()" to "libs.versions.android.min.sdk.get().toInt()",
            "libs.versions.android.targetSdk.get().toInt()" to "libs.versions.android.target.sdk.get().toInt()",
            "implementation(projects.shared)" to """implementation(project(":shared"))""",
            "jetbrains-kotlin-multiplatform" to "kotlin-multiplatform",
            "jetbrains-compose-compiler" to "compose-compiler",
            "jetbrains-compose" to "compose",
            "android-compileSdk" to "android-compile-sdk",
            "android-minSdk" to "android-min-sdk",
            "android-targetSdk" to "android-target-sdk",
            "enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\")\n\n" to "",
        )

        projectDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kts" || it.name == "libs.versions.toml") }
            .forEach { file ->
                val updated = replacements.fold(file.readText()) { content, (from, to) ->
                    content.replace(from, to)
                }
                file.writeText(updated)
            }
    }

    private fun installedLauncher(): File {
        val scriptName = if (System.getProperty("os.name").startsWith("Windows")) "composables.bat" else "composables"
        val launcher = File("build/install/composables/bin/$scriptName")
        check(launcher.isFile) { "Expected installed launcher at ${launcher.absolutePath}" }
        return launcher
    }

    private fun shadowJarArtifact(): File {
        val jar = File("build/libs/composables.jar")
        check(jar.isFile) { "Expected shadow jar at ${jar.absolutePath}" }
        return jar
    }

    private fun projectGradleScript(): String = if (System.getProperty("os.name").startsWith("Windows")) {
        "gradlew.bat"
    } else {
        "./gradlew"
    }

    private fun assertJvmReadme(projectDir: File) {
        val readme = File(projectDir, "README.md")
        assertThat(readme.exists()).isTrue()

        val content = readme.readText()
        assertThat(content).contains("# ${projectDir.name}")
        assertThat(content).contains("## Run")
        assertThat(content).contains("`./gradlew :desktopApp:hotRunJvm --auto`")
        assertThat(content).doesNotContain(":androidApp:installDebug")
        assertThat(content).doesNotContain("iosApp/iosApp.xcodeproj")
        assertThat(content).doesNotContain(":webApp:wasmJsBrowserDevelopmentRun")

        val hotRunHelp = runProcess(
            command = listOf(projectGradleScript(), "help", "--task", ":desktopApp:hotRunJvm"),
            workingDir = projectDir,
            timeoutSeconds = 180,
        )

        assertThat(hotRunHelp.finished).isTrue()
        assertThat(hotRunHelp.exitCode).isEqualTo(0)
        assertThat(hotRunHelp.output).contains("Path")
        assertThat(hotRunHelp.output).contains(":desktopApp:hotRunJvm")
        assertThat(hotRunHelp.output).contains("--auto")
        assertThat(hotRunHelp.output).contains("Enables automatic recompilation/reload once the source files change")
    }

    private fun assertAndroidReadme(projectDir: File) {
        val readme = File(projectDir, "README.md")
        assertThat(readme.exists()).isTrue()

        val content = readme.readText()
        assertThat(content).contains("# ${projectDir.name}")
        assertThat(content).contains("## Run")
        assertThat(content).contains("`./gradlew :androidApp:installDebug`")
        assertThat(content).doesNotContain(":desktopApp:hotRunJvm --auto")
        assertThat(content).doesNotContain("iosApp/iosApp.xcodeproj")
        assertThat(content).doesNotContain(":webApp:wasmJsBrowserDevelopmentRun")
    }

    private fun assertWasmReadme(projectDir: File) {
        val readme = File(projectDir, "README.md")
        assertThat(readme.exists()).isTrue()

        val content = readme.readText()
        assertThat(content).contains("# ${projectDir.name}")
        assertThat(content).contains("## Run")
        assertThat(content).contains("`./gradlew :webApp:wasmJsBrowserDevelopmentRun`")
        assertThat(content).doesNotContain(":androidApp:installDebug")
        assertThat(content).doesNotContain(":desktopApp:hotRunJvm --auto")
        assertThat(content).doesNotContain("iosApp/iosApp.xcodeproj")
    }

    private fun runProcess(
        command: List<String>,
        workingDir: File,
        stdin: String = "",
        timeoutSeconds: Long,
    ): ProcessResult {
        val process = ProcessBuilder(platformCommand(command))
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val readerThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    output.appendLine(line)
                }
            }
        }.apply { start() }

        process.outputStream.bufferedWriter().use { writer ->
            if (stdin.isNotEmpty()) {
                writer.write(stdin)
                writer.flush()
            }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        readerThread.join()

        return ProcessResult(
            finished = finished,
            exitCode = if (finished) process.exitValue() else -1,
            output = output.toString(),
        )
    }

    private fun platformCommand(command: List<String>): List<String> {
        val executable = command.firstOrNull() ?: return command
        val isWindows = System.getProperty("os.name").startsWith("Windows")
        return if (isWindows && (executable.endsWith(".bat") || executable.endsWith(".cmd"))) {
            listOf("cmd.exe", "/c") + command
        } else {
            command
        }
    }

    private data class ProcessResult(
        val finished: Boolean,
        val exitCode: Int,
        val output: String,
    )
}
