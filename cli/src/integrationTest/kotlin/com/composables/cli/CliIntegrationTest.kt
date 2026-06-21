package com.composables.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test

class CliIntegrationTest {

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

            val compileResult = runProcess(
                command = listOf("./gradlew", ":composeApp:compileKotlinJvm"),
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

    private fun installedLauncher(): File {
        val scriptName = if (System.getProperty("os.name").startsWith("Windows")) "composables.bat" else "composables"
        val launcher = File("build/install/composables/bin/$scriptName")
        check(launcher.isFile) { "Expected installed launcher at ${launcher.absolutePath}" }
        return launcher
    }

    private fun runProcess(
        command: List<String>,
        workingDir: File,
        stdin: String = "",
        timeoutSeconds: Long,
    ): ProcessResult {
        val process = ProcessBuilder(command)
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

    private data class ProcessResult(
        val finished: Boolean,
        val exitCode: Int,
        val output: String,
    )
}
