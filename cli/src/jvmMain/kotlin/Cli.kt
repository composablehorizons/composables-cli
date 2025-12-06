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

suspend fun main(args: Array<String>) {
    ComposablesCli()
        .subcommands(Init())
        .main(args)
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

        val namespace = readNamespace()
        val appName = readAppName()

        cloneComposeApp(targetDir = workingDir, dirName = projectName, packageName = namespace, appName = appName)
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
            print("Enter app name (e.g., MyAwesomeApp): ")
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
    appName: String
) {
    val target = File(targetDir).resolve(dirName)

    fun copyResource(resourcePath: String, targetFile: File) {
        val inputStream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
        if (inputStream != null) {
            targetFile.parentFile.mkdirs()
            inputStream.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Set executable permissions for scripts
            if (targetFile.name == "gradlew") {
                targetFile.setExecutable(true)
            }
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
                        val relativePath = file.relativeTo(dir)
                        resources.add("$path/${relativePath.path}")
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

        // Replace org.example.project with the actual namespace in file paths
        targetPath = targetPath.replace("org/example/project", packageName.replace(".", "/"))

        val targetFile = target.resolve(targetPath)
        copyResource(resourcePath, targetFile)
    }

    // Replace placeholders in all files
    target.walkTopDown().forEach { file ->
        if (file.isFile) {
            val content = file.readText()
            var updatedContent = content.replace("{{namespace}}", packageName)
            updatedContent = updatedContent.replace("{{app_name}}", appName)
            if (content != updatedContent) {
                file.writeText(updatedContent)
            }
        }
    }

    debugln { "Success! Created Compose app at ${target.absolutePath}" }
    debugln { "Start by typing:" }
    infoln { "" }
    infoln { "\tcd $dirName" }
    infoln { "\t./gradlew run" }
    infoln { "" }
    debugln { "Happy coding!" }
}
