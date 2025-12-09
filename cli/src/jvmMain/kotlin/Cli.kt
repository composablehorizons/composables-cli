package com.composables.cli

import com.alexstyl.debugln.debugln
import com.alexstyl.debugln.infoln
import com.alexstyl.debugln.warnln
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.versionOption
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

val ANDROID = "android"
val JVM = "jvm"
val IOS = "ios"
val WEB = "web"

suspend fun main(args: Array<String>) {
    ComposablesCli()
        .subcommands(Init(), Update(), Target())
        .main(args)
}

class ComposablesCli : CliktCommand(name = "composables") {
    init {
        versionOption(
            version = BuildConfig.Version,
            names = setOf("-v", "--version"),
            message = { BuildConfig.Version }
        )
    }

    override fun run() {
    }

    override fun help(context: Context) = """
        If you have any problems or need help, do not hesitate to ask for help at:
            https://github.com/composablehorizons/composables-cli
    """.trimIndent()
}

class Update : CliktCommand("update") {

    override fun help(context: Context): String = """
        Updates the CLI tool with the latest version
    """.trimIndent()

    override fun run() {
        try {
            val process = ProcessBuilder("bash", "-c", "curl -fsSL https://composables.com/get-composables.sh | bash")
                .inheritIO()
                .start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                echo("Update failed with exit code: $exitCode", err = true)
            }
        } catch (e: Exception) {
            echo("Failed to run update: ${e.message}", err = true)
        }
    }
}

class Init : CliktCommand("init") {
    override fun help(context: Context): String = """
        Initializes a new Compose Multiplatform module to the specified <directory> path.
    """.trimIndent()

    private val directory by argument("directory", help = "The directory path to create the new module in").optional()

    override fun run() {
        val workingDir = System.getProperty("user.dir")
        val projectName = directory.orEmpty()
        if (projectName.isBlank()) {
            debugln { "Please specify the project directory:" }
            infoln { "composables init <project-directory>" }
            debugln { "" }
            debugln { "For example:" }
            infoln { "composables init composeApp" }
            return
        }
        val target = if (projectName == ".") File(workingDir) else File(workingDir).resolve(projectName)

        // Check if we can create the directory first
        if (target.exists()) {
            if (target.listFiles()?.isEmpty() == true) {
                target.deleteRecursively()
            } else {
                // Check if it's a Gradle project
                val isGradleProject = File(target, "build.gradle.kts").exists() ||
                        File(target, "build.gradle").exists() ||
                        File(target, "settings.gradle.kts").exists() ||
                        File(target, "settings.gradle").exists()

                if (isGradleProject) {
                    print("Gradle project detected. This will add a new module to your existing project. Is this what you want? y/n ")
                    val response = readln().trim().lowercase()
                    if (response != "y" && response != "yes") {
                        echo("Operation cancelled.")
                        return
                    }
                    val moduleName = readUniqueModuleName(target)
                    val appName = readAppName()
                    val namespace = readNamespace()
                    val targets = readTargets()

                    // Create only the module directory and files
                    createModuleOnly(
                        targetDir = target.absolutePath,
                        moduleName = moduleName,
                        packageName = namespace,
                        appName = appName,
                        targets = targets
                    )

                    // Add module to settings.gradle.kts
                    addModuleToSettings(target.absolutePath, moduleName)

                    // Update version catalog if needed
                    updateVersionCatalog(target.absolutePath, targets)

                    // Update root build.gradle.kts with required plugins
                    updateRootBuildFile(target.absolutePath, targets)

                    // Create iOS app directory if iOS target is selected
                    if (targets.contains("ios")) {
                        createIosAppDirectory(target.absolutePath, moduleName)
                    }
                    return
                } else {
                    val dirName = if (projectName == ".") "The current directory" else "The directory $projectName"
                    echo("$dirName is not empty and does not contain a Gradle project.")
                    echo("Try a new directory path or delete the existing one before trying to initialize a new module.")
                    return
                }
            }
        }

        if (!target.mkdirs()) {
            echo("Failed to create directory $projectName")
            return
        }

        val moduleName = readModuleName(projectName)
        val appName = readAppName()
        val namespace = readNamespace()
        val targets = readTargets()

        cloneGradleProjectAndPrint(
            targetDir = workingDir,
            dirName = projectName,
            packageName = namespace,
            appName = appName,
            targets = targets,
            moduleName = moduleName
        )
    }

    private fun readNamespace(): String {
        while (true) {
            print("Enter package name (default: com.example.app): ")
            val namespace = readln().trim()

            if (namespace.isEmpty()) {
                return "com.example.app"
            }

            if (!isValidPackageName(namespace)) {
                echo("Invalid package name. Must be a valid Java package name (e.g., com.example.app)")
                continue
            }

            return namespace
        }
    }

    private fun readAppName(): String {
        while (true) {
            print("Enter app name (default: My App): ")
            val appName = readln().trim()

            if (appName.isEmpty()) {
                return "My App"
            }

            if (!isValidAppName(appName)) {
                echo("Invalid app name. Must start with a letter and contain only letters, digits, or underscores")
                continue
            }

            return appName
        }
    }

    private fun readTargets(): Set<String> {
        while (true) {
            val targets = mutableSetOf<String>()

            echo("\nWhich platforms would you like your app to run on?")

            while (true) {
                print("Android (y/n, default: y): ")
                val android = readln().trim().lowercase()
                if (android.isEmpty() || android == "y" || android == "yes") {
                    targets.add(ANDROID)
                }
                break
            }

            while (true) {
                print("JVM (Desktop) (y/n, default: y): ")
                val jvm = readln().trim().lowercase()
                if (jvm.isEmpty() || jvm == "y" || jvm == "yes") {
                    targets.add(JVM)
                }
                break
            }

            while (true) {
                print("iOS (y/n, default: y): ")
                val ios = readln().trim().lowercase()
                if (ios.isEmpty() || ios == "y" || ios == "yes") {
                    targets.add(IOS)
                }
                break
            }

            while (true) {
                print("Web (y/n, default: y): ")
                val web = readln().trim().lowercase()
                if (web.isEmpty() || web == "y" || web == "yes") {
                    targets.add(WEB)
                }
                break
            }

            if (targets.isNotEmpty()) {
                return targets
            } else {
                echo("At least one platform is required...")
            }
        }
    }

    private fun isValidAppName(appName: String): Boolean {
        if (appName.isEmpty()) return false

        // Check if it contains at least one letter or digit
        return appName.any { char -> char.isLetterOrDigit() }
    }

    private fun readModuleName(projectName: String): String {
        while (true) {
            print("Enter module name (default: composeApp): ")
            val moduleName = readln().trim()

            if (moduleName.isEmpty()) {
                return "composeApp"
            }

            if (moduleName == projectName) {
                echo("Module name cannot be the same as the project name \"$projectName\". Try specifying a different name.")
                continue
            }

            if (!isValidModuleName(moduleName)) {
                echo("Invalid module name. Must start with a letter and contain only letters, digits, hyphens, or underscores")
                continue
            }

            return moduleName
        }
    }

    private fun readUniqueModuleName(targetDir: File): String {
        while (true) {
            print("Enter module name (default: composeApp): ")
            val moduleName = readln().trim()

            if (moduleName.isEmpty()) {
                if (File(targetDir, "composeApp").exists()) {
                    echo("Module name 'composeApp' already exists. Please choose a different name.")
                    continue
                }
                return "composeApp"
            }

            if (!isValidModuleName(moduleName)) {
                echo("Invalid module name. Must start with a letter and contain only letters, digits, hyphens, or underscores")
                continue
            }

            val moduleDir = File(targetDir, moduleName)
            if (moduleDir.exists()) {
                echo("Module '$moduleName' already exists. Please choose a different name.")
                continue
            }

            return moduleName
        }
    }

    private fun isValidModuleName(moduleName: String): Boolean {
        if (moduleName.isEmpty()) return false

        // Check if it contains at least one letter or digit
        return moduleName.any { char -> char.isLetterOrDigit() } &&
                moduleName.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' }
    }

    private fun isValidPackageName(packageName: String): Boolean {
        if (packageName.isEmpty()) return false

        val parts = packageName.split(".")
        if (parts.size < 2) return false

        // Check each part is a valid Java identifier
        return parts.all { part ->
            part.isNotEmpty() &&
                    part[0].isLetter() &&
                    part.all { char -> char.isLetterOrDigit() || char == '_' }
        }
    }
}

private fun toCamelCase(input: String): String {
    return input.split(Regex("[-_]"))
        .mapIndexed { index, part ->
            if (index == 0) {
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            } else {
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
        .joinToString("")
}

class Target : CliktCommand("target") {
    override fun help(context: Context): String = """
        Adds a new Kotlin target to the current Compose Multiplatform project (options: android, jvm, ios, web).
    """.trimIndent()

    private val targetName by argument(name = "target")

    override fun run() {
        val validTargets = setOf("android", "jvm", "ios", "web")

        if (targetName !in validTargets) {
            echo("Unknown target '$targetName'")
            echo("Available targets: android, jvm, ios, web")
            echo("Usage: composables target <target-name>")
            return
        }

        val workingDir = System.getProperty("user.dir")

        if (!isValidComposeAppDirectory(workingDir)) {
            echo("This doesn't appear to be a Compose Multiplatform project.")
            echo("To create a new Compose app, run:")
            echo("    composables init composeApp")
            return
        }

        val composeModuleBuildFile = findComposeModuleBuildFile(workingDir)
        if (composeModuleBuildFile == null) {
            echo("Could not find a Compose Multiplatform module in this project.")
            return
        }

        when (targetName) {
            "android" -> {
                if (hasAndroidTarget(composeModuleBuildFile)) {
                    echo("Android target is already configured in this project.")
                    return
                }
                try {
                    addAndroidTarget(workingDir, composeModuleBuildFile)
                    echo("Android target added successfully!")
                    echo("Run '$gradleScript build' to verify the configuration.")
                } catch (e: Exception) {
                    echo("Failed to add Android target: ${e.message}", err = true)
                }
            }

            "jvm" -> {
                if (hasJvmTarget(composeModuleBuildFile)) {
                    echo("JVM target is already configured in this project.")
                    return
                }
                try {
                    addJvmTarget(workingDir, composeModuleBuildFile)
                    echo("JVM target added successfully!")
                    echo("Run '$gradleScript build' to verify the configuration.")
                } catch (e: Exception) {
                    echo("Failed to add JVM target: ${e.message}", err = true)
                }
            }

            "ios" -> {
                if (hasIosTarget(composeModuleBuildFile)) {
                    echo("iOS target is already configured in this project.")
                    return
                }
                try {
                    addIosTarget(workingDir, composeModuleBuildFile)
                    echo("iOS target added successfully!")
                    echo("Run '$gradleScript build' to verify the configuration.")
                } catch (e: Exception) {
                    echo("Failed to add iOS target: ${e.message}", err = true)
                }
            }

            "web" -> {
                if (hasWebTarget(composeModuleBuildFile)) {
                    echo("Web target is already configured in this project.")
                    return
                }
                try {
                    addWebTarget(workingDir, composeModuleBuildFile)
                    echo("Web target added successfully!")
                    echo("Run '$gradleScript build' to verify the configuration.")
                } catch (e: Exception) {
                    echo("Failed to add web target: ${e.message}", err = true)
                }
            }
        }
    }

    private fun isValidComposeAppDirectory(directory: String): Boolean {
        val dir = File(directory)

        // Check for root build.gradle.kts
        val rootBuildFile = File(dir, "build.gradle.kts")
        if (!rootBuildFile.exists()) {
            return false
        }

        // Look for any subdirectory with build.gradle.kts that has Compose dependencies
        val subDirs = dir.listFiles()?.filter { subDir ->
            subDir.isDirectory && File(subDir, "build.gradle.kts").exists()
        } ?: return false

        return subDirs.any { subDir ->
            val buildFile = File(subDir, "build.gradle.kts")
            val content = buildFile.readText()
            hasComposeDependencies(content)
        }
    }

    private fun hasComposeDependencies(content: String): Boolean {
        val composeDependencies = listOf(
            "compose.components.resources",
            "compose.components.uiToolingPreview",
            "compose.material3",
            "compose.desktop.currentOs",
            "compose.preview",
            "compose.runtime"
        )

        return composeDependencies.any { dependency ->
            content.contains(dependency)
        }
    }

    private fun findComposeModuleBuildFile(workingDir: String): File? {
        val dir = File(workingDir)

        val composeModules = dir.listFiles()?.filter { subDir ->
            subDir.isDirectory && File(subDir, "build.gradle.kts").exists()
        }?.filter { subDir ->
            val buildFile = File(subDir, "build.gradle.kts")
            val content = buildFile.readText()
            hasComposeDependencies(content)
        } ?: return null

        when {
            composeModules.isEmpty() -> return null
            composeModules.size == 1 -> return File(composeModules.first(), "build.gradle.kts")
            else -> {
                return selectComposeModule(composeModules)
            }
        }
    }

    private fun selectComposeModule(composeModules: List<File>): File? {
        val sortedModules = composeModules.sortedBy { it.name }
        echo("Multiple Compose modules detected:")
        sortedModules.forEachIndexed { index, module ->
            echo("  ${index + 1}. ${module.name}")
        }

        while (true) {
            print("Select a module (1-${sortedModules.size}): ")
            val input = readln().trim()

            val selection = input.toIntOrNull()
            if (selection != null && selection in 1..sortedModules.size) {
                val selectedModule = sortedModules[selection - 1]
                echo("Selected module: ${selectedModule.name}")
                return File(selectedModule, "build.gradle.kts")
            } else {
                echo("Invalid selection. Please enter a number between 1 and ${sortedModules.size}")
            }
        }
    }

    private fun hasAndroidTarget(buildFile: File): Boolean {
        val content = buildFile.readText()
        return content.contains("androidTarget {") || content.contains("android {")
    }

    private fun hasJvmTarget(buildFile: File): Boolean {
        val content = buildFile.readText()
        return content.contains("jvm()")
    }

    private fun hasIosTarget(buildFile: File): Boolean {
        val content = buildFile.readText()
        return content.contains("iosArm64()") || content.contains("iosSimulatorArm64()")
    }

    private fun hasWebTarget(buildFile: File): Boolean {
        val content = buildFile.readText()
        return content.contains("js(") || content.contains("wasmJs(")
    }

    private fun addAndroidTarget(workingDir: String, buildFile: File) {
        var content = buildFile.readText()
        val lines = content.lines().toMutableList()

        // Add import if needed
        if (!content.contains("import org.jetbrains.kotlin.gradle.dsl.JvmTarget")) {
            val importLine = "import org.jetbrains.kotlin.gradle.dsl.JvmTarget"
            // Find the last import line and add after it
            val lastImportIndex = lines.indexOfLast { it.startsWith("import ") }
            if (lastImportIndex >= 0) {
                lines.add(lastImportIndex + 1, importLine)
            } else {
                // Add before the first non-empty, non-comment line
                val firstCodeLine = lines.indexOfFirst { !it.trim().isEmpty() && !it.trim().startsWith("//") }
                if (firstCodeLine >= 0) {
                    lines.add(firstCodeLine, importLine)
                }
            }
        }

        // Append to plugins block
        val pluginsCloseIndex = findPluginsBlockEnd(lines)
        if (pluginsCloseIndex >= 0) {
            lines.add(pluginsCloseIndex, "    alias(libs.plugins.android.application)")
        }

        // Append to kotlin block
        val kotlinCloseIndex = findKotlinBlockEnd(lines)
        if (kotlinCloseIndex >= 0) {
            val androidTargetLines = listOf(
                "",
                "    androidTarget {",
                "        compilerOptions {",
                "            jvmTarget.set(JvmTarget.JVM_11)",
                "        }",
                "    }"
            )
            androidTargetLines.reversed().forEach { line ->
                lines.add(kotlinCloseIndex, line)
            }
        }

        // Append to sourceSets block
        val sourceSetsCloseIndex = findSourceSetsBlockEnd(lines)
        if (sourceSetsCloseIndex >= 0) {
            val androidMainLines = listOf(
                "",
                "        androidMain.dependencies {",
                "            implementation(compose.preview)",
                "            implementation(compose.material3)",
                "            implementation(libs.androidx.activitycompose)",
                "        }"
            )
            androidMainLines.reversed().forEach { line ->
                lines.add(sourceSetsCloseIndex, line)
            }
        }

        // Add android block at the end
        val namespace = "com.example.app" // Default namespace for target command
        val androidBlock = listOf(
            "",
            "android {",
            "    namespace = \"$namespace\"",
            "    compileSdk = 36",
            "",
            "    defaultConfig {",
            "        applicationId = \"$namespace\"",
            "        minSdk = 24",
            "        targetSdk = 36",
            "        versionCode = 1",
            "        versionName = \"1.0\"",
            "    }",
            "    packaging {",
            "        resources {",
            "            excludes += \"/META-INF/{AL2.0,LGPL2.1}\"",
            "        }",
            "    }",
            "    buildTypes {",
            "        getByName(\"release\") {",
            "            isMinifyEnabled = false",
            "        }",
            "    }",
            "    compileOptions {",
            "        sourceCompatibility = JavaVersion.VERSION_11",
            "        targetCompatibility = JavaVersion.VERSION_11",
            "    }",
            "}"
        )
        lines.addAll(androidBlock)

        // Write updated content
        buildFile.writeText(lines.joinToString("\n"))

        // Create androidMain source set and MainActivity
        val moduleDir = buildFile.parentFile
        createAndroidSourceSet(moduleDir, namespace)

        // Copy Android resources
        copyAndroidResources(moduleDir, namespace)

        // Update root build.gradle.kts
        updateRootBuildFile(workingDir)

        // Update gradle.properties
        updateGradleProperties(workingDir)

        // Update libs.versions.toml
        updateVersionsFile(workingDir)
    }

    private fun findPluginsBlockEnd(lines: List<String>): Int {
        var depth = 0
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("plugins {")) {
                depth = 1
                for (j in i + 1 until lines.size) {
                    val currentLine = lines[j].trim()
                    if (currentLine.contains("{")) depth++
                    if (currentLine.contains("}")) depth--
                    if (depth == 0) return j
                }
            }
        }
        return -1
    }

    private fun findKotlinBlockEnd(lines: List<String>): Int {
        var depth = 0
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("kotlin {")) {
                depth = 1
                for (j in i + 1 until lines.size) {
                    val currentLine = lines[j].trim()
                    if (currentLine.contains("{")) depth++
                    if (currentLine.contains("}")) depth--
                    if (depth == 0) return j
                }
            }
        }
        return -1
    }

    private fun findSourceSetsBlockEnd(lines: List<String>): Int {
        var depth = 0
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.contains("sourceSets") && line.contains("{")) {
                depth = 1
                for (j in i + 1 until lines.size) {
                    val currentLine = lines[j].trim()
                    if (currentLine.contains("{")) depth++
                    if (currentLine.contains("}")) depth--
                    if (depth == 0) return j
                }
            }
        }
        return -1
    }

    private fun extractNamespace(lines: List<String>): String {
        // Try to find existing namespace from android block or use a default
        for (line in lines) {
            if (line.trim().startsWith("namespace =")) {
                return line.trim().substringAfter("namespace =").trim().removeSurrounding("\"")
            }
        }
        return "com.example.app" // fallback
    }

    private fun updateRootBuildFile(workingDir: String) {
        val rootBuildFile = File(workingDir, "build.gradle.kts")
        if (!rootBuildFile.exists()) return

        var content = rootBuildFile.readText()
        if (!content.contains("android-application")) {
            // Find the plugins block and add android plugin
            val lines = content.lines().toMutableList()
            val pluginsCloseIndex = findPluginsBlockEnd(lines)
            if (pluginsCloseIndex >= 0) {
                lines.add(pluginsCloseIndex, "    alias(libs.plugins.android.application) apply false")
                rootBuildFile.writeText(lines.joinToString("\n"))
            }
        }
    }

    private fun updateVersionsFile(workingDir: String) {
        val versionsFile = File(workingDir, "gradle/libs.versions.toml")
        if (!versionsFile.exists()) return

        var content = versionsFile.readText()

        // Add Android versions if not present
        if (!content.contains("agp =")) {
            content = content.replace(
                "[versions]",
                """[versions]
# Android
agp = "8.11.2"
android-compileSdk = "36"
android-minSdk = "24"
android-targetSdk = "36"
androidx-activity = "1.11.0"
"""
            )
        }

        // Add Android libraries if not present
        if (!content.contains("androidx-activitycompose")) {
            content = content.replace(
                "[libraries]",
                """[libraries]
androidx-activitycompose = { module = "androidx.activity:activity-compose", version.ref = "androidx-activity" }
"""
            )
        }

        // Add Android plugins if not present
        if (!content.contains("android-application")) {
            content = content.replace(
                "[plugins]",
                """[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
"""
            )
        }

        versionsFile.writeText(content)
    }

    private fun updateGradleProperties(workingDir: String) {
        val gradlePropertiesFile = File(workingDir, "gradle.properties")
        if (!gradlePropertiesFile.exists()) return

        var content = gradlePropertiesFile.readText()

        // Add Android properties if not present
        if (!content.contains("android.useAndroidX")) {
            content += "\n\n#Android\nandroid.useAndroidX=true\nandroid.nonTransitiveRClass=true\n"
        }

        gradlePropertiesFile.writeText(content)
    }

    private fun createAndroidSourceSet(moduleDir: File, namespace: String) {
        val androidMainDir = File(moduleDir, "src/androidMain/kotlin")
        val packageDir = File(androidMainDir, namespace.replace(".", "/"))

        // Create directories
        packageDir.mkdirs()

        // Create MainActivity.kt
        val mainActivityFile = File(packageDir, "MainActivity.kt")
        val mainActivityContent = """package $namespace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidApp()
        }
    }
}

@Composable
fun AndroidApp() {
    MaterialTheme {
        Scaffold {
            Box(
                modifier = Modifier
                    .safeDrawingPadding()
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                ) {
                    Text(
                        text = "Hello Beautiful World!",
                        style = MaterialTheme.typography.displayLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Go to MainActivity.kt to edit your app",
                        style = MaterialTheme.typography.displayMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun DefaultPreview() {
    AndroidApp()
}
"""
        mainActivityFile.writeText(mainActivityContent)
    }

    private fun copyAndroidResources(moduleDir: File, namespace: String) {
        fun copyResource(resourcePath: String, targetFile: File) {
            val inputStream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
            if (inputStream != null) {
                targetFile.parentFile?.mkdirs()
                inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        fun listResources(path: String): List<String> {
            val resources = mutableListOf<String>()
            val resourceUrl = object {}.javaClass.getResource(path)

            if (resourceUrl != null) {
                when (resourceUrl.protocol) {
                    "file" -> {
                        val dir = File(resourceUrl.toURI())
                        dir.walkTopDown().forEach { file ->
                            if (file.isFile) {
                                val relativePath = file.relativeTo(dir)
                                resources.add("$path/${relativePath.path}")
                            }
                        }
                    }

                    "jar" -> {
                        val jarPath = resourceUrl.path.substringBefore("!")
                        val jarFile = JarFile(File(jarPath.substringAfter("file:")))
                        val entries = jarFile.entries()

                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.startsWith(path.substring(1)) && !entry.isDirectory) {
                                resources.add("/${entry.name}")
                            }
                        }
                        jarFile.close()
                    }
                }
            }

            return resources
        }

        val resources = listResources("/project/composeApp/src/androidMain")
        resources.forEach { resourcePath ->
            val targetPath = resourcePath.removePrefix("/project/composeApp/src/androidMain/")

            // Skip MainActivity.kt template since we create it programmatically
            if (targetPath.endsWith("MainActivity.kt")) {
                return@forEach
            }

            val targetFile = File(moduleDir, "src/androidMain/$targetPath")
            copyResource(resourcePath, targetFile)

            // Replace placeholders in text files
            if (targetFile.name.endsWith(".kt")) {
                try {
                    val content = targetFile.readText()
                    var updatedContent = content.replace("{{namespace}}", namespace)
                    updatedContent = updatedContent.replace("{{app_name}}", "My App")
                    if (content != updatedContent) {
                        targetFile.writeText(updatedContent)
                    }
                } catch (e: Exception) {
                    // Skip binary files
                }
            }

            // Replace placeholders in strings.xml
            if (targetFile.name == "strings.xml") {
                try {
                    val content = targetFile.readText()
                    var updatedContent = content.replace("{{app_name}}", "My App")
                    if (content != updatedContent) {
                        targetFile.writeText(updatedContent)
                    }
                } catch (e: Exception) {
                    // Skip binary files
                }
            }
        }
    }

    private fun addJvmTarget(workingDir: String, buildFile: File) {
        var content = buildFile.readText()
        val lines = content.lines().toMutableList()

        // Add import if needed
        if (!content.contains("import org.jetbrains.compose.desktop.application.dsl.TargetFormat")) {
            val importLine = "import org.jetbrains.compose.desktop.application.dsl.TargetFormat"
            // Find the last import line and add after it
            val lastImportIndex = lines.indexOfLast { it.startsWith("import ") }
            if (lastImportIndex >= 0) {
                lines.add(lastImportIndex + 1, importLine)
            } else {
                // Add before the first non-empty, non-comment line
                val firstCodeLine = lines.indexOfFirst { !it.trim().isEmpty() && !it.trim().startsWith("//") }
                if (firstCodeLine >= 0) {
                    lines.add(firstCodeLine, importLine)
                }
            }
        }

        // Append to kotlin block
        val kotlinCloseIndex = findKotlinBlockEnd(lines)
        if (kotlinCloseIndex >= 0) {
            lines.add(kotlinCloseIndex, "    jvm()")
        }

        // Append to sourceSets block
        val sourceSetsCloseIndex = findSourceSetsBlockEnd(lines)
        if (sourceSetsCloseIndex >= 0) {
            val jvmMainLines = listOf(
                "",
                "        jvmMain.dependencies {",
                "            implementation(compose.desktop.currentOs)",
                "            implementation(compose.material3)",
                "        }"
            )
            jvmMainLines.reversed().forEach { line ->
                lines.add(sourceSetsCloseIndex, line)
            }
        }

        // Add compose.desktop block at the end
        val namespace = extractNamespace(lines)
        val desktopBlock = listOf(
            "",
            "compose.desktop {",
            "    application {",
            "        mainClass = \"${namespace}.MainKt\"",
            "",
            "        nativeDistributions {",
            "            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)",
            "            packageName = \"${namespace}\"",
            "            packageVersion = \"1.0.0\"",
            "        }",
            "    }",
            "}"
        )
        lines.addAll(desktopBlock)

        // Write updated content
        buildFile.writeText(lines.joinToString("\n"))

        // Create jvmMain source set and main function
        val moduleDir = buildFile.parentFile
        createJvmSourceSet(moduleDir, namespace)
    }

    private fun createJvmSourceSet(moduleDir: File, namespace: String) {
        val jvmMainDir = File(moduleDir, "src/jvmMain/kotlin")
        val packageDir = File(jvmMainDir, namespace.replace(".", "/"))

        // Create directories
        packageDir.mkdirs()

        // Create main.desktop.kt
        val mainFile = File(packageDir, "main.desktop.kt")
        val mainContent = """@file:JvmName("MainKt")
package $namespace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.singleWindowApplication
import org.jetbrains.compose.ui.tooling.preview.Preview

fun main() = singleWindowApplication {
    DesktopApp()
}

@Composable
fun DesktopApp() {
    MaterialTheme {
        Scaffold {
            Box(
                modifier = Modifier
                    .safeDrawingPadding()
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                ) {
                    Text(
                        text = "Hello Beautiful World!",
                        style = MaterialTheme.typography.displayLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Go to main.desktop.kt to edit your app",
                        style = MaterialTheme.typography.displayMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun DesktopAppPreview() {
    DesktopApp()
}
"""
        mainFile.writeText(mainContent)
    }

    private fun addIosTarget(workingDir: String, buildFile: File) {
        var content = buildFile.readText()
        val lines = content.lines().toMutableList()

        // Append to kotlin block
        val kotlinCloseIndex = findKotlinBlockEnd(lines)
        if (kotlinCloseIndex >= 0) {
            val moduleName = buildFile.parentFile.name
            val baseName = toCamelCase(moduleName)
            val iosTargetLines = listOf(
                "",
                "    listOf(",
                "        iosArm64(),",
                "        iosSimulatorArm64()",
                "    ).forEach { iosTarget ->",
                "        iosTarget.binaries.framework {",
                "            baseName = \"$baseName\"",
                "            isStatic = true",
                "        }",
                "    }"
            )
            iosTargetLines.reversed().forEach { line ->
                lines.add(kotlinCloseIndex, line)
            }
        }

        // Append to sourceSets block
        val sourceSetsCloseIndex = findSourceSetsBlockEnd(lines)
        if (sourceSetsCloseIndex >= 0) {
            val iosMainLines = listOf(
                "",
                "        iosMain.dependencies {",
                "            implementation(compose.material3)",
                "        }"
            )
            iosMainLines.reversed().forEach { line ->
                lines.add(sourceSetsCloseIndex, line)
            }
        }

        // Write updated content
        buildFile.writeText(lines.joinToString("\n"))

        // Create iosMain source set
        val moduleDir = buildFile.parentFile
        createIosSourceSet(moduleDir, extractNamespace(lines))

        // Copy iOS app directory
        copyIosAppDirectory(workingDir, moduleDir.name) // iOS app is still at root level

        // Link iOS project for IDE
        try {
            debugln { "Preparing iOS target..." }
            val process = ProcessBuilder(gradleScript, "compileIosMainKotlinMetadata", "--quiet")
                .directory(File(workingDir))
                .inheritIO()
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                echo("iOS target is now ready to run from the IDE")
            } else {
                echo("Warning: Failed to link iOS project for IDE. You may need to run '$gradleScript compileIosMainKotlinMetadata' manually.")
            }
        } catch (e: Exception) {
            echo("Warning: Failed to link iOS project for IDE: ${e.message}")
        }
    }

    private fun createIosSourceSet(moduleDir: File, namespace: String) {
        val iosMainDir = File(moduleDir, "src/iosMain/kotlin")
        val packageDir = iosMainDir

        // Create directories
        packageDir.mkdirs()

        // Create IosApp.kt
        val mainFile = File(packageDir, "MainViewController.kt")
        val mainContent = """import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController { IosApp() }

@Composable
fun IosApp() {
    MaterialTheme {
        Scaffold {
            Box(
                modifier = Modifier
                    .safeDrawingPadding()
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                ) {
                    Text(
                        text = "Hello Beautiful World!",
                        style = MaterialTheme.typography.displayLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Go to MainViewController.kt to edit your app",
                        style = MaterialTheme.typography.displayMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun IosAppPreview() {
    IosApp()
}
"""
        mainFile.writeText(mainContent)
    }

    private fun addWebTarget(workingDir: String, buildFile: File) {
        var content = buildFile.readText()
        val lines = content.lines().toMutableList()

        // Add imports if needed
        if (!content.contains("import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl")) {
            val importLine = "import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl"
            val lastImportIndex = lines.indexOfLast { it.startsWith("import ") }
            if (lastImportIndex >= 0) {
                lines.add(lastImportIndex + 1, importLine)
            } else {
                val firstCodeLine = lines.indexOfFirst { !it.trim().isEmpty() && !it.trim().startsWith("//") }
                if (firstCodeLine >= 0) {
                    lines.add(firstCodeLine, importLine)
                }
            }
        }

        if (!content.contains("import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig")) {
            val importLine = "import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig"
            val lastImportIndex = lines.indexOfLast { it.startsWith("import ") }
            if (lastImportIndex >= 0) {
                lines.add(lastImportIndex + 1, importLine)
            } else {
                val firstCodeLine = lines.indexOfFirst { !it.trim().isEmpty() && !it.trim().startsWith("//") }
                if (firstCodeLine >= 0) {
                    lines.add(firstCodeLine, importLine)
                }
            }
        }

        // Append to kotlin block
        val kotlinCloseIndex = findKotlinBlockEnd(lines)
        if (kotlinCloseIndex >= 0) {
            val webTargetLines = listOf(
                "",
                "    js {",
                "        browser {",
                "            val rootDirPath = project.rootDir.path",
                "            val projectDirPath = project.projectDir.path",
                "            commonWebpackConfig {",
                "                outputFileName = \"composeApp.js\"",
                "                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {",
                "                    static = (static ?: mutableListOf()).apply {",
                "                        add(rootDirPath)",
                "                        add(projectDirPath)",
                "                    }",
                "                }",
                "            }",
                "        }",
                "        binaries.executable()",
                "    }",
                "",
                "    @OptIn(ExperimentalWasmDsl::class)",
                "    wasmJs {",
                "        browser {",
                "            val rootDirPath = project.rootDir.path",
                "            val projectDirPath = project.projectDir.path",
                "            commonWebpackConfig {",
                "                outputFileName = \"composeApp.js\"",
                "                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {",
                "                    static = (static ?: mutableListOf()).apply {",
                "                        add(rootDirPath)",
                "                        add(projectDirPath)",
                "                    }",
                "                }",
                "            }",
                "        }",
                "        binaries.executable()",
                "    }"
            )
            webTargetLines.reversed().forEach { line ->
                lines.add(kotlinCloseIndex, line)
            }
        }

        // Append to sourceSets block
        val sourceSetsCloseIndex = findSourceSetsBlockEnd(lines)
        if (sourceSetsCloseIndex >= 0) {
            val webMainLines = listOf(
                "",
                "        jsMain.dependencies {",
                "            implementation(compose.material3)",
                "        }"
            )
            webMainLines.reversed().forEach { line ->
                lines.add(sourceSetsCloseIndex, line)
            }
        }

        // Write updated content
        buildFile.writeText(lines.joinToString("\n"))

        // Create web source sets
        val moduleDir = buildFile.parentFile
        createWebSourceSets(moduleDir, extractNamespace(lines))

        // Copy webpack.config.d directory
        copyWebpackConfigDirectory(moduleDir)

        // Copy resources directory
        copyResourcesDirectory(moduleDir)
    }

    private fun createWebSourceSets(moduleDir: File, namespace: String) {
        // Create webMain source set
        val webMainDir = File(moduleDir, "src/webMain/kotlin")
        val webPackageDir = webMainDir
        webPackageDir.mkdirs()

        val webMainFile = File(webPackageDir, "main.web.kt")
        val webMainContent = """import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import org.jetbrains.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport {
        WebApp()
    }
}

@Composable
fun WebApp() {
    MaterialTheme {
        Scaffold {
            Box(
                modifier = Modifier
                    .safeDrawingPadding()
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                ) {
                    Text(
                        text = "Hello Beautiful World!",
                        style = MaterialTheme.typography.displayLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Go to main.web.kt to edit your app",
                        style = MaterialTheme.typography.displayMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun WebAppPreview() {
    WebApp()
}
"""
        webMainFile.writeText(webMainContent)
    }

    private fun copyWebpackConfigDirectory(moduleDir: File) {
        val targetDir = File(moduleDir, "webpack.config.d")

        fun copyResource(resourcePath: String, targetFile: File) {
            val inputStream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
            if (inputStream != null) {
                targetFile.parentFile?.mkdirs()
                inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        fun listResources(path: String): List<String> {
            val resources = mutableListOf<String>()
            val resourceUrl = object {}.javaClass.getResource(path)

            if (resourceUrl != null) {
                when (resourceUrl.protocol) {
                    "file" -> {
                        val dir = File(resourceUrl.toURI())
                        dir.walkTopDown().forEach { file ->
                            if (file.isFile) {
                                val relativePath = file.relativeTo(dir)
                                resources.add("$path/${relativePath.path}")
                            }
                        }
                    }

                    "jar" -> {
                        val jarPath = resourceUrl.path.substringBefore("!")
                        val jarFile = JarFile(File(jarPath.substringAfter("file:")))
                        val entries = jarFile.entries()

                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.startsWith(path.substring(1)) && !entry.isDirectory) {
                                resources.add("/${entry.name}")
                            }
                        }
                        jarFile.close()
                    }
                }
            }

            return resources
        }

        val resources = listResources("/project/composeApp/webpack.config.d")
        resources.forEach { resourcePath ->
            val targetPath = resourcePath.removePrefix("/project/composeApp/webpack.config.d/")
            val targetFile = targetDir.resolve(targetPath)
            copyResource(resourcePath, targetFile)
        }
    }

    private fun copyResourcesDirectory(moduleDir: File) {
        val targetDir = File(moduleDir, "src/webMain/resources")

        fun copyResource(resourcePath: String, targetFile: File) {
            val inputStream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
            if (inputStream != null) {
                targetFile.parentFile?.mkdirs()
                inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        fun listResources(path: String): List<String> {
            val resources = mutableListOf<String>()
            val resourceUrl = object {}.javaClass.getResource(path)

            if (resourceUrl != null) {
                when (resourceUrl.protocol) {
                    "file" -> {
                        val dir = File(resourceUrl.toURI())
                        dir.walkTopDown().forEach { file ->
                            if (file.isFile) {
                                val relativePath = file.relativeTo(dir)
                                resources.add("$path/${relativePath.path}")
                            }
                        }
                    }

                    "jar" -> {
                        val jarPath = resourceUrl.path.substringBefore("!")
                        val jarFile = JarFile(File(jarPath.substringAfter("file:")))
                        val entries = jarFile.entries()

                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.startsWith(path.substring(1)) && !entry.isDirectory) {
                                resources.add("/${entry.name}")
                            }
                        }
                        jarFile.close()
                    }
                }
            }

            return resources
        }

        val resources = listResources("/project/composeApp/src/webMain/resources")
        resources.forEach { resourcePath ->
            val targetPath = resourcePath.removePrefix("/project/composeApp/src/webMain/resources/")
            val targetFile = targetDir.resolve(targetPath)
            copyResource(resourcePath, targetFile)

            // Replace placeholders in text files
            if (targetFile.name.endsWith(".html") || targetFile.name.endsWith(".css") || targetFile.name.endsWith(".js")) {
                try {
                    val content = targetFile.readText()
                    var updatedContent = content.replace("{{app_name}}", "ComposeApp")
                    if (content != updatedContent) {
                        targetFile.writeText(updatedContent)
                    }
                } catch (e: Exception) {
                    // Skip binary files
                }
            }
        }
    }

    private fun copyIosAppDirectory(workingDir: String, moduleName: String) {
        val iosAppName = "ios${toCamelCase(moduleName)}" // Dynamic iOS app name based on module
        val targetDir = File(workingDir, iosAppName) // iOS app directory name based on module

        fun copyResource(resourcePath: String, targetFile: File) {
            val inputStream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
            if (inputStream != null) {
                targetFile.parentFile?.mkdirs()
                inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        fun listResources(path: String): List<String> {
            val resources = mutableListOf<String>()
            val resourceUrl = object {}.javaClass.getResource(path)

            if (resourceUrl != null) {
                when (resourceUrl.protocol) {
                    "file" -> {
                        val dir = File(resourceUrl.toURI())
                        dir.walkTopDown().forEach { file ->
                            if (file.isFile) {
                                val relativePath = file.relativeTo(dir)
                                resources.add("$path/${relativePath.path}")
                            }
                        }
                    }

                    "jar" -> {
                        val jarPath = resourceUrl.path.substringBefore("!")
                        val jarFile = JarFile(File(jarPath.substringAfter("file:")))
                        val entries = jarFile.entries()

                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.name.startsWith(path.substring(1)) && !entry.isDirectory) {
                                resources.add("/${entry.name}")
                            }
                        }
                        jarFile.close()
                    }
                }
            }

            return resources
        }

        val resources = listResources("/project/iosApp")
        resources.forEach { resourcePath ->
            val targetPath = resourcePath.removePrefix("/project/iosApp/")
            val targetFile = targetDir.resolve(targetPath)
            copyResource(resourcePath, targetFile)

            // Replace placeholders in text files
            if (targetFile.name.endsWith(".swift") || targetFile.name.endsWith(".h") || targetFile.name.endsWith(".m") || targetFile.name.endsWith(
                    ".pbxproj"
                ) || targetFile.name.endsWith(".xcconfig")
            ) {
                try {
                    val content = targetFile.readText()
                    var updatedContent = content.replace("{{module_name}}", moduleName)
                    updatedContent = updatedContent.replace("{{ios_binary_name}}", toCamelCase(moduleName))
                    updatedContent = updatedContent.replace("{{target_name}}", "${toCamelCase(moduleName)}.app")
                    // For target command, use hardcoded defaults since appName/namespace aren't in scope
                    updatedContent = updatedContent.replace("{{app_name}}", "My App")
                    updatedContent = updatedContent.replace("{{namespace}}", "com.example.app")
                    if (content != updatedContent) {
                        targetFile.writeText(updatedContent)
                    }
                } catch (e: Exception) {
                    // Skip binary files
                }
            }
        }
    }
}

val gradleScript: String
    get() {
        return if (System.getProperty("os.name").lowercase().contains("win"))
            "gradlew.bat"
        else
            "./gradlew"
    }

fun cloneGradleProjectAndPrint(
    targetDir: String,
    dirName: String,
    packageName: String,
    appName: String,
    targets: Set<String>,
    moduleName: String
) {
    cloneGradleProject(
        targetDir,
        dirName,
        packageName,
        appName,
        targets,
        moduleName
    )
    // Log project configuration summary
    infoln { "" }
    infoln { "Project Configuration:" }
    infoln { "\tApp Name: $appName" }
    infoln { "\tPackage: $packageName" }
    infoln { "\tCompose Module: $moduleName" }
    infoln { "\tTargets: ${targets.joinToString(", ")}" }
    infoln { "" }

    debugln { "Success! Your new Compose app is ready at ${File(targetDir).absolutePath}" }
    debugln { "Start by typing:" }
    infoln { "" }
    infoln { "\tcd $dirName" }
    infoln { "\t$gradleScript run" }
    infoln { "" }
    debugln { "Happy coding!" }
}


fun cloneGradleProject(
    targetDir: String,
    dirName: String,
    packageName: String,
    appName: String,
    targets: Set<String>,
    moduleName: String
) {
    val target = File(targetDir).resolve(dirName)

    fun copyResource(resourcePath: String, targetFile: File) {
        val inputStream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
        if (inputStream != null) {
            targetFile.parentFile?.mkdirs()

            // Handle gradle-wrapper.jarX -> gradle-wrapper.jar rename
            val actualTargetFile = if (targetFile.name.endsWith(".jarX")) {
                File(targetFile.parent, targetFile.nameWithoutExtension + ".jar")
            } else {
                targetFile
            }

            inputStream.use { input ->
                actualTargetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Set executable permissions for scripts
            if (actualTargetFile.name == "gradlew") {
                actualTargetFile.setExecutable(true)
            }
        } else {
            error("Resource not found: $resourcePath")
            debugln { "Resource not found: $resourcePath" }
        }
    }

    fun listResources(path: String): List<String> {
        val resources = mutableListOf<String>()
        val resourceUrl = object {}.javaClass.getResource(path)

        if (resourceUrl != null) {
            when (resourceUrl.protocol) {
                "file" -> {
                    // Development mode - read from filesystem
                    val dir = File(resourceUrl.toURI())
                    dir.walkTopDown().forEach { file ->
                        if (file.isFile) {  // Only include files, not directories
                            val relativePath = file.relativeTo(dir)
                            resources.add("$path/${relativePath.path}")
                        }
                    }
                }

                "jar" -> {
                    // Production mode - read from JAR
                    val jarPath = resourceUrl.path.substringBefore("!")
                    val jarFile = JarFile(File(jarPath.substringAfter("file:")))
                    val entries = jarFile.entries()

                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.startsWith(path.substring(1)) && !entry.isDirectory) {
                            resources.add("/${entry.name}")
                        }
                    }
                    jarFile.close()
                }
            }
        }

        return resources
    }

    val resources = listResources("/project")
    resources.forEach { resourcePath ->
        var targetPath = resourcePath.removePrefix("/project/")

        // Skip iOS directory if iOS target is not selected
        val iosAppName = "ios${toCamelCase(moduleName)}"
        if (!targets.contains("ios") && targetPath.startsWith("iosApp/")) {
            return@forEach
        }

        // Skip source set directories if corresponding target is not selected
        val isInsideAKotlinSourceSet = targetPath.startsWith("composeApp/src/")
        if (isInsideAKotlinSourceSet) {
            val sourceSetType = targetPath.substringAfter("composeApp/src/").substringBefore("/")
            when (sourceSetType) {
                "androidMain" -> if (!targets.contains("android")) return@forEach
                "iosMain" -> if (!targets.contains("ios")) return@forEach
                "jvmMain" -> if (!targets.contains("jvm")) return@forEach
                "jsMain" -> if (!targets.contains("web")) return@forEach
                "wasmJsMain" -> if (!targets.contains("web")) return@forEach
                "webMain" -> if (!targets.contains("web")) return@forEach
                "commonMain" -> Unit
                else -> error("Unknown target: $targetPath")
            }
        }

        // Skip webpack.config.d directory if web target is not selected
        if (!targets.contains("web") && targetPath.startsWith("$moduleName/webpack.config.d/")) {
            return@forEach
        }

        // Replace org.example.project with the actual namespace in file paths
        targetPath = targetPath.replace("org/example/project", packageName.replace(".", "/"))

        // Replace composeApp with the actual module name in file paths
        targetPath = targetPath.replace("composeApp", moduleName)

        // Replace only the top-level iosApp directory with the dynamic iOS app name
        if (targetPath.startsWith("iosApp/")) {
            val newPath = iosAppName + "/" + targetPath.removePrefix("iosApp/")
//                .replace("iosApp/", iosAppName + "/")
            targetPath = newPath
        }

        val targetFile = target.resolve(targetPath)
        copyResource(resourcePath, targetFile)
    }

    // Replace placeholders in text files only (skip binary files)
    target.walkTopDown().forEach { file ->
        if (file.isFile) {
            // Skip binary files and known non-text files
            if (file.name.endsWith(".jar") ||
                file.name.endsWith(".png") ||
                file.name.endsWith(".jpg") ||
                file.name.endsWith(".jpeg") ||
                file.name.endsWith(".ico") ||
                file.name.endsWith(".icns") ||
                file.name.endsWith(".class")
            ) {
                return@forEach
            }

            try {
                val content = file.readText()
                var updatedContent = content.replace("{{app_name}}", appName)

                val androidVersions = if (targets.contains("android")) """# Android
agp = "8.11.2"
android-compileSdk = "36"
android-minSdk = "24"
android-targetSdk = "36"
androidx-activity = "1.11.0"

""" else ""

                val androidLibraries =
                    if (targets.contains("android")) """androidx-activitycompose = { module = "androidx.activity:activity-compose", version.ref = "androidx-activity" }

""" else ""

                val androidPlugins =
                    if (targets.contains("android")) """android-application = { id = "com.android.application", version.ref = "agp" }""" else ""

                val androidPlugin =
                    if (targets.contains("android")) """    alias(libs.plugins.android.application) apply false
""" else ""

                val androidProperties = if (targets.contains("android")) """#Android
android.nonTransitiveRClass=true
android.useAndroidX=true
""" else ""

                // Build imports block
                val imports = mutableListOf<String>()
                if (targets.contains("jvm")) {
                    imports.add("import org.jetbrains.compose.desktop.application.dsl.TargetFormat")
                }
                if (targets.contains("web")) {
                    imports.add("import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl")
                    imports.add("import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig")
                }
                if (targets.contains("android")) {
                    imports.add("import org.jetbrains.kotlin.gradle.dsl.JvmTarget")
                }
                val importsBlock = if (imports.isNotEmpty()) imports.joinToString("\n") + "\n" else ""

                // Build plugins block
                val plugins = mutableListOf<String>()
                plugins.add("    alias(libs.plugins.jetbrains.kotlin.multiplatform)")
                plugins.add("    alias(libs.plugins.jetbrains.compose)")
                plugins.add("    alias(libs.plugins.jetbrains.compose.compiler)")
                plugins.add("    alias(libs.plugins.jetbrains.compose.hotreload)")
                if (targets.contains("android")) {
                    plugins.add("    alias(libs.plugins.android.application)")
                }
                val pluginsBlock = "plugins {\n" + plugins.joinToString("\n") + "\n}"

                // Build kotlin targets block
                val kotlinTargets = mutableListOf<String>()
                if (targets.contains("android")) {
                    kotlinTargets.add(
                        """    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }"""
                    )
                }
                if (targets.contains("ios")) {
                    val baseName = toCamelCase(moduleName)
                    kotlinTargets.add(
                        """    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "$baseName"
            isStatic = true
        }
    }"""
                    )
                }
                if (targets.contains("jvm")) {
                    kotlinTargets.add("    jvm()")
                }
                if (targets.contains("web")) {
                    kotlinTargets.add(
                        """    js {
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }"""
                    )
                    kotlinTargets.add(
                        """    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }"""
                    )
                }
                val kotlinTargetsBlock =
                    if (kotlinTargets.isNotEmpty()) kotlinTargets.joinToString("\n\n") + "\n" else ""

                // Build sourcesets block
                val sourcesets = mutableListOf<String>()
                sourcesets.add(
                    """    sourceSets {
        commonMain.dependencies {
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.material3)
        }"""
                )

                if (targets.contains("jvm")) {
                    sourcesets.add(
                        """        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }"""
                    )
                }
                if (targets.contains("android")) {
                    sourcesets.add(
                        """        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activitycompose)
        }"""
                    )
                }
                sourcesets.add("    }")
                val sourcesetsBlock = sourcesets.joinToString("\n")

                // Build configuration blocks
                val configurations = mutableListOf<String>()
                if (targets.contains("android")) {
                    configurations.add(
                        """android {
    namespace = "{{namespace}}"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "{{namespace}}"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}"""
                    )
                }
                if (targets.contains("jvm")) {
                    configurations.add(
                        """compose.desktop {
    application {
        mainClass = "{{namespace}}.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "{{namespace}}"
            packageVersion = "1.0.0"
        }
    }
}"""
                    )
                }
                val configurationBlocksBlock =
                    if (configurations.isNotEmpty()) configurations.joinToString("\n\n") else ""

                val composeDesktop = if (targets.contains("jvm")) """compose.desktop {
    application {
        mainClass = "{{namespace}}.MainDesktopKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "{{namespace}}"
            packageVersion = "1.0.0"
        }
    }
}""" else ""

                val androidMainDependencies = if (targets.contains("android")) """        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activitycompose)
        }""" else ""

                val androidBlock = if (targets.contains("android")) """android {
    namespace = "{{namespace}}"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "{{namespace}}"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

""" else ""

                updatedContent = updatedContent.replace("{{android_versions}}", androidVersions)
                updatedContent = updatedContent.replace("{{android_libraries}}", androidLibraries)
                updatedContent = updatedContent.replace("{{android_plugins}}", androidPlugins)
                updatedContent = updatedContent.replace("{{android_plugin}}", androidPlugin)
                updatedContent = updatedContent.replace("{{android_properties}}", androidProperties)

                // Replace composeApp build.gradle.kts blocks
                updatedContent = updatedContent.replace("{{imports}}", importsBlock)
                updatedContent = updatedContent.replace("{{plugins}}", pluginsBlock)
                updatedContent = updatedContent.replace("{{kotlin_targets}}", kotlinTargetsBlock)
                updatedContent = updatedContent.replace("{{sourcesets}}", sourcesetsBlock)
                updatedContent = updatedContent.replace("{{configuration_blocks}}", configurationBlocksBlock)

                // Replace remaining placeholders after blocks are built
                updatedContent = updatedContent.replace("{{namespace}}", packageName)
                updatedContent = updatedContent.replace("{{module_name}}", moduleName)
                updatedContent = updatedContent.replace("{{app_name}}", appName)
                updatedContent = updatedContent.replace("{{ios_binary_name}}", toCamelCase(moduleName))
                updatedContent = updatedContent.replace("{{target_name}}", toCamelCase(moduleName) + ".app")
                if (content != updatedContent) {
                    file.writeText(updatedContent.trim() + "\n")
                }
            } catch (e: Exception) {
                // If we can't read as text, skip this file
                debugln { "Skipping binary file: ${file.name}" }
            }
        }
    }

    // Link iOS project for IDE if iOS target was included
    if (targets.contains("ios")) {
        try {
            debugln { "Preparing iOS target..." }
            val process = ProcessBuilder(gradleScript, "compileIosMainKotlinMetadata", "--quiet")
                .directory(target)
                .inheritIO()
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                debugln { "iOS target is now ready to run from the IDE" }
            } else {
                warnln { "Warning: Failed to link iOS project for IDE. You may need to run '$gradleScript compileIosMainKotlinMetadata' manually." }
            }
        } catch (e: Exception) {
            warnln { "Warning: Failed to link iOS project for IDE: ${e.message}" }
        }
    }

}

fun updateRootBuildFile(
    targetDir: String,
    targets: Set<String>
) {
    val buildFile = File(targetDir, "build.gradle.kts")
    if (!buildFile.exists()) {
        warnln { "build.gradle.kts not found in $targetDir" }
        return
    }

    var content = buildFile.readText()
    var modified = false

    // Find plugins block or create one
    val lines = content.lines().toMutableList()
    val pluginsBlockIndex = lines.indexOfFirst { it.trim().startsWith("plugins {") }

    if (pluginsBlockIndex >= 0) {
        // Find end of plugins block
        var pluginsEndIndex = pluginsBlockIndex + 1
        var depth = 1
        while (pluginsEndIndex < lines.size && depth > 0) {
            val line = lines[pluginsEndIndex].trim()
            if (line.contains("{")) depth++
            if (line.contains("}")) depth--
            pluginsEndIndex++
        }

        // Extract plugins content for checking
        val pluginsContent = lines.subList(pluginsBlockIndex, pluginsEndIndex).joinToString("\n")
        val requiredPlugins = mutableListOf<String>()

        // Check for exact plugin references, not partial matches
        if (!pluginsContent.contains("libs.plugins.jetbrains.kotlin.multiplatform")) {
            requiredPlugins.add("    alias(libs.plugins.jetbrains.kotlin.multiplatform) apply false")
        }
        if (!pluginsContent.contains("libs.plugins.jetbrains.compose")) {
            requiredPlugins.add("    alias(libs.plugins.jetbrains.compose) apply false")
        }
        if (!pluginsContent.contains("libs.plugins.jetbrains.compose.compiler")) {
            requiredPlugins.add("    alias(libs.plugins.jetbrains.compose.compiler) apply false")
        }
        if (!pluginsContent.contains("libs.plugins.jetbrains.compose.hotreload")) {
            requiredPlugins.add("    alias(libs.plugins.jetbrains.compose.hotreload) apply false")
        }
        if (targets.contains("android") && !pluginsContent.contains("libs.plugins.android.application")) {
            requiredPlugins.add("    alias(libs.plugins.android.application) apply false")
        }

        if (requiredPlugins.isNotEmpty()) {
            // Add missing plugins before closing brace
            requiredPlugins.reversed().forEach { plugin ->
                lines.add(pluginsEndIndex - 1, plugin)
            }
            modified = true
        }
    } else {
        // Create plugins block at the beginning
        val requiredPlugins = mutableListOf<String>()
        requiredPlugins.add("plugins {")
        requiredPlugins.add("    alias(libs.plugins.jetbrains.kotlin.multiplatform) apply false")
        requiredPlugins.add("    alias(libs.plugins.jetbrains.compose) apply false")
        requiredPlugins.add("    alias(libs.plugins.jetbrains.compose.compiler) apply false")
        requiredPlugins.add("    alias(libs.plugins.jetbrains.compose.hotreload) apply false")
        if (targets.contains("android")) {
            requiredPlugins.add("    alias(libs.plugins.android.application) apply false")
        }
        requiredPlugins.add("}")

        // Add at the beginning of file
        requiredPlugins.reversed().forEach { line ->
            lines.add(0, line)
        }
        modified = true
    }

    if (modified) {
        buildFile.writeText(lines.joinToString("\n"))
    }
}

fun updateVersionCatalog(
    targetDir: String,
    targets: Set<String>
) {
    val versionsFile = File(targetDir, "gradle/libs.versions.toml")
    if (!versionsFile.exists()) {
        warnln { "libs.versions.toml not found in $targetDir/gradle/" }
        return
    }

    var content = versionsFile.readText()
    var modified = false

    // Parse existing sections
    val versionsSection = extractSection(content, "versions")
    val librariesSection = extractSection(content, "libraries")
    val pluginsSection = extractSection(content, "plugins")

    // Add required versions if not present
    val newVersions = mutableListOf<String>()
    if (!versionsSection.contains("kotlin")) {
        newVersions.add("kotlin = \"2.1.0\"")
    }
    if (!versionsSection.contains("compose")) {
        newVersions.add("compose = \"2024.12.01\"")
    }

    // Add Android versions if android target is selected
    if (targets.contains("android")) {
        if (!versionsSection.contains("agp")) newVersions.add("agp = \"8.11.2\"")
        if (!versionsSection.contains("android-compileSdk")) newVersions.add("android-compileSdk = \"36\"")
        if (!versionsSection.contains("android-minSdk")) newVersions.add("android-minSdk = \"24\"")
        if (!versionsSection.contains("android-targetSdk")) newVersions.add("android-targetSdk = \"36\"")
        if (!versionsSection.contains("androidx-activity")) newVersions.add("androidx-activity = \"1.11.0\"")
    }

    // Add required libraries if not present
    val newLibraries = mutableListOf<String>()
    if (targets.contains("android") && !librariesSection.contains("androidx-activitycompose")) {
        newLibraries.add("androidx-activitycompose = { module = \"androidx.activity:activity-compose\", version.ref = \"androidx-activity\" }")
    }

    // Add required plugins if not present
    val newPlugins = mutableListOf<String>()
    if (!pluginsSection.contains("jetbrains-kotlin-multiplatform")) {
        newPlugins.add("jetbrains-kotlin-multiplatform = { id = \"org.jetbrains.kotlin.multiplatform\", version.ref = \"kotlin\" }")
    }
    if (!pluginsSection.contains("jetbrains-compose")) {
        newPlugins.add("jetbrains-compose = { id = \"org.jetbrains.compose\", version.ref = \"compose\" }")
    }
    if (!pluginsSection.contains("jetbrains-compose-compiler")) {
        newPlugins.add("jetbrains-compose-compiler = { id = \"org.jetbrains.kotlin.plugin.compose\", version.ref = \"kotlin\" }")
    }
    if (!pluginsSection.contains("jetbrains-compose-hotreload")) {
        newPlugins.add("jetbrains-compose-hotreload = { id = \"org.jetbrains.compose.hot-reload\", version.ref = \"compose\" }")
    }
    if (targets.contains("android") && !pluginsSection.contains("android-application")) {
        newPlugins.add("android-application = { id = \"com.android.application\", version.ref = \"agp\" }")
    }

    // Build updated content
    if (newVersions.isNotEmpty() || newLibraries.isNotEmpty() || newPlugins.isNotEmpty()) {
        modified = true

        // Update versions section
        if (newVersions.isNotEmpty()) {
            content = updateSection(content, "versions", newVersions)
        }

        // Update libraries section
        if (newLibraries.isNotEmpty()) {
            content = updateSection(content, "libraries", newLibraries)
        }

        // Update plugins section
        if (newPlugins.isNotEmpty()) {
            content = updateSection(content, "plugins", newPlugins)
        }
    }

    if (modified) {
        versionsFile.writeText(content)
    }
}

private fun extractSection(content: String, sectionName: String): String {
    val startPattern = Regex("""\[$sectionName\]""")
    val startMatch = startPattern.find(content)
    if (startMatch == null) return ""

    val startIndex = startMatch.range.last + 1
    val nextSectionPattern = Regex("""\[[^\]]+\]""")
    val nextMatch = nextSectionPattern.find(content, startIndex)

    val endIndex = if (nextMatch != null) nextMatch.range.first else content.length
    return content.substring(startIndex, endIndex)
}

private fun updateSection(content: String, sectionName: String, newEntries: List<String>): String {
    val lines = content.lines().toMutableList()
    val sectionIndex = lines.indexOfFirst { it.trim() == "[$sectionName]" }

    if (sectionIndex >= 0) {
        // Add new entries after section header
        newEntries.reversed().forEach { entry ->
            lines.add(sectionIndex + 1, entry)
        }
    } else {
        // Create new section at end
        lines.add("")
        lines.add("[$sectionName]")
        newEntries.forEach { entry ->
            lines.add(entry)
        }
    }

    return lines.joinToString("\n")
}

fun addModuleToSettings(
    targetDir: String,
    moduleName: String
) {
    val settingsFile = File(targetDir, "settings.gradle.kts")
    if (!settingsFile.exists()) {
        warnln { "settings.gradle.kts not found in $targetDir" }
        return
    }

    val content = settingsFile.readText()
    val includePattern = Regex("""include\s*\(\s*["']([^"']+)["']\s*\)""")
    val existingModules = includePattern.findAll(content).map { it.groupValues[1] }.toSet()

    if (existingModules.contains(":$moduleName")) {
        warnln { "Module ':$moduleName' is already included in settings.gradle.kts" }
        return
    }

    val lines = content.lines().toMutableList()

    // Add new include statement at the end
    lines.add("")
    lines.add("include(\":$moduleName\")")

    settingsFile.writeText(lines.joinToString("\n"))
}

fun createModuleOnly(
    targetDir: String,
    moduleName: String,
    packageName: String,
    appName: String,
    targets: Set<String>
) {
    val moduleDir = File(targetDir, moduleName)

    fun copyResource(resourcePath: String, targetFile: File) {
        val inputStream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
        if (inputStream != null) {
            targetFile.parentFile?.mkdirs()
            inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            error("Resource not found: $resourcePath")
            debugln { "Resource not found: $resourcePath" }
        }
    }

    fun listResources(path: String): List<String> {
        val resources = mutableListOf<String>()
        val resourceUrl = object {}.javaClass.getResource(path)

        if (resourceUrl != null) {
            when (resourceUrl.protocol) {
                "file" -> {
                    val dir = File(resourceUrl.toURI())
                    dir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val relativePath = file.relativeTo(dir)
                            resources.add("$path/${relativePath.path}")
                        }
                    }
                }

                "jar" -> {
                    val jarPath = resourceUrl.path.substringBefore("!")
                    val jarFile = JarFile(File(jarPath.substringAfter("file:")))
                    val entries = jarFile.entries()

                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.startsWith(path.substring(1)) && !entry.isDirectory) {
                            resources.add("/${entry.name}")
                        }
                    }
                    jarFile.close()
                }
            }
        }

        return resources
    }

    // Copy only the module contents (not the entire project)
    val resources = listResources("/project/composeApp")
    resources.forEach { resourcePath ->
        var targetPath = resourcePath.removePrefix("/project/composeApp/")

        // Skip source set directories if corresponding target is not selected
        val isInsideAKotlinSourceSet = targetPath.startsWith("src/")
        if (isInsideAKotlinSourceSet) {
            val sourceSetType = targetPath.substringAfter("src/").substringBefore("/")
            when (sourceSetType) {
                "androidMain" -> if (!targets.contains("android")) return@forEach
                "iosMain" -> if (!targets.contains("ios")) return@forEach
                "jvmMain" -> if (!targets.contains("jvm")) return@forEach
                "jsMain" -> if (!targets.contains("web")) return@forEach
                "wasmJsMain" -> if (!targets.contains("web")) return@forEach
                "webMain" -> if (!targets.contains("web")) return@forEach
                "commonMain" -> Unit
                else -> error("Unknown target: $targetPath")
            }
        }

        // Skip webpack.config.d directory if web target is not selected
        if (!targets.contains("web") && targetPath.startsWith("webpack.config.d/")) {
            return@forEach
        }

        // Replace org.example.project with the actual namespace in file paths
        targetPath = targetPath.replace("org/example/project", packageName.replace(".", "/"))

        val targetFile = moduleDir.resolve(targetPath)
        copyResource(resourcePath, targetFile)
    }

    // Replace placeholders in text files only (skip binary files)
    moduleDir.walkTopDown().forEach { file ->
        if (file.isFile) {
            // Skip binary files and known non-text files
            if (file.name.endsWith(".jar") ||
                file.name.endsWith(".png") ||
                file.name.endsWith(".jpg") ||
                file.name.endsWith(".jpeg") ||
                file.name.endsWith(".ico") ||
                file.name.endsWith(".icns") ||
                file.name.endsWith(".class")
            ) {
                return@forEach
            }

            try {
                val content = file.readText()
                var updatedContent = content.replace("{{app_name}}", appName)

                // Build imports block
                val imports = mutableListOf<String>()
                if (targets.contains("jvm")) {
                    imports.add("import org.jetbrains.compose.desktop.application.dsl.TargetFormat")
                }
                if (targets.contains("web")) {
                    imports.add("import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl")
                    imports.add("import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig")
                }
                if (targets.contains("android")) {
                    imports.add("import org.jetbrains.kotlin.gradle.dsl.JvmTarget")
                }
                val importsBlock = if (imports.isNotEmpty()) imports.joinToString("\n") + "\n" else ""

                // Build plugins block
                val plugins = mutableListOf<String>()
                plugins.add("    alias(libs.plugins.jetbrains.kotlin.multiplatform)")
                plugins.add("    alias(libs.plugins.jetbrains.compose)")
                plugins.add("    alias(libs.plugins.jetbrains.compose.compiler)")
                plugins.add("    alias(libs.plugins.jetbrains.compose.hotreload)")
                if (targets.contains("android")) {
                    plugins.add("    alias(libs.plugins.android.application)")
                }
                val pluginsBlock = "plugins {\n" + plugins.joinToString("\n") + "\n}"

                // Build kotlin targets block
                val kotlinTargets = mutableListOf<String>()
                if (targets.contains("android")) {
                    kotlinTargets.add(
                        """    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }"""
                    )
                }
                if (targets.contains("ios")) {
                    val baseName = toCamelCase(moduleName)
                    kotlinTargets.add(
                        """    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "$baseName"
            isStatic = true
        }
    }"""
                    )
                }
                if (targets.contains("jvm")) {
                    kotlinTargets.add("    jvm()")
                }
                if (targets.contains("web")) {
                    kotlinTargets.add(
                        """    js {
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }"""
                    )
                    kotlinTargets.add(
                        """    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        // Serve sources to debug inside browser
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }"""
                    )
                }
                val kotlinTargetsBlock =
                    if (kotlinTargets.isNotEmpty()) kotlinTargets.joinToString("\n\n") + "\n" else ""

                // Build sourcesets block
                val sourcesets = mutableListOf<String>()
                sourcesets.add(
                    """    sourceSets {
        commonMain.dependencies {
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.material3)
        }"""
                )

                if (targets.contains("jvm")) {
                    sourcesets.add(
                        """        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }"""
                    )
                }
                if (targets.contains("android")) {
                    sourcesets.add(
                        """        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activitycompose)
        }"""
                    )
                }
                sourcesets.add("    }")
                val sourcesetsBlock = sourcesets.joinToString("\n")

                // Build configuration blocks
                val configurations = mutableListOf<String>()
                if (targets.contains("android")) {
                    configurations.add(
                        """android {
    namespace = "{{namespace}}"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "{{namespace}}"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}"""
                    )
                }
                if (targets.contains("jvm")) {
                    configurations.add(
                        """compose.desktop {
    application {
        mainClass = "{{namespace}}.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "{{namespace}}"
            packageVersion = "1.0.0"
        }
    }
}"""
                    )
                }
                val configurationBlocksBlock =
                    if (configurations.isNotEmpty()) configurations.joinToString("\n\n") else ""

                // Replace composeApp build.gradle.kts blocks
                updatedContent = updatedContent.replace("{{imports}}", importsBlock)
                updatedContent = updatedContent.replace("{{plugins}}", pluginsBlock)
                updatedContent = updatedContent.replace("{{kotlin_targets}}", kotlinTargetsBlock)
                updatedContent = updatedContent.replace("{{sourcesets}}", sourcesetsBlock)
                updatedContent = updatedContent.replace("{{configuration_blocks}}", configurationBlocksBlock)

                // Replace remaining placeholders after blocks are built
                updatedContent = updatedContent.replace("{{namespace}}", packageName)
                updatedContent = updatedContent.replace("{{module_name}}", moduleName)
                updatedContent = updatedContent.replace("{{app_name}}", appName)
                updatedContent = updatedContent.replace("{{ios_binary_name}}", toCamelCase(moduleName))
                updatedContent = updatedContent.replace("{{target_name}}", "${toCamelCase(moduleName)}.app")
                if (content != updatedContent) {
                    file.writeText(updatedContent.trim() + "\n")
                }
            } catch (e: Exception) {
                // If we can't read as text, skip this file
                debugln { "Skipping binary file: ${file.name}" }
            }
        }
    }

    // Log module creation summary
    infoln { "" }
    infoln { "Module Configuration:" }
    infoln { "\tApp Name: $appName" }
    infoln { "\tPackage: $packageName" }
    infoln { "\tModule: $moduleName" }
    infoln { "\tTargets: ${targets.joinToString(", ")}" }
    infoln { "" }

    debugln { "Success! Your new Compose Multiplatform module is ready at ${moduleDir.absolutePath}" }
}

private fun createIosAppDirectory(
    targetDir: String,
    moduleName: String
) {
    val iosAppName = "ios${toCamelCase(moduleName)}"
    val targetDir = File(targetDir, iosAppName)

    fun copyResource(resourcePath: String, targetFile: File) {
        val inputStream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
        if (inputStream != null) {
            targetFile.parentFile?.mkdirs()
            inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun listResources(path: String): List<String> {
        val resources = mutableListOf<String>()
        val resourceUrl = object {}.javaClass.getResource(path)

        if (resourceUrl != null) {
            when (resourceUrl.protocol) {
                "file" -> {
                    val dir = File(resourceUrl.toURI())
                    dir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            val relativePath = file.relativeTo(dir)
                            resources.add("$path/${relativePath.path}")
                        }
                    }
                }

                "jar" -> {
                    val jarPath = resourceUrl.path.substringBefore("!")
                    val jarFile = JarFile(File(jarPath.substringAfter("file:")))
                    val entries = jarFile.entries()

                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.name.startsWith(path.substring(1)) && !entry.isDirectory) {
                            resources.add("/${entry.name}")
                        }
                    }
                    jarFile.close()
                }
            }
        }

        return resources
    }

    val resources = listResources("/project/iosApp")
    resources.forEach { resourcePath ->
        val targetPath = resourcePath.removePrefix("/project/iosApp/")
        val targetFile = targetDir.resolve(targetPath)
        copyResource(resourcePath, targetFile)

        // Replace placeholders in text files
        if (targetFile.name.endsWith(".swift") || targetFile.name.endsWith(".h") || targetFile.name.endsWith(".m") ||
            targetFile.name.endsWith(".pbxproj") || targetFile.name.endsWith(".xcconfig")
        ) {
            try {
                val content = targetFile.readText()
                var updatedContent = content.replace("{{module_name}}", moduleName)
                updatedContent = updatedContent.replace("{{ios_binary_name}}", toCamelCase(moduleName))
                updatedContent = updatedContent.replace("{{target_name}}", "${toCamelCase(moduleName)}.app")
                // Use defaults for module addition since we don't have app name/namespace in scope
                updatedContent = updatedContent.replace("{{app_name}}", "My App")
                updatedContent = updatedContent.replace("{{namespace}}", "com.example.app")
                if (content != updatedContent) {
                    targetFile.writeText(updatedContent)
                }
            } catch (e: Exception) {
                // Skip binary files
            }
        }
    }
}
