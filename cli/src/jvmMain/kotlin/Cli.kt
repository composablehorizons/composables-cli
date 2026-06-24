package com.composables.cli

import com.alexstyl.debugln.debugln
import com.alexstyl.debugln.infoln
import com.alexstyl.debugln.warnln
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

val ANDROID = "android"
val JVM = "jvm"
val IOS = "ios"
val WASM = "wasm"
val SHARED_MODULE = "shared"
val ANDROID_APP_MODULE = "androidApp"
val IOS_APP_MODULE = "iosApp"
val DESKTOP_APP_MODULE = "desktopApp"
val WEB_APP_MODULE = "webApp"

private fun File.toResourcePath(): String = invariantSeparatorsPath.replace('\\', '/')

private fun normalizeTargets(targets: Set<String>): LinkedHashSet<String> = linkedSetOf<String>().apply {
    addAll(targets)
}

private data class ProjectInstruction(
    val label: String,
    val detail: String? = null,
    val command: String? = null,
)

private fun buildProjectInstructions(
    targets: Set<String>,
    gradleCommand: String,
): List<ProjectInstruction> {
    val normalizedTargets = normalizeTargets(targets)
    return buildList {
        if (normalizedTargets.contains(JVM)) {
            add(ProjectInstruction(label = "JVM", command = "$gradleCommand :$DESKTOP_APP_MODULE:hotRunJvm --auto"))
        }
        if (normalizedTargets.contains(ANDROID)) {
            add(
                ProjectInstruction(
                    label = "Android",
                    detail = "open the project in Android Studio and run the `$ANDROID_APP_MODULE` app on a device or emulator",
                ),
            )
            add(ProjectInstruction(label = "Android install from terminal", command = "$gradleCommand :$ANDROID_APP_MODULE:installDebug"))
        }
        if (normalizedTargets.contains(IOS)) {
            add(
                ProjectInstruction(
                    label = "iOS",
                    detail = "open `$IOS_APP_MODULE/$IOS_APP_MODULE.xcodeproj` in Xcode and run the app on a simulator or device",
                ),
            )
        }
        if (normalizedTargets.contains(WASM)) {
            add(ProjectInstruction(label = "Wasm", command = "$gradleCommand :$WEB_APP_MODULE:wasmJsBrowserDevelopmentRun"))
        }
    }
}

internal fun buildProjectStartCommand(
    targets: Set<String>,
    gradleCommand: String,
): String = buildProjectInstructions(
    targets = targets,
    gradleCommand = gradleCommand,
).firstOrNull { it.command != null }?.command ?: "$gradleCommand build"

private fun buildProjectReadme(
    projectName: String,
    targets: Set<String>,
): String {
    val lines = mutableListOf<String>()

    lines += "# $projectName"
    lines += ""
    lines += "## Run"
    lines += ""
    lines += "From the project root:"
    lines += ""

    buildProjectInstructions(
        targets = targets,
        gradleCommand = "./gradlew",
    ).forEach { instruction ->
        val description = instruction.command?.let { "`$it`" } ?: instruction.detail
        lines += "- ${instruction.label}: $description"
    }

    return lines.joinToString("\n") + "\n"
}

suspend fun main(args: Array<String>) {
    ComposablesCli()
        .subcommands(CreateApp(), Init(), Target())
        .main(args)
}

class ComposablesCli : CliktCommand(name = "composables") {
    init {
        versionOption(
            version = BuildConfig.Version,
            names = setOf("-v", "--version"),
            message = { BuildConfig.Version },
        )
    }

    override fun run() {
    }

    override fun help(context: Context) = """
        If you have any problems or need help, do not hesitate to ask for help at:
            https://github.com/composablehorizons/composables-cli
    """.trimIndent()
}

class CreateApp : CliktCommand("create-app") {
    override fun help(context: Context): String = """
        Creates a new Compose Multiplatform app in the specified <directory> path.
    """.trimIndent()

    private val directory by argument("directory", help = "The directory path to create the new app in").optional()
    private val packageName by option("--package", help = "The package name for the generated app")
    private val appName by option("--app-name", help = "The display name for the generated app")
    private val targetsInput by option("--targets", help = "Comma-separated targets: android,jvm,ios,wasm")
    private val overwrite by option("--overwrite", help = "Overwrite an existing target directory").flag(default = false)

    override fun run() {
        val anyExplicitInput = directory != null || packageName != null || appName != null || targetsInput != null || overwrite
        val workingDir = System.getProperty("user.dir")

        val target: File
        val resolvedPackageName: String
        val resolvedAppName: String
        val targets: Set<String>

        if (!anyExplicitInput) {
            try {
                target = readNewAppDirectory(workingDir)
                resolvedPackageName = readNamespace()
                resolvedAppName = readAppName()
                targets = readTargets()
            } catch (error: RuntimeException) {
                if (error::class.simpleName == "ReadAfterEOFException" || error.message?.contains("EOF has already been reached") == true) {
                    throw UsageError(
                        "Interactive mode requires stdin. Pass <directory>, --package, --app-name, and --targets for non-interactive use.",
                    )
                }
                throw error
            }
        } else {
            val missingInputs = buildList {
                if (directory == null) add("<directory>")
                if (packageName == null) add("--package")
                if (appName == null) add("--app-name")
                if (targetsInput == null) add("--targets")
            }
            if (missingInputs.isNotEmpty()) {
                throw UsageError("When using create-app non-interactively, specify all required inputs. Missing: ${missingInputs.joinToString(", ")}")
            }

            resolvedPackageName = packageName!!
            resolvedAppName = appName!!

            if (!isValidPackageName(resolvedPackageName)) {
                throw UsageError("Invalid package name. Must be a valid Java package name (e.g., com.example.app)")
            }

            if (!isValidAppName(resolvedAppName)) {
                throw UsageError("Invalid app name. Must contain at least one letter or digit")
            }

            targets = try {
                parseTargets(targetsInput!!)
            } catch (error: IllegalArgumentException) {
                throw UsageError(error.message ?: "Invalid targets")
            }
            target = resolveTargetDirectory(
                workingDir = workingDir,
                projectPath = directory!!,
            )
            validateCreateAppTargetDirectory(target, overwrite)
        }

        if (!target.exists() && !target.mkdirs()) {
            throw UsageError("Failed to create directory at ${target.absolutePath}")
        }

        cloneGradleProjectAndPrint(
            target = target,
            packageName = resolvedPackageName,
            appName = resolvedAppName,
            targets = targets,
            moduleName = SHARED_MODULE,
        )
    }
}

class Init : CliktCommand("init") {
    override fun help(context: Context): String = """
        Initializes a new Compose Multiplatform module to the specified <directory> path.
    """.trimIndent()

    private val directory by argument("directory", help = "The directory path to create the new module in").optional()

    override fun run() {
        val workingDir = System.getProperty("user.dir")
        val projectPath = directory.orEmpty()
        if (projectPath.isBlank()) {
            debugln { "Please specify the project directory:" }
            infoln { "composables init <project-directory>" }
            debugln { "" }
            debugln { "For example:" }
            infoln { "composables init app" }
            return
        }
        val target = resolveTargetDirectory(workingDir = workingDir, projectPath = projectPath)
        val projectName = target.name

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
                    echo("Gradle project detected. This will add a new module to your existing project. Is this what you want? y/n ", trailingNewline = false)
                    val response = readln().trim().lowercase()
                    if (response != "y" && response != "yes") {
                        echo("Operation cancelled.")
                        return
                    }

                    // Check Kotlin version
                    val kotlinVersion = getKotlinVersion(target)
                    if (kotlinVersion != null && !isKotlinVersionSupported(kotlinVersion)) {
                        echo("Kotlin version $kotlinVersion is not supported. At least version 2.4.0 is required.")
                        echo("Please update your Kotlin version and try again.")
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
                        targets = targets,
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
                    val dirName = if (projectPath == ".") "The current directory" else "The directory $projectPath"
                    echo("$dirName is not empty and does not contain a Gradle project.")
                    echo("Try a new directory path or delete the existing one before trying to initialize a new module.")
                    return
                }
            }
        }

        val moduleName = readModuleName(projectName)
        val appName = readAppName()
        val namespace = readNamespace()
        val targets = readTargets()

        if (!target.mkdirs()) {
            echo("Failed to create directory $projectName")
            return
        }

        cloneGradleProjectAndPrint(
            target = target,
            packageName = namespace,
            appName = appName,
            targets = targets,
            moduleName = moduleName,
        )
    }

    private fun readModuleName(projectName: String): String {
        while (true) {
            echo("Enter module name (default: $SHARED_MODULE): ", trailingNewline = false)
            val moduleName = readln().trim()

            if (moduleName.isEmpty()) {
                return SHARED_MODULE
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
            echo("Enter module name (default: $SHARED_MODULE): ", trailingNewline = false)
            val moduleName = readln().trim()

            if (moduleName.isEmpty()) {
                if (File(targetDir, SHARED_MODULE).exists()) {
                    echo("Module name '$SHARED_MODULE' already exists. Please choose a different name.")
                    continue
                }
                return SHARED_MODULE
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
}

internal fun resolveTargetDirectory(workingDir: String, projectPath: String): File {
    if (projectPath == ".") {
        return File(workingDir)
    }

    val requestedPath = File(projectPath)
    return if (requestedPath.isAbsolute) {
        requestedPath
    } else {
        File(workingDir).resolve(projectPath)
    }
}

internal fun validateCreateAppTargetDirectory(target: File, overwrite: Boolean) {
    when {
        target.exists() && overwrite -> {
            if (!target.deleteRecursively()) {
                throw UsageError("Failed to overwrite existing directory at ${target.absolutePath}")
            }
        }

        target.exists() && target.isFile -> {
            throw UsageError("Target path ${target.absolutePath} is a file. Choose a different directory or use --overwrite.")
        }

        target.exists() && target.listFiles()?.isNotEmpty() == true -> {
            throw UsageError("Target directory ${target.absolutePath} already exists and is not empty. Use --overwrite to replace it.")
        }

        target.exists() && target.listFiles()?.isEmpty() == true -> {
            target.deleteRecursively()
        }
    }
}

internal fun readNewAppDirectory(workingDir: String): File {
    while (true) {
        print("Enter project directory: ")
        val projectPath = readln().trim()
        if (projectPath.isBlank()) {
            println("Project directory is required.")
            continue
        }

        val target = resolveTargetDirectory(workingDir = workingDir, projectPath = projectPath)
        when {
            target.exists() && target.isFile -> {
                println("Target path ${target.absolutePath} is a file. Choose a different directory.")
            }

            target.exists() && target.listFiles()?.isNotEmpty() == true -> {
                println("Target directory ${target.absolutePath} already exists and is not empty. Choose a different directory.")
            }

            else -> {
                if (target.exists() && target.listFiles()?.isEmpty() == true) {
                    target.deleteRecursively()
                }
                return target
            }
        }
    }
}

internal fun readNamespace(): String {
    while (true) {
        print("Enter package name (default: com.example.app): ")
        val namespace = readln().trim()
        if (namespace.isEmpty()) {
            return "com.example.app"
        }

        if (!isValidPackageName(namespace)) {
            println("Invalid package name. Must be a valid Java package name (e.g., com.example.app)")
            continue
        }

        return namespace
    }
}

internal fun readAppName(): String {
    while (true) {
        print("Enter app name (default: My App): ")
        val appName = readln().trim()

        if (appName.isEmpty()) {
            return "My App"
        }

        if (!isValidAppName(appName)) {
            println("Invalid app name. Must contain at least one letter or digit")
            continue
        }

        return appName
    }
}

internal fun readTargets(): Set<String> {
    while (true) {
        val targets = mutableSetOf<String>()

        println("Which platforms would you like your app to run on?")

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
            print("Wasm (Browser) (y/n, default: y): ")
            val wasm = readln().trim().lowercase()
            if (wasm.isEmpty() || wasm == "y" || wasm == "yes") {
                targets.add(WASM)
            }
            break
        }

        if (targets.isNotEmpty()) {
            return targets
        } else {
            println("At least one platform is required...")
        }
    }
}

internal fun isValidAppName(appName: String): Boolean {
    if (appName.isEmpty()) return false
    return appName.any { char -> char.isLetterOrDigit() }
}

internal fun isValidModuleName(moduleName: String): Boolean {
    if (moduleName.isEmpty()) return false
    return moduleName.any { char -> char.isLetterOrDigit() } &&
        moduleName.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' }
}

internal fun isValidPackageName(packageName: String): Boolean {
    if (packageName.isEmpty()) return false

    val parts = packageName.split(".")
    if (parts.size < 2) return false

    return parts.all { part ->
        part.isNotEmpty() &&
            part[0].isLetter() &&
            part.all { char -> char.isLetterOrDigit() || char == '_' }
    }
}

internal fun parseTargets(targetsInput: String): Set<String> {
    val validTargets = setOf(ANDROID, JVM, IOS, WASM)
    val targets = targetsInput
        .split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }

    if (targets.isEmpty()) {
        throw IllegalArgumentException("At least one target is required. Use --targets android,jvm,ios,wasm")
    }

    val invalidTargets = targets.filterNot { it in validTargets }
    if (invalidTargets.isNotEmpty()) {
        throw IllegalArgumentException(
            "Unknown targets: ${invalidTargets.joinToString(", ")}. Available targets: android, jvm, ios, wasm",
        )
    }

    return linkedSetOf<String>().apply {
        addAll(targets)
    }
}

private fun toCamelCase(input: String): String = input.split(Regex("[-_]"))
    .mapIndexed { index, part ->
        if (index == 0) {
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        } else {
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
    .joinToString("")

private fun toProjectAccessorName(input: String): String = toCamelCase(input)
    .replaceFirstChar { if (it.isUpperCase()) it.lowercase() else it.toString() }

private fun toNamespaceSegment(input: String): String = toProjectAccessorName(input)

class Target : CliktCommand("target") {
    override fun help(context: Context): String = """
        Adds a new Kotlin target to the current Compose Multiplatform project (options: android, jvm, ios, wasm).
    """.trimIndent()

    private val targetName by argument(name = "target")

    override fun run() {
        val validTargets = setOf("android", "jvm", "ios", "wasm")

        if (targetName !in validTargets) {
            echo("Unknown target '$targetName'")
            echo("Available targets: android, jvm, ios, wasm")
            echo("Usage: composables target <target-name>")
            return
        }

        val workingDir = System.getProperty("user.dir")

        if (!isValidComposeAppDirectory(workingDir)) {
            echo("This doesn't appear to be a Compose Multiplatform project.")
            echo("To create a new Compose app, run:")
            echo("    composables create-app app --package com.example.app --app-name \"My App\" --targets android,jvm,ios,wasm")
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

            "wasm" -> {
                if (hasWasmTarget(composeModuleBuildFile)) {
                    echo("Wasm target is already configured in this project.")
                    return
                }
                try {
                    addWasmTarget(workingDir, composeModuleBuildFile)
                    echo("Wasm target added successfully!")
                    echo("Run '$gradleScript build' to verify the configuration.")
                } catch (e: Exception) {
                    echo("Failed to add wasm target: ${e.message}", err = true)
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
            "org.jetbrains.compose.ui:ui-tooling-preview",
            "libs.compose.ui",
            "libs.compose.ui.tooling.preview",
            "libs.composables.ui",
            "com.composables:ui:",
            "compose.desktop.currentOs",
            "compose.runtime",
        )

        return composeDependencies.any { dependency ->
            content.contains(dependency)
        }
    }

    private fun findComposeModuleBuildFile(workingDir: String): File? {
        val dir = File(workingDir)

        val sharedBuildFile = File(dir, "$SHARED_MODULE/build.gradle.kts")
        if (sharedBuildFile.exists()) {
            return sharedBuildFile
        }

        val composeModules = dir.listFiles()?.filter { subDir ->
            subDir.isDirectory && File(subDir, "build.gradle.kts").exists()
        }?.filter { subDir ->
            val buildFile = File(subDir, "build.gradle.kts")
            val content = buildFile.readText()
            hasComposeDependencies(content)
        } ?: return null

        val sharedLikeComposeModules = composeModules.filter { subDir ->
            File(subDir, "src/commonMain").isDirectory
        }

        when {
            sharedLikeComposeModules.size == 1 -> return File(sharedLikeComposeModules.first(), "build.gradle.kts")
            sharedLikeComposeModules.size > 1 -> return selectComposeModule(sharedLikeComposeModules)
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
            echo("Select a module (1-${sortedModules.size}): ", trailingNewline = false)
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
        return content.contains("android {")
    }

    private fun hasJvmTarget(buildFile: File): Boolean {
        val content = buildFile.readText()
        return content.contains("jvm()")
    }

    private fun hasIosTarget(buildFile: File): Boolean {
        val content = buildFile.readText()
        return content.contains("iosArm64()") || content.contains("iosSimulatorArm64()")
    }

    private fun hasWasmTarget(buildFile: File): Boolean {
        val content = buildFile.readText()
        return content.contains("wasmJs")
    }

    private fun addAndroidTarget(workingDir: String, buildFile: File) {
        val content = buildFile.readText()
        val lines = content.lines().toMutableList()
        val moduleDir = buildFile.parentFile
        val moduleName = moduleDir.name
        val namespace = inferNamespace(moduleDir)

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
            lines.add(pluginsCloseIndex, "    alias(libs.plugins.android.kotlin.multiplatform.library)")
        }

        // Append to kotlin block
        val kotlinCloseIndex = findKotlinBlockEnd(lines)
        if (kotlinCloseIndex >= 0) {
            val moduleNamespace = toNamespaceSegment(moduleName)
            val androidTargetLines = listOf(
                "",
                "    android {",
                "        namespace = \"$namespace.$moduleNamespace\"",
                "        compileSdk = libs.versions.android.compileSdk.get().toInt()",
                "        minSdk = libs.versions.android.minSdk.get().toInt()",
                "        withJava()",
                "        androidResources {",
                "            enable = true",
                "        }",
                "        compilerOptions {",
                "            jvmTarget.set(JvmTarget.JVM_17)",
                "        }",
                "    }",
            )
            androidTargetLines.reversed().forEach { line ->
                lines.add(kotlinCloseIndex, line)
            }
        }

        // Write updated content
        buildFile.writeText(lines.joinToString("\n"))

        createAndroidAppModule(
            projectDir = File(workingDir),
            sharedModuleName = moduleName,
            namespace = namespace,
        )
        addModuleIncludeToSettings(workingDir, ANDROID_APP_MODULE)

        // Update root build.gradle.kts
        updateRootBuildFile(workingDir, setOf(ANDROID))

        // Update gradle.properties
        updateGradleProperties(workingDir)

        // Update libs.versions.toml
        updateVersionCatalog(workingDir, setOf(ANDROID))
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

    private fun extractNamespace(lines: List<String>): String {
        // Try to find existing namespace from android block or use a default
        for (line in lines) {
            if (line.trim().startsWith("namespace =")) {
                return line.trim().substringAfter("namespace =").trim().removeSurrounding("\"")
            }
        }
        return "com.example.app" // fallback
    }

    private fun inferNamespace(moduleDir: File): String {
        val commonMainDir = File(moduleDir, "src/commonMain/kotlin")
        if (commonMainDir.exists()) {
            commonMainDir.walkTopDown()
                .firstOrNull { it.isFile && it.extension == "kt" }
                ?.useLines { lines ->
                    lines.firstOrNull { it.startsWith("package ") }
                        ?.removePrefix("package ")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
                ?.let { return it }
        }

        return "com.example.app"
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

    private fun createAndroidAppModule(
        projectDir: File,
        sharedModuleName: String,
        namespace: String,
    ) {
        val androidAppDir = File(projectDir, ANDROID_APP_MODULE)
        androidAppDir.mkdirs()
        File(androidAppDir, "build.gradle.kts").writeText(
            """
            plugins {
                alias(libs.plugins.android.application)
                alias(libs.plugins.jetbrains.compose.compiler)
            }

            android {
                namespace = "$namespace"
                compileSdk = libs.versions.android.compileSdk.get().toInt()

                defaultConfig {
                    applicationId = "$namespace"
                    minSdk = libs.versions.android.minSdk.get().toInt()
                    targetSdk = libs.versions.android.targetSdk.get().toInt()
                    versionCode = 1
                    versionName = "1.0"
                }
                buildFeatures {
                    compose = true
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
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
            }

            dependencies {
                implementation(projects.${toProjectAccessorName(sharedModuleName)})
                implementation(libs.androidx.activity.compose)
            }
            """.trimIndent() + "\n",
        )

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
                                resources.add("$path/${relativePath.toResourcePath()}")
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

        val resources = listResources("/project/$ANDROID_APP_MODULE/src/main")
        resources.forEach { resourcePath ->
            val targetPath = resourcePath.removePrefix("/project/$ANDROID_APP_MODULE/src/main/")
                .replace("org/example/project", namespace.replace(".", "/"))
            val targetFile = File(androidAppDir, "src/main/$targetPath")
            copyResource(resourcePath, targetFile)

            if (targetFile.name.endsWith(".kt") || targetFile.name.endsWith(".xml")) {
                try {
                    val content = targetFile.readText()
                    var updatedContent = content.replace("{{namespace}}", namespace)
                    updatedContent = updatedContent.replace("{{app_name}}", projectDir.name)
                    if (content != updatedContent) {
                        targetFile.writeText(updatedContent)
                    }
                } catch (e: Exception) {
                    // Skip binary files
                }
            }
        }
    }

    private fun addModuleIncludeToSettings(
        workingDir: String,
        moduleName: String,
    ) {
        val settingsFile = File(workingDir, "settings.gradle.kts")
        if (!settingsFile.exists()) return

        val content = settingsFile.readText()
        if (content.contains("""include(":$moduleName")""")) return

        settingsFile.writeText(content.trimEnd() + "\ninclude(\":$moduleName\")\n")
    }

    private fun addJvmTarget(workingDir: String, buildFile: File) {
        val content = buildFile.readText()
        val lines = content.lines().toMutableList()

        // Append to kotlin block
        val kotlinCloseIndex = findKotlinBlockEnd(lines)
        if (kotlinCloseIndex >= 0) {
            lines.add(kotlinCloseIndex, "    jvm()")
        }

        // Write updated content
        buildFile.writeText(lines.joinToString("\n"))

        val moduleDir = buildFile.parentFile
        val moduleName = moduleDir.name
        val namespace = inferNamespace(moduleDir)

        createDesktopAppModule(
            projectDir = File(workingDir),
            sharedModuleName = moduleName,
            namespace = namespace,
        )
        addModuleIncludeToSettings(workingDir, DESKTOP_APP_MODULE)
        updateRootBuildFile(workingDir, setOf(JVM))
        updateVersionCatalog(workingDir, setOf(JVM))
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
                "    }",
            )
            iosTargetLines.reversed().forEach { line ->
                lines.add(kotlinCloseIndex, line)
            }
        }

        // Write updated content
        buildFile.writeText(lines.joinToString("\n"))

        // Create iosMain source set
        val moduleDir = buildFile.parentFile
        createIosSourceSet(moduleDir, extractNamespace(lines))

        // Copy iOS app directory
        copyIosAppDirectory(workingDir, moduleDir.name)
    }

    private fun createIosSourceSet(moduleDir: File, namespace: String) {
        val iosMainDir = File(moduleDir, "src/iosMain/kotlin")
        val packageDir = File(iosMainDir, namespace.replace(".", "/"))

        // Create directories
        packageDir.mkdirs()

        val mainFile = File(packageDir, "MainViewController.kt")
        val mainContent = """package $namespace

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() = ComposeUIViewController { App() }
"""
        mainFile.writeText(mainContent)
    }

    private fun addWasmTarget(workingDir: String, buildFile: File) {
        val content = buildFile.readText()
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

        // Append to kotlin block
        val kotlinCloseIndex = findKotlinBlockEnd(lines)
        if (kotlinCloseIndex >= 0) {
            val wasmTargetLines = listOf(
                "",
                "    @OptIn(ExperimentalWasmDsl::class)",
                "    wasmJs {",
                "        browser()",
                "    }",
            )
            wasmTargetLines.reversed().forEach { line ->
                lines.add(kotlinCloseIndex, line)
            }
        }

        // Write updated content
        buildFile.writeText(lines.joinToString("\n"))

        val moduleDir = buildFile.parentFile
        val moduleName = moduleDir.name
        val namespace = inferNamespace(moduleDir)

        createWebAppModule(
            projectDir = File(workingDir),
            sharedModuleName = moduleName,
            namespace = namespace,
        )
        addModuleIncludeToSettings(workingDir, WEB_APP_MODULE)
        updateRootBuildFile(workingDir, setOf(WASM))
        updateVersionCatalog(workingDir, setOf(WASM))
    }

    private fun createDesktopAppModule(
        projectDir: File,
        sharedModuleName: String,
        namespace: String,
    ) {
        val desktopAppDir = File(projectDir, DESKTOP_APP_MODULE)
        desktopAppDir.mkdirs()
        File(desktopAppDir, "build.gradle.kts").writeText(
            object {}.javaClass.getResource("/project/$DESKTOP_APP_MODULE/build.gradle.kts")!!.readText()
                .replace("{{shared_module_accessor}}", toProjectAccessorName(sharedModuleName))
                .replace("{{namespace}}", namespace)
                .trim() + "\n",
        )

        val mainFile = File(desktopAppDir, "src/jvmMain/kotlin/${namespace.replace(".", "/")}/main.kt")
        mainFile.parentFile.mkdirs()
        mainFile.writeText(
            object {}.javaClass.getResource("/project/$DESKTOP_APP_MODULE/src/jvmMain/kotlin/org/example/main.kt")!!.readText()
                .replace("{{namespace}}", namespace)
                .trim() + "\n",
        )
    }

    private fun createWebAppModule(
        projectDir: File,
        sharedModuleName: String,
        namespace: String,
    ) {
        val webAppDir = File(projectDir, WEB_APP_MODULE)

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
                                resources.add("$path/${relativePath.toResourcePath()}")
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

        listResources("/project/$WEB_APP_MODULE").forEach { resourcePath ->
            var targetPath = resourcePath.removePrefix("/project/$WEB_APP_MODULE/")
            targetPath = targetPath.replace("org/example", namespace.replace(".", "/"))
            val targetFile = webAppDir.resolve(targetPath)
            copyResource(resourcePath, targetFile)

            if (targetFile.isFile &&
                (
                    targetFile.extension == "kts" ||
                        targetFile.extension == "kt" ||
                        targetFile.extension == "html" ||
                        targetFile.extension == "css" ||
                        targetFile.extension == "js"
                    )
            ) {
                val content = targetFile.readText()
                val updatedContent = content
                    .replace("{{shared_module_accessor}}", toProjectAccessorName(sharedModuleName))
                    .replace("{{namespace}}", namespace)
                if (content != updatedContent) {
                    targetFile.writeText(updatedContent.trim() + "\n")
                }
            }
        }
    }

    private fun copyIosAppDirectory(workingDir: String, moduleName: String) {
        val targetDir = File(workingDir, IOS_APP_MODULE)

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
                                resources.add("$path/${relativePath.toResourcePath()}")
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

        val resources = listResources("/project/$IOS_APP_MODULE")
        resources.forEach { resourcePath ->
            val targetPath = resourcePath.removePrefix("/project/$IOS_APP_MODULE/")
            val targetFile = targetDir.resolve(targetPath)
            copyResource(resourcePath, targetFile)

            // Replace placeholders in text files
            if (targetFile.name.endsWith(".swift") ||
                targetFile.name.endsWith(".h") ||
                targetFile.name.endsWith(".m") ||
                targetFile.name.endsWith(
                    ".pbxproj",
                ) ||
                targetFile.name.endsWith(".xcconfig")
            ) {
                try {
                    val content = targetFile.readText()
                    var updatedContent = content.replace("{{module_name}}", moduleName)
                    updatedContent = updatedContent.replace("{{ios_binary_name}}", toCamelCase(moduleName))
                    updatedContent = updatedContent.replace("{{target_name}}", "$IOS_APP_MODULE.app")
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
        return if (System.getProperty("os.name").lowercase().contains("win")) {
            "gradlew.bat"
        } else {
            "./gradlew"
        }
    }

fun cloneGradleProjectAndPrint(
    target: File,
    packageName: String,
    appName: String,
    targets: Set<String>,
    moduleName: String,
) {
    cloneGradleProjectAt(
        target = target,
        packageName = packageName,
        appName = appName,
        targets = targets,
        moduleName = moduleName,
    )
    // Log project configuration summary
    infoln { "" }
    infoln { "Project Configuration:" }
    infoln { "\tApp Name: $appName" }
    infoln { "\tPackage: $packageName" }
    infoln { "\tShared Module: $moduleName" }
    infoln { "\tTargets: ${targets.joinToString(", ")}" }
    infoln { "" }

    debugln { "Success! Your new Compose app is ready at ${target.absolutePath}" }
    debugln { "Start by typing:" }
    infoln { "" }
    infoln { "\tcd ${target.absolutePath}" }
    val startCommand = buildProjectStartCommand(targets = targets, gradleCommand = gradleScript)
    infoln { "\t$startCommand" }
    infoln { "" }
    debugln { "Happy coding!" }
}

fun cloneGradleProject(
    targetDir: String,
    dirName: String,
    packageName: String,
    appName: String,
    targets: Set<String>,
    moduleName: String,
) {
    val normalizedTargets = normalizeTargets(targets)
    val target = File(targetDir).resolve(dirName)
    cloneGradleProjectAt(
        target = target,
        packageName = packageName,
        appName = appName,
        targets = normalizedTargets,
        moduleName = moduleName,
    )
}

private fun cloneGradleProjectAt(
    target: File,
    packageName: String,
    appName: String,
    targets: Set<String>,
    moduleName: String,
) {
    val normalizedTargets = normalizeTargets(targets)
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
                        if (file.isFile) { // Only include files, not directories
                            val relativePath = file.relativeTo(dir)
                            resources.add("$path/${relativePath.toResourcePath()}")
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
        if (!normalizedTargets.contains(IOS) && targetPath.startsWith("$IOS_APP_MODULE/")) {
            return@forEach
        }
        if (!normalizedTargets.contains(ANDROID) && targetPath.startsWith("$ANDROID_APP_MODULE/")) {
            return@forEach
        }
        if (!normalizedTargets.contains(JVM) && targetPath.startsWith("$DESKTOP_APP_MODULE/")) {
            return@forEach
        }
        if (!normalizedTargets.contains(WASM) && targetPath.startsWith("$WEB_APP_MODULE/")) {
            return@forEach
        }

        // Skip source set directories if corresponding target is not selected
        val isInsideAKotlinSourceSet = targetPath.startsWith("$SHARED_MODULE/src/")
        if (isInsideAKotlinSourceSet) {
            val sourceSetType = targetPath.substringAfter("$SHARED_MODULE/src/").substringBefore("/")

            when (sourceSetType) {
                "androidMain" -> if (!normalizedTargets.contains(ANDROID)) return@forEach
                "iosMain" -> if (!normalizedTargets.contains(IOS)) return@forEach
                "jvmMain" -> if (!normalizedTargets.contains(JVM)) return@forEach
                "wasmJsMain" -> if (!normalizedTargets.contains(WASM)) return@forEach
                "commonMain" -> Unit
                else -> error("Unknown target: $targetPath")
            }
        }

        // Replace org.example.project with the actual namespace in file paths
        targetPath = targetPath.replace("org/example/project", packageName.replace(".", "/"))
        targetPath = targetPath.replace("org/example", packageName.replace(".", "/"))

        // Replace shared module directory when a custom name is requested
        targetPath = targetPath.replace(SHARED_MODULE, moduleName)

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
                val updatedContent = renderProjectTemplate(
                    content = content,
                    packageName = packageName,
                    moduleName = moduleName,
                    appName = appName,
                    targets = normalizedTargets,
                    projectName = target.name,
                )
                if (content != updatedContent) {
                    file.writeText(updatedContent)
                }
            } catch (e: Exception) {
                // If we can't read as text, skip this file
                debugln { "Skipping binary file: ${file.name}" }
            }
        }
    }

    File(target, "README.md").writeText(
        buildProjectReadme(
            projectName = target.name,
            targets = normalizedTargets,
        ),
    )
}

private fun renderProjectTemplate(
    content: String,
    packageName: String,
    moduleName: String,
    appName: String,
    targets: Set<String>,
    projectName: String = "",
): String {
    val normalizedTargets = normalizeTargets(targets)
    val sharedModuleNamespace = toNamespaceSegment(moduleName)
    val imports = buildList {
        if (normalizedTargets.contains(ANDROID)) add("import org.jetbrains.kotlin.gradle.dsl.JvmTarget")
        if (normalizedTargets.contains(WASM)) add("import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl")
    }
    val plugins = buildList {
        add("    alias(libs.plugins.jetbrains.kotlin.multiplatform)")
        add("    alias(libs.plugins.jetbrains.compose)")
        if (!content.contains("libs.plugins.kotlin.compose")) {
            add("    alias(libs.plugins.jetbrains.compose.compiler)")
        }
        if (normalizedTargets.contains(ANDROID)) {
            add("    alias(libs.plugins.android.kotlin.multiplatform.library)")
        }
    }
    val kotlinTargets = buildList {
        if (normalizedTargets.contains(ANDROID)) {
            add(
                """    android {
        namespace = "{{namespace}}.$sharedModuleNamespace"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withJava()
        androidResources {
            enable = true
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }""",
            )
        }
        if (normalizedTargets.contains(IOS)) {
            add(
                """    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "${toCamelCase(moduleName)}"
            isStatic = true
        }
    }""",
            )
        }
        if (normalizedTargets.contains(JVM)) add("    jvm()")
        if (normalizedTargets.contains(WASM)) {
            add(
                """    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }""",
            )
        }
    }

    val replacements = linkedMapOf(
        "{{android_versions}}" to if (normalizedTargets.contains(ANDROID)) {
            """# Android
agp = "9.2.1"
android-compileSdk = "37"
android-minSdk = "23"
android-targetSdk = "37"
activityCompose = "1.13.0"

"""
        } else {
            ""
        },
        "{{compose_libraries}}" to """compose-ui = { group = "org.jetbrains.compose.ui", name = "ui", version.ref = "compose" }
compose-ui-tooling = { group = "org.jetbrains.compose.ui", name = "ui-tooling", version.ref = "compose" }
compose-ui-tooling-preview = { group = "org.jetbrains.compose.ui", name = "ui-tooling-preview", version.ref = "compose" }

""",
        "{{android_libraries}}" to if (normalizedTargets.contains(ANDROID)) {
            """androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }

"""
        } else {
            ""
        },
        "{{android_plugins}}" to if (normalizedTargets.contains(ANDROID)) {
            """
android-application = { id = "com.android.application", version.ref = "agp" }
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
            """.trimIndent()
        } else {
            ""
        },
        "{{android_root_plugins}}" to if (normalizedTargets.contains(ANDROID)) {
            """    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
"""
        } else {
            ""
        },
        "{{android_include}}" to if (normalizedTargets.contains(ANDROID)) """include(":$ANDROID_APP_MODULE")""" else "",
        "{{desktop_include}}" to if (normalizedTargets.contains(JVM)) """include(":$DESKTOP_APP_MODULE")""" else "",
        "{{web_include}}" to if (normalizedTargets.contains(WASM)) """include(":$WEB_APP_MODULE")""" else "",
        "{{android_properties}}" to if (normalizedTargets.contains(ANDROID)) {
            """#Android
android.nonTransitiveRClass=true
android.useAndroidX=true
"""
        } else {
            ""
        },
        "{{web_preload_task_wiring}}" to if (normalizedTargets.contains(WASM)) wasmPreloadTaskWiring() else "",
        "{{imports}}" to if (imports.isNotEmpty()) imports.joinToString("\n") + "\n" else "",
        "{{plugins}}" to "plugins {\n" + plugins.joinToString("\n") + "\n}",
        "{{kotlin_targets}}" to if (kotlinTargets.isNotEmpty()) kotlinTargets.joinToString("\n\n") + "\n" else "",
        "{{sourcesets}}" to """    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.composables.ui)
        }
    }""",
        "{{configuration_blocks}}" to if (normalizedTargets.contains(ANDROID)) {
            """
dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}
            """.trimIndent()
        } else {
            ""
        },
        "{{namespace}}" to packageName,
        "{{project_name}}" to projectName,
        "{{module_name}}" to moduleName,
        "{{shared_module_name}}" to moduleName,
        "{{app_name}}" to appName,
        "{{shared_module_accessor}}" to toProjectAccessorName(moduleName),
        "{{ios_binary_name}}" to toCamelCase(moduleName),
        "{{target_name}}" to "$IOS_APP_MODULE.app",
    )

    return replacements.entries.fold(content) { updated, (placeholder, value) ->
        updated.replace(placeholder, value)
    }.trim() + "\n"
}

private fun wasmPreloadTaskWiring(): String = """
subprojects {
    fun registerPreloadInjectionTask(
        distributionTarget: String,
        markerName: String,
        includeWasmArtifacts: Boolean,
    ) = tasks.register("inject${'$'}{distributionTarget.replaceFirstChar(Char::titlecase)}Preloads") {
        description = "Injects preload links for generated ${'$'}distributionTarget distribution artifacts."
        val distributionDir = layout.buildDirectory.dir("dist/${'$'}distributionTarget/productionExecutable")
        val preloadMarker = markerName
        val preloadWasmArtifacts = includeWasmArtifacts

        doLast {
            val distDir = distributionDir.get().asFile
            val indexFile = distDir.resolve("index.html")
            if (!indexFile.isFile) return@doLast

            val scriptPreloads = distDir
                .listFiles { file -> file.isFile && file.extension == "js" }
                .orEmpty()
                .sortedBy { it.name }
                .map { "  <link rel=\"preload\" href=\"${'$'}{it.name}\" as=\"script\">" }

            val artifactPreloads = if (preloadWasmArtifacts) {
                distDir
                    .listFiles { file -> file.isFile && file.extension == "wasm" }
                    .orEmpty()
                    .sortedBy { it.name }
                    .map {
                        "  <link rel=\"preload\" href=\"${'$'}{it.name}\" as=\"fetch\" type=\"application/wasm\" crossorigin>"
                    }
            } else {
                emptyList()
            }

            val preloadBlock = (scriptPreloads + artifactPreloads).joinToString(
                separator = "\n",
                prefix = "  <!-- ${'$'}preloadMarker:start -->\n",
                postfix = "\n  <!-- ${'$'}preloadMarker:end -->",
            )

            val existingPreloadBlock = Regex(
                pattern = "\\n?  <!-- ${'$'}preloadMarker:start -->.*?  <!-- ${'$'}preloadMarker:end -->\\n?",
                options = setOf(RegexOption.DOT_MATCHES_ALL),
            )
            val indexHtml = indexFile.readText().replace(existingPreloadBlock, "\n")
            val updatedIndexHtml = indexHtml.replaceFirst("</title>", "</title>\n${'$'}preloadBlock")
            indexFile.writeText(updatedIndexHtml)
        }
    }

    val injectWasmPreloads = registerPreloadInjectionTask(
        distributionTarget = "wasmJs",
        markerName = "wasm-preloads",
        includeWasmArtifacts = true,
    )

    tasks.matching { it.name == "wasmJsBrowserDistribution" }.configureEach {
        finalizedBy(injectWasmPreloads)
    }
}
""".trimIndent()

fun updateRootBuildFile(
    targetDir: String,
    targets: Set<String>,
) {
    val normalizedTargets = normalizeTargets(targets)
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
        if (!pluginsContent.contains("libs.plugins.jetbrains.compose") && !pluginsContent.contains("libs.plugins.kotlin.compose")) {
            requiredPlugins.add("    alias(libs.plugins.jetbrains.compose) apply false")
        }
        if (!pluginsContent.contains("libs.plugins.jetbrains.compose.compiler")) {
            // Only add compose compiler at root level if kotlin compose plugin is not already present
            if (!pluginsContent.contains("libs.plugins.kotlin.compose")) {
                requiredPlugins.add("    alias(libs.plugins.jetbrains.compose.compiler) apply false")
            }
        }
        if (normalizedTargets.contains(ANDROID) && !pluginsContent.contains("libs.plugins.android.application")) {
            requiredPlugins.add("    alias(libs.plugins.android.application) apply false")
        }
        if (normalizedTargets.contains(ANDROID) && !pluginsContent.contains("libs.plugins.android.kotlin.multiplatform.library")) {
            requiredPlugins.add("    alias(libs.plugins.android.kotlin.multiplatform.library) apply false")
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
        if (normalizedTargets.contains(ANDROID)) {
            requiredPlugins.add("    alias(libs.plugins.android.application) apply false")
            requiredPlugins.add("    alias(libs.plugins.android.kotlin.multiplatform.library) apply false")
        }
        requiredPlugins.add("}")

        // Add at the beginning of file
        requiredPlugins.reversed().forEach { line ->
            lines.add(0, line)
        }
        modified = true
    }

    if (modified) {
        content = lines.joinToString("\n")
        buildFile.writeText(content)
    }

    if (normalizedTargets.contains(WASM) && !content.contains("injectWasmPreloads")) {
        buildFile.writeText(buildFile.readText().trimEnd() + "\n\n" + wasmPreloadTaskWiring() + "\n")
    }
}

fun updateVersionCatalog(
    targetDir: String,
    targets: Set<String>,
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
    if (!hasVersionVariable(versionsSection, "kotlin")) {
        newVersions.add("kotlin = \"2.4.0\"")
    }
    if (!hasVersionVariable(versionsSection, "compose")) {
        newVersions.add("compose = \"1.11.1\"")
    }
    if (!hasVersionVariable(versionsSection, "composablesUi")) {
        newVersions.add("composablesUi = \"0.1.0\"")
    }

    // Add Android versions if android target is selected
    if (targets.contains("android")) {
        if (!hasVersionVariable(versionsSection, "agp")) newVersions.add("agp = \"9.2.1\"")
        if (!hasVersionVariable(versionsSection, "android-compileSdk")) newVersions.add("android-compileSdk = \"37\"")
        if (!hasVersionVariable(versionsSection, "android-minSdk")) newVersions.add("android-minSdk = \"23\"")
        if (!hasVersionVariable(versionsSection, "android-targetSdk")) newVersions.add("android-targetSdk = \"37\"")
        if (!hasVersionVariable(versionsSection, "activityCompose")) newVersions.add("activityCompose = \"1.13.0\"")
    }

    // Add required libraries if not present
    val newLibraries = mutableListOf<String>()
    if (!hasLibraryVariable(librariesSection, "composables-ui")) {
        newLibraries.add("composables-ui = { group = \"com.composables\", name = \"ui\", version.ref = \"composablesUi\" }")
    }
    if (!hasLibraryVariable(librariesSection, "compose-ui")) {
        newLibraries.add("compose-ui = { group = \"org.jetbrains.compose.ui\", name = \"ui\", version.ref = \"compose\" }")
    }
    if (!hasLibraryVariable(librariesSection, "compose-ui-tooling")) {
        newLibraries.add("compose-ui-tooling = { group = \"org.jetbrains.compose.ui\", name = \"ui-tooling\", version.ref = \"compose\" }")
    }
    if (!hasLibraryVariable(librariesSection, "compose-ui-tooling-preview")) {
        newLibraries.add("compose-ui-tooling-preview = { group = \"org.jetbrains.compose.ui\", name = \"ui-tooling-preview\", version.ref = \"compose\" }")
    }
    if (targets.contains("android") && !hasLibraryVariable(librariesSection, "androidx-activity-compose")) {
        newLibraries.add("androidx-activity-compose = { group = \"androidx.activity\", name = \"activity-compose\", version.ref = \"activityCompose\" }")
    }

    // Add required plugins if not present
    val newPlugins = mutableListOf<String>()
    if (!hasPluginVariable(pluginsSection, "jetbrains-kotlin-multiplatform")) {
        newPlugins.add("jetbrains-kotlin-multiplatform = { id = \"org.jetbrains.kotlin.multiplatform\", version.ref = \"kotlin\" }")
    }
    if (!hasPluginVariable(pluginsSection, "jetbrains-compose")) {
        newPlugins.add("jetbrains-compose = { id = \"org.jetbrains.compose\", version.ref = \"compose\" }")
    }
    if (!hasPluginVariable(pluginsSection, "jetbrains-compose-compiler")) {
        newPlugins.add("jetbrains-compose-compiler = { id = \"org.jetbrains.kotlin.plugin.compose\", version.ref = \"kotlin\" }")
    }
    if (targets.contains("android") && !hasPluginVariable(pluginsSection, "android-application")) {
        newPlugins.add("android-application = { id = \"com.android.application\", version.ref = \"agp\" }")
    }
    if (targets.contains("android") && !hasPluginVariable(pluginsSection, "android-kotlin-multiplatform-library")) {
        newPlugins.add("android-kotlin-multiplatform-library = { id = \"com.android.kotlin.multiplatform.library\", version.ref = \"agp\" }")
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

private fun hasVersionVariable(sectionContent: String, variableName: String): Boolean {
    // Check for exact version variable match: variableName = "version"
    val pattern = Regex("""^\s*$variableName\s*=""", RegexOption.MULTILINE)
    return pattern.containsMatchIn(sectionContent)
}

private fun hasLibraryVariable(sectionContent: String, variableName: String): Boolean {
    // Check for exact library variable match: variableName = { ... }
    val pattern = Regex("""^\s*$variableName\s*=""", RegexOption.MULTILINE)
    return pattern.containsMatchIn(sectionContent)
}

private fun hasPluginVariable(sectionContent: String, variableName: String): Boolean {
    // Check for exact plugin variable match: variableName = { ... }
    val pattern = Regex("""^\s*$variableName\s*=""", RegexOption.MULTILINE)
    return pattern.containsMatchIn(sectionContent)
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
    moduleName: String,
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
    targets: Set<String>,
) {
    val normalizedTargets = normalizeTargets(targets)
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
                            resources.add("$path/${relativePath.toResourcePath()}")
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
    val resources = listResources("/project/$SHARED_MODULE")
    resources.forEach { resourcePath ->
        var targetPath = resourcePath.removePrefix("/project/$SHARED_MODULE/")

        // Skip source set directories if corresponding target is not selected
        val isInsideAKotlinSourceSet = targetPath.startsWith("src/")
        if (isInsideAKotlinSourceSet) {
            val sourceSetType = targetPath.substringAfter("src/").substringBefore("/")
            when (sourceSetType) {
                "androidMain" -> if (!normalizedTargets.contains(ANDROID)) return@forEach
                "iosMain" -> if (!normalizedTargets.contains(IOS)) return@forEach
                "jvmMain" -> if (!normalizedTargets.contains(JVM)) return@forEach
                "wasmJsMain" -> if (!normalizedTargets.contains(WASM)) return@forEach
                "commonMain" -> Unit
                else -> error("Unknown target: $targetPath")
            }
        }

        // Replace org.example.project with the actual namespace in file paths
        targetPath = targetPath.replace("org/example/project", packageName.replace(".", "/"))
        targetPath = targetPath.replace("org/example", packageName.replace(".", "/"))

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
                val updatedContent = renderProjectTemplate(
                    content = content,
                    packageName = packageName,
                    moduleName = moduleName,
                    appName = appName,
                    targets = normalizedTargets,
                )
                if (content != updatedContent) {
                    file.writeText(updatedContent)
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
    moduleName: String,
) {
    val targetDir = File(targetDir, IOS_APP_MODULE)

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
                            resources.add("$path/${relativePath.toResourcePath()}")
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

    val resources = listResources("/project/$IOS_APP_MODULE")
    resources.forEach { resourcePath ->
        val targetPath = resourcePath.removePrefix("/project/$IOS_APP_MODULE/")
        val targetFile = targetDir.resolve(targetPath)
        copyResource(resourcePath, targetFile)

        // Replace placeholders in text files
        if (targetFile.name.endsWith(".swift") ||
            targetFile.name.endsWith(".h") ||
            targetFile.name.endsWith(".m") ||
            targetFile.name.endsWith(".pbxproj") ||
            targetFile.name.endsWith(".xcconfig")
        ) {
            try {
                val content = targetFile.readText()
                var updatedContent = content.replace("{{module_name}}", moduleName)
                updatedContent = updatedContent.replace("{{ios_binary_name}}", toCamelCase(moduleName))
                updatedContent = updatedContent.replace("{{target_name}}", "$IOS_APP_MODULE.app")
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

private fun getKotlinVersion(projectDir: File): String? {
    try {
        // Try to get Kotlin version from gradle.properties
        val gradleProperties = File(projectDir, "gradle.properties")
        if (gradleProperties.exists()) {
            val content = gradleProperties.readText()
            val kotlinVersionMatch = Regex("kotlin\\.version\\s*=\\s*([^\n\r]+)").find(content)
            if (kotlinVersionMatch != null) {
                return kotlinVersionMatch.groupValues[1].trim()
            }
        }

        // Try to get from libs.versions.toml
        val versionsToml = File(projectDir, "gradle/libs.versions.toml")
        if (versionsToml.exists()) {
            val content = versionsToml.readText()
            val kotlinVersionMatch = Regex("kotlin\\s*=\\s*\"?([^\"]+)\"?").find(content)
            if (kotlinVersionMatch != null) {
                return kotlinVersionMatch.groupValues[1].trim()
            }
        }

        // Try to run gradle and get the version
        val process = ProcessBuilder(gradleScript, "properties", "-q", "--no-daemon")
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()

        val versionMatch = Regex("kotlin\\.version\\s*=\\s*([^\n\r]+)").find(output)
        if (versionMatch != null) {
            return versionMatch.groupValues[1].trim()
        }
    } catch (e: Exception) {
        // Failed to get version, return null
    }

    return null
}

private fun isKotlinVersionSupported(version: String): Boolean {
    return try {
        val parts = version.split(".")
        if (parts.size >= 3) {
            val major = parts[0].toInt()
            val minor = parts[1].toInt()
            val patch = parts[2].toInt()

            // Check if version is at least 2.4.0
            if (major > 2) return true
            if (major < 2) return false
            if (minor > 4) return true
            if (minor < 4) return false
            if (patch >= 0) return true
            return false
        } else {
            // For versions like "2.2" or "2.2.0", assume they're too old
            false
        }
    } catch (e: Exception) {
        // Failed to parse version, assume it's not supported
        false
    }
}
