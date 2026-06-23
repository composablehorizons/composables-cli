package com.composables.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
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
        val rootDir = Files.createTempDirectory("composables-cli-integration").toFile()
        try {
            val projectDir = File(rootDir, "sample-app")
            val launcher = installedLauncher()

            val initResult = runProcess(
                command = listOf(launcher.absolutePath, "init", projectDir.absolutePath),
                workingDir = rootDir,
                stdin = "\nSample App\ncom.example.sampleapp\nn\ny\nn\nn\n",
                timeoutSeconds = 60,
            )

            assertThat(initResult.finished).isTrue()
            assertThat(initResult.exitCode).isEqualTo(0)
            assertThat(initResult.output).contains("Success! Your new Compose app is ready")
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
    fun `cli create-app creates a jvm project that compiles`() {
        val rootDir = Files.createTempDirectory("composables-cli-create-app").toFile()
        try {
            val projectDir = File(rootDir, "sample-app")
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(
                    launcher.absolutePath,
                    "create-app",
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
    fun `cli create-app with no args runs interactively and creates a jvm project that compiles`() {
        val rootDir = Files.createTempDirectory("composables-cli-create-app-interactive").toFile()
        try {
            val projectDir = File(rootDir, "sample-app")
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(launcher.absolutePath, "create-app"),
                workingDir = rootDir,
                stdin = "sample-app\ncom.example.sampleapp\nSample App\nn\ny\nn\nn\n",
                timeoutSeconds = 60,
            )

            assertThat(createResult.finished).isTrue()
            assertThat(createResult.exitCode).isEqualTo(0)
            assertThat(createResult.output).contains("Success! Your new Compose app is ready")
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
    fun `cli create-app with no args fails cleanly without stdin`() {
        val rootDir = Files.createTempDirectory("composables-cli-create-app-no-stdin").toFile()
        try {
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(launcher.absolutePath, "create-app"),
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
    fun `cli create-app requires overwrite for non-empty directories`() {
        val rootDir = Files.createTempDirectory("composables-cli-create-app-existing").toFile()
        try {
            val projectDir = File(rootDir, "sample-app").apply {
                mkdirs()
                File(this, "keep.txt").writeText("existing")
            }
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(
                    launcher.absolutePath,
                    "create-app",
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
    fun `cli create-app with partial args fails without prompting`() {
        val rootDir = Files.createTempDirectory("composables-cli-create-app-partial").toFile()
        try {
            val launcher = installedLauncher()

            val createResult = runProcess(
                command = listOf(
                    launcher.absolutePath,
                    "create-app",
                    "sample-app",
                    "--package",
                    "com.example.sampleapp",
                ),
                workingDir = rootDir,
                timeoutSeconds = 60,
            )

            assertThat(createResult.finished).isTrue()
            assertThat(createResult.exitCode).isEqualTo(1)
            assertThat(createResult.output).contains("When using create-app non-interactively")
            assertThat(createResult.output).contains("--app-name")
            assertThat(createResult.output).contains("--targets")
        } finally {
            rootDir.deleteRecursively()
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
