package com.composables.cli

import com.alexstyl.debugln.debugln
import com.alexstyl.debugln.infoln
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import java.io.File
import java.io.InputStream

val ANDROID = "android"
val JVM = "jvm"
val IOS = "ios"
val WEB = "web"

suspend fun main(args: Array<String>) {
    ComposablesCli()
        .subcommands(Init())
        .main(args)

//    cloneComposeApp(
//        targetDir = "/Users/alexstyl/projects/composables-cli",
//        dirName = "composeApp",
//        packageName = "com.composables",
//        appName = "The App",
//        targets = setOf(
//            ANDROID,
////            JVM,
////            IOS,
////            WEB
//        )
//    )
}

class ComposablesCli : CliktCommand(name = "composables") {
    override fun run() {}

    override fun help(context: Context) = """
        If you have any problems or need help, do not hesitate to ask for help at:
            https://github.com/composablehorizons/composables-cli
    """.trimIndent()
}

class Init : CliktCommand(
    "init"
) {
    override fun help(context: Context): String = """
        Creates a new Compose Multiplatform app.
    """.trimIndent()

    private val dirName by argument("directory", help = "Directory name for the new project").optional()

    override fun run() {
        val workingDir = System.getProperty("user.dir")
        val projectName = dirName.orEmpty()
        if (projectName.isBlank()) {
            debugln { "Please specify the project directory:" }
            infoln { "composables init <project-directory>" }
            debugln { "" }
            debugln { "For example:" }
            infoln { "composables init composeApp" }
            return
        }
        val target = File(workingDir).resolve(projectName)

        // Check if we can create the directory first
        if (target.exists()) {
            if (target.listFiles()?.isEmpty() == true) {
                target.deleteRecursively()
            } else {
                echo("The directory $projectName already exists and is not empty.")
                echo("Try a new directory name or delete the existing one before trying to create a new app.")
                return
            }
        }

        if (!target.mkdirs()) {
            echo("Failed to create directory $projectName")
            return
        }

        val appName = readAppName()
        val namespace = readNamespace()
        val targets = readTargets()

        cloneComposeApp(
            targetDir = workingDir,
            dirName = projectName,
            packageName = namespace,
            appName = appName,
            targets = targets
        )
    }

    private fun readNamespace(): String {
        while (true) {
            print("Enter package name (e.g., com.example.app): ")
            val namespace = readln().trim()

            if (namespace.isEmpty()) {
                echo("Package name cannot be empty")
                continue
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
            print("Enter app name (e.g., My App): ")
            val appName = readln().trim()

            if (appName.isEmpty()) {
                echo("App name cannot be empty")
                continue
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
                print("Android (y/n): ")
                val android = readln().trim().lowercase()
                if (android == "y" || android == "yes") {
                    targets.add(ANDROID)
                }
                break
            }

            while (true) {
                print("JVM (Desktop) (y/n): ")
                val jvm = readln().trim().lowercase()
                if (jvm == "y" || jvm == "yes") {
                    targets.add(JVM)
                }
                break
            }

            while (true) {
                print("iOS (y/n): ")
                val ios = readln().trim().lowercase()
                if (ios == "y" || ios == "yes") {
                    targets.add(IOS)
                }
                break
            }

            while (true) {
                print("Web (y/n): ")
                val web = readln().trim().lowercase()
                if (web == "y" || web == "yes") {
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

fun cloneComposeApp(
    targetDir: String,
    dirName: String,
    packageName: String,
    appName: String,
    targets: Set<String>
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
                    val jarFile = java.util.jar.JarFile(File(jarPath.substringAfter("file:")))
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
        if (!targets.contains("ios") && targetPath.startsWith("iosApp/")) {
            return@forEach
        }

        // Skip source set directories if corresponding target is not selected
        if (targetPath.startsWith("composeApp/src/")) {
            val sourceSetType = targetPath.substringAfter("composeApp/src/").substringBefore("/")
            when (sourceSetType) {
                "androidMain" -> if (!targets.contains("android")) return@forEach
                "iosMain" -> if (!targets.contains("ios")) return@forEach
                "jvmMain" -> if (!targets.contains("jvm")) return@forEach
                "jsMain" -> if (!targets.contains("web")) return@forEach
                "wasmJsMain" -> if (!targets.contains("web")) return@forEach
                "webMain" -> if (!targets.contains("web")) return@forEach
            }
        }

        // Skip webpack.config.d directory if web target is not selected
        if (!targets.contains("web") && targetPath.startsWith("composeApp/webpack.config.d/")) {
            return@forEach
        }

        // Replace org.example.project with the actual namespace in file paths
        targetPath = targetPath.replace("org/example/project", packageName.replace(".", "/"))

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
                    kotlinTargets.add("""    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }""")
                }
                if (targets.contains("ios")) {
                    kotlinTargets.add("""    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }""")
                }
                if (targets.contains("jvm")) {
                    kotlinTargets.add("    jvm()")
                }
                if (targets.contains("web")) {
                    kotlinTargets.add("""    js {
        browser()
        binaries.executable()
    }""")
                    kotlinTargets.add("""    @OptIn(ExperimentalWasmDsl::class)
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
    }""")
                }
                val kotlinTargetsBlock = if (kotlinTargets.isNotEmpty()) kotlinTargets.joinToString("\n\n") + "\n" else ""

                // Build sourcesets block
                val sourcesets = mutableListOf<String>()
                sourcesets.add("""    sourceSets {
        commonMain.dependencies {
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(compose.material3)
        }""")

                if (targets.contains("jvm")) {
                    sourcesets.add("""        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }""")
                }
                if (targets.contains("android")) {
                    sourcesets.add("""        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activitycompose)
        }""")
                }
                sourcesets.add("    }")
                val sourcesetsBlock = sourcesets.joinToString("\n")

                // Build configuration blocks
                val configurations = mutableListOf<String>()
                if (targets.contains("android")) {
                    configurations.add("""android {
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
}""")
                }
                if (targets.contains("jvm")) {
                    configurations.add("""compose.desktop {
    application {
        mainClass = "{{namespace}}.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "{{namespace}}"
            packageVersion = "1.0.0"
        }
    }
}""")
                }
                val configurationBlocksBlock = if (configurations.isNotEmpty()) configurations.joinToString("\n\n") else ""

                val composeDesktop = if (targets.contains("jvm")) """compose.desktop {
    application {
        mainClass = "{{namespace}}.MainKt"

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
                if (content != updatedContent) {
                    file.writeText(updatedContent.trim()+"\n")
                }
            } catch (e: Exception) {
                // If we can't read as text, skip this file
                debugln { "Skipping binary file: ${file.name}" }
            }
        }
    }

    debugln { "Success! Your new Compose app is ready at ${target.absolutePath}" }
    debugln { "Start by typing:" }
    infoln { "" }
    infoln { "\tcd $dirName" }
    infoln { "\t./gradlew run" }
    infoln { "" }
    debugln { "Happy coding!" }
}
