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

    val result =
        runProcess(
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
    val rootDir = createTempRoot("composables-cli-init")
    try {
      val projectDir = File(rootDir, "sample-app")
      val launcher = installedLauncher()

      val createResult =
          runProcess(
              command =
                  listOf(
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
      assertThat(createResult.output)
          .contains("${projectGradleScript()} :desktopApp:hotRunJvm --auto")
      assertJvmReadme(projectDir)

      val compileResult =
          runProcess(
              command = projectGradleCommand(":shared:compileKotlinJvm"),
              workingDir = projectDir,
              timeoutSeconds = 180,
          )

      assertThat(compileResult.finished).isTrue()
      assertThat(compileResult.exitCode).isEqualTo(0)
      assertThat(compileResult.output).contains("BUILD SUCCESSFUL")
      assertGeneratedProjectUsesKtfmt(projectDir)
    } finally {
      rootDir.deleteRecursively()
    }
  }

  @Test
  fun `cli init creates an android-only project`() {
    val rootDir = createTempRoot("composables-cli-init-android-only")
    try {
      val projectDir = File(rootDir, "sample-app")
      val launcher = installedLauncher()

      val createResult =
          runProcess(
              command =
                  listOf(
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

      val tasksResult =
          runProcess(
              command = projectGradleCommand("tasks", "--all"),
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
    val rootDir = createTempRoot("composables-cli-init-wasm-only")
    try {
      val projectDir = File(rootDir, "sample-app")
      val launcher = installedLauncher()

      val createResult =
          runProcess(
              command =
                  listOf(
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

      val compileResult =
          runProcess(
              command =
                  projectGradleCommand(
                      ":shared:compileKotlinWasmJs", ":webApp:compileKotlinWasmJs"),
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
    val rootDir = createTempRoot("composables-cli-init-interactive")
    try {
      val projectDir = File(rootDir, "sample-app")
      val launcher = installedLauncher()

      val createResult =
          runProcess(
              command = listOf(launcher.absolutePath, "init"),
              workingDir = rootDir,
              stdin = "sample-app\ncom.example.sampleapp\nSample App\nn\ny\nn\nn\n",
              timeoutSeconds = 60,
          )

      assertThat(createResult.finished).isTrue()
      assertThat(createResult.exitCode).isEqualTo(0)
      assertThat(createResult.output).contains("Success! Your new Compose app is ready")
      assertThat(createResult.output)
          .contains("${projectGradleScript()} :desktopApp:hotRunJvm --auto")
      assertJvmReadme(projectDir)

      val compileResult =
          runProcess(
              command = projectGradleCommand(":shared:compileKotlinJvm"),
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
    val rootDir = createTempRoot("composables-cli-init-no-stdin")
    try {
      val launcher = installedLauncher()

      val createResult =
          runProcess(
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
    val rootDir = createTempRoot("composables-cli-init-existing")
    try {
      val projectDir =
          File(rootDir, "sample-app").apply {
            mkdirs()
            File(this, "keep.txt").writeText("existing")
          }
      val launcher = installedLauncher()

      val createResult =
          runProcess(
              command =
                  listOf(
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
    val rootDir = createTempRoot("composables-cli-init-partial")
    try {
      val launcher = installedLauncher()

      val createResult =
          runProcess(
              command =
                  listOf(
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
    val rootDir = createTempRoot("composables-cli-add-module")
    try {
      val isWindows = System.getProperty("os.name").startsWith("Windows")
      val projectName = if (isWindows) "s" else "sample-app"
      val rootPackage = if (isWindows) "c.e.s" else "com.example.sampleapp"
      val rootAppName = if (isWindows) "S" else "Sample App"
      val nestedModulePath = if (isWindows) "a/f" else "apps/feature-app"
      val nestedPackage = if (isWindows) "c.e.f" else "com.example.featureapp"
      val nestedAppName = if (isWindows) "F" else "Feature App"
      val nestedPackagePath = nestedPackage.replace(".", "/")
      val nestedModuleGradlePath = ":" + nestedModulePath.replace('/', ':')
      val projectDir = File(rootDir, projectName)
      val launcher = installedLauncher()

      val initResult =
          runProcess(
              command =
                  listOf(
                      launcher.absolutePath,
                      "init",
                      projectDir.absolutePath,
                      "--package",
                      rootPackage,
                      "--app-name",
                      rootAppName,
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

      val addResult =
          runProcess(
              command =
                  listOf(
                      launcher.absolutePath,
                      "add",
                      "module",
                      nestedModulePath,
                      "--package",
                      nestedPackage,
                      "--app-name",
                      nestedAppName,
                      "--targets",
                      "android,jvm,wasm",
                  ),
              workingDir = projectDir,
              timeoutSeconds = 60,
          )

      assertThat(addResult.finished).isTrue()
      assertThat(addResult.exitCode).isEqualTo(0)
      val nestedModuleDir = File(projectDir, nestedModulePath)
      assertThat(File(nestedModuleDir, "shared/build.gradle.kts").exists()).isTrue()
      assertThat(
              File(nestedModuleDir, "shared/src/commonMain/kotlin/$nestedPackagePath/App.kt")
                  .exists())
          .isTrue()
      assertThat(File(nestedModuleDir, "androidApp/build.gradle.kts").exists()).isTrue()
      assertThat(File(nestedModuleDir, "desktopApp/build.gradle.kts").exists()).isTrue()
      assertThat(File(nestedModuleDir, "webApp/build.gradle.kts").exists()).isTrue()
      assertThat(File(nestedModuleDir, "iosApp").exists()).isFalse()
      val settingsContent = File(projectDir, "settings.gradle.kts").readText()
      assertThat(settingsContent).contains("""include("$nestedModuleGradlePath:shared")""")
      assertThat(settingsContent).contains("""include("$nestedModuleGradlePath:androidApp")""")
      assertThat(settingsContent).contains("""include("$nestedModuleGradlePath:desktopApp")""")
      assertThat(settingsContent).contains("""include("$nestedModuleGradlePath:webApp")""")
      assertThat(File(projectDir, "build.gradle.kts").readText()).isEqualTo(rootBuildBeforeAdd)
      assertThat(File(projectDir, "gradle/libs.versions.toml").readText())
          .isEqualTo(versionCatalogBeforeAdd)

      val sharedBuildFile = File(nestedModuleDir, "shared/build.gradle.kts").readText()
      val androidAppBuildFile = File(nestedModuleDir, "androidApp/build.gradle.kts").readText()
      val desktopMainFile =
          File(nestedModuleDir, "desktopApp/src/jvmMain/kotlin/$nestedPackagePath/main.kt")
              .readText()
      val webBuildFile = File(nestedModuleDir, "webApp/build.gradle.kts").readText()
      val webIndexFile =
          File(nestedModuleDir, "webApp/src/wasmJsMain/resources/index.html").readText()
      assertThat(sharedBuildFile).contains("alias(libs.plugins.kotlin.multiplatform)")
      assertThat(sharedBuildFile).contains("alias(libs.plugins.compose)")
      assertThat(sharedBuildFile).contains("alias(libs.plugins.compose.compiler)")
      assertThat(sharedBuildFile)
          .contains("alias(libs.plugins.android.kotlin.multiplatform.library)")
      assertThat(sharedBuildFile)
          .contains("compileSdk = libs.versions.android.compile.sdk.get().toInt()")
      assertThat(sharedBuildFile).contains("minSdk = libs.versions.android.min.sdk.get().toInt()")
      assertThat(sharedBuildFile).contains("implementation(libs.composables.ui)")
      assertThat(sharedBuildFile).contains("implementation(libs.composables.uri.painter)")
      assertThat(androidAppBuildFile)
          .contains("""implementation(project("$nestedModuleGradlePath:shared"))""")
      assertThat(desktopMainFile).contains("""singleWindowApplication(title = "$nestedAppName")""")
      assertThat(webBuildFile).contains("injectWasmPreloads")
      assertThat(webIndexFile).contains("<title>$nestedAppName</title>")

      if (isWindows) {
        val evaluationResult =
            runProcess(
                command = projectGradleCommand("$nestedModuleGradlePath:webApp:tasks", "--all"),
                workingDir = projectDir,
                timeoutSeconds = 180,
            )

        assertThat(evaluationResult.finished).isTrue()
        assertThat(evaluationResult.exitCode).isEqualTo(0)
        assertThat(evaluationResult.output).contains("wasmJsBrowserDistribution")
      } else {
        val compileResult =
            runProcess(
                command =
                    projectGradleCommand(
                        "$nestedModuleGradlePath:shared:compileKotlinJvm",
                        "$nestedModuleGradlePath:shared:compileKotlinWasmJs",
                        "$nestedModuleGradlePath:desktopApp:compileKotlinJvm",
                        "$nestedModuleGradlePath:webApp:compileKotlinWasmJs",
                    ),
                workingDir = projectDir,
                timeoutSeconds = 180,
            )

        assertThat(compileResult.finished).isTrue()
        assertThat(compileResult.exitCode).isEqualTo(0)
        assertThat(compileResult.output).contains("BUILD SUCCESSFUL")

        val distributionResult =
            runProcess(
                command =
                    projectGradleCommand(
                        "$nestedModuleGradlePath:webApp:wasmJsBrowserDistribution"),
                workingDir = projectDir,
                timeoutSeconds = 180,
            )

        assertThat(distributionResult.finished).isTrue()
        assertThat(distributionResult.exitCode).isEqualTo(0)
        val distributedIndexFile =
            File(
                    projectDir,
                    "$nestedModulePath/webApp/build/dist/wasmJs/productionExecutable/index.html",
                )
                .readText()
        assertThat(distributedIndexFile).contains("<!-- wasm-preloads:start -->")
        assertThat(distributedIndexFile).contains("""rel="preload"""")
      }
    } finally {
      rootDir.deleteRecursively()
    }
  }

  @Test
  fun `rewriteProjectToUiStyleConventions removes type safe project accessors on CRLF settings files`() {
    val rootDir = createTempRoot("composables-cli-ui-style-crlf")
    try {
      val projectDir = File(rootDir, "sample-app").apply { mkdirs() }
      File(projectDir, "settings.gradle.kts")
          .writeText(
              "rootProject.name = \"sample-app\"\r\n\r\nenableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\")\r\n\r\ninclude(\":shared\")\r\n",
          )

      rewriteProjectToUiStyleConventions(projectDir)

      val rewritten = File(projectDir, "settings.gradle.kts").readText()
      assertThat(rewritten).doesNotContain("enableFeaturePreview(\"TYPESAFE_PROJECT_ACCESSORS\")")
    } finally {
      rootDir.deleteRecursively()
    }
  }

  private fun rewriteProjectToUiStyleConventions(projectDir: File) {
    val replacements =
        listOf(
            "libs.plugins.jetbrains.kotlin.multiplatform" to "libs.plugins.kotlin.multiplatform",
            "libs.plugins.jetbrains.compose.compiler" to "libs.plugins.compose.compiler",
            "libs.plugins.jetbrains.compose" to "libs.plugins.compose",
            "libs.versions.android.compileSdk.get().toInt()" to
                "libs.versions.android.compile.sdk.get().toInt()",
            "libs.versions.android.minSdk.get().toInt()" to
                "libs.versions.android.min.sdk.get().toInt()",
            "libs.versions.android.targetSdk.get().toInt()" to
                "libs.versions.android.target.sdk.get().toInt()",
            "implementation(projects.shared)" to """implementation(project(":shared"))""",
            "jetbrains-kotlin-multiplatform" to "kotlin-multiplatform",
            "jetbrains-compose-compiler" to "compose-compiler",
            "jetbrains-compose" to "compose",
            "android-compileSdk" to "android-compile-sdk",
            "android-minSdk" to "android-min-sdk",
            "android-targetSdk" to "android-target-sdk",
        )

    projectDir
        .walkTopDown()
        .filter { it.isFile && (it.extension == "kts" || it.name == "libs.versions.toml") }
        .forEach { file ->
          val updated =
              replacements.fold(file.readText()) { content, (from, to) ->
                content.replace(from, to)
              }
          val normalized =
              updated.replace(
                  Regex("""enableFeaturePreview\("TYPESAFE_PROJECT_ACCESSORS"\)\r?\n(?:\r?\n)?"""),
                  "",
              )
          file.writeText(normalized)
        }
  }

  private fun installedLauncher(): File {
    val scriptName =
        if (System.getProperty("os.name").startsWith("Windows")) "composables.bat"
        else "composables"
    val launcher = File("build/install/composables/bin/$scriptName")
    check(launcher.isFile) { "Expected installed launcher at ${launcher.absolutePath}" }
    return launcher
  }

  private fun shadowJarArtifact(): File {
    val jar = File("build/libs/composables.jar")
    check(jar.isFile) { "Expected shadow jar at ${jar.absolutePath}" }
    return jar
  }

  private fun createTempRoot(prefix: String): File {
    val safePrefix =
        if (System.getProperty("os.name").startsWith("Windows")) {
          prefix.take(8)
        } else {
          prefix
        }
    return Files.createTempDirectory(safePrefix).toFile()
  }

  private fun projectGradleScript(): String =
      if (System.getProperty("os.name").startsWith("Windows")) {
        "gradlew.bat"
      } else {
        "./gradlew"
      }

  private fun projectGradleCommand(vararg arguments: String): List<String> = buildList {
    add(projectGradleScript())
    add("--no-daemon")
    add("--console=plain")
    addAll(arguments)
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

    val hotRunHelp =
        runProcess(
            command = projectGradleCommand("help", "--task", ":desktopApp:hotRunJvm"),
            workingDir = projectDir,
            timeoutSeconds = 180,
        )

    assertThat(hotRunHelp.finished).isTrue()
    assertThat(hotRunHelp.exitCode).isEqualTo(0)
    assertThat(hotRunHelp.output).contains("Path")
    assertThat(hotRunHelp.output).contains(":desktopApp:hotRunJvm")
    assertThat(hotRunHelp.output).contains("--auto")
    assertThat(hotRunHelp.output)
        .contains("Enables automatic recompilation/reload once the source files change")
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

  private fun assertGeneratedProjectUsesKtfmt(projectDir: File) {
    val rootBuild = File(projectDir, "build.gradle.kts").readText()
    assertThat(rootBuild).contains("alias(libs.plugins.spotless)")
    assertThat(rootBuild).contains("ktfmt()")

    val sharedApp =
        File(projectDir, "shared/src/commonMain/kotlin/com/example/sampleapp/App.kt").readText()
    assertThat(sharedApp)
        .contains("modifier = Modifier.safeDrawingPadding().fillMaxSize().padding(16.dp),")
    assertThat(sharedApp)
        .contains(
            "text =\n            \"Pro tip: Use the `dev` configuration in your IDE to auto-reload your app when you edit your code\",",
        )

    val desktopMain =
        File(projectDir, "desktopApp/src/jvmMain/kotlin/com/example/sampleapp/main.kt").readText()
    assertThat(desktopMain)
        .contains("fun main() = singleWindowApplication(title = \"Sample App\") { App() }")
  }

  private fun runProcess(
      command: List<String>,
      workingDir: File,
      stdin: String = "",
      timeoutSeconds: Long,
  ): ProcessResult {
    val process =
        ProcessBuilder(platformCommand(command))
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()

    val output = StringBuilder()
    val readerThread =
        Thread {
              process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> output.appendLine(line) }
              }
            }
            .apply { start() }

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
