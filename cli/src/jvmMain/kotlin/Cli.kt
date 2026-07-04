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
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.versionOption
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.jar.JarFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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

private fun normalizeTargets(targets: Set<String>): LinkedHashSet<String> =
    linkedSetOf<String>().apply { addAll(targets) }

private val docsHttpClient: HttpClient = HttpClient.newHttpClient()
private val json = Json { prettyPrint = true }

internal fun docsBaseUrl(): String =
    (System.getenv("COMPOSABLES_DOCS_BASE_URL")
            ?: System.getProperty("composables.docs.baseUrl")
            ?: "https://composables.com")
        .trimEnd('/')

internal fun docsApiUrl(
    endpoint: String,
    queryParameters: Map<String, String> = emptyMap(),
): String {
  val normalizedEndpoint = endpoint.trim().trimStart('/')
  val query =
      queryParameters.entries.joinToString("&") { (key, value) ->
        "${urlEncode(key)}=${urlEncode(value)}"
      }
  return buildString {
    append(docsBaseUrl())
    append("/api/composables-ui-docs/")
    append(normalizedEndpoint)
    if (query.isNotEmpty()) {
      append('?')
      append(query)
    }
  }
}

internal fun docsMarkdownUrl(slug: String): String = buildString {
  append(docsBaseUrl())
  append("/ui/docs/")
  append(urlEncode(slug.trim()))
  append(".md")
}

internal fun mcpUrl(): String = buildString {
  append(docsBaseUrl())
  append("/mcp")
}

private fun fetchUrl(url: String): String {
  val request =
      HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Accept", "application/json")
          .GET()
          .build()

  val response =
      try {
        docsHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
      } catch (error: Exception) {
        throw UsageError("Failed to reach Composables UI docs at $url: ${error.message}")
      }

  if (response.statusCode() !in 200..299) {
    val body = response.body().trim()
    val detail = if (body.isEmpty()) "No response body returned." else body
    throw UsageError("Docs request failed (${response.statusCode()}) for $url\n$detail")
  }

  return response.body()
}

private fun fetchDocsApi(
    endpoint: String,
    queryParameters: Map<String, String> = emptyMap(),
): String = fetchUrl(docsApiUrl(endpoint, queryParameters))

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private data class ProjectInstruction(
    val label: String,
    val detail: String? = null,
    val command: String? = null,
)

private data class ProjectConventions(
    val kotlinMultiplatformPlugin: String,
    val composePlugin: String,
    val composeCompilerPlugin: String,
    val androidApplicationPlugin: String,
    val androidKotlinMultiplatformLibraryPlugin: String,
    val usesTypeSafeProjectAccessors: Boolean,
    val composeUiDependency: String,
    val composablesUiDependency: String,
    val composablesIconsLucideDependency: String,
    val composablesUriPainterDependency: String,
    val androidxActivityComposeDependency: String,
    val androidCompileSdkExpression: String,
    val androidMinSdkExpression: String,
    val androidTargetSdkExpression: String,
)

private fun buildProjectInstructions(
    targets: Set<String>,
    gradleCommand: String,
): List<ProjectInstruction> {
  val normalizedTargets = normalizeTargets(targets)
  return buildList {
    if (normalizedTargets.contains(JVM)) {
      add(
          ProjectInstruction(
              label = "JVM", command = "$gradleCommand :$DESKTOP_APP_MODULE:hotRunJvm --auto"))
    }
    if (normalizedTargets.contains(ANDROID)) {
      add(
          ProjectInstruction(
              label = "Android",
              detail =
                  "open the project in Android Studio and run the `$ANDROID_APP_MODULE` app on a device or emulator",
          ),
      )
      add(
          ProjectInstruction(
              label = "Android install from terminal",
              command = "$gradleCommand :$ANDROID_APP_MODULE:installDebug"))
    }
    if (normalizedTargets.contains(IOS)) {
      add(
          ProjectInstruction(
              label = "iOS",
              detail =
                  "open `$IOS_APP_MODULE/$IOS_APP_MODULE.xcodeproj` in Xcode and run the app on a simulator or device",
          ),
      )
    }
    if (normalizedTargets.contains(WASM)) {
      add(
          ProjectInstruction(
              label = "Wasm",
              command = "$gradleCommand :$WEB_APP_MODULE:wasmJsBrowserDevelopmentRun"))
    }
  }
}

internal fun buildProjectStartCommand(
    targets: Set<String>,
    gradleCommand: String,
): String =
    buildProjectInstructions(
            targets = targets,
            gradleCommand = gradleCommand,
        )
        .firstOrNull { it.command != null }
        ?.command ?: "$gradleCommand build"

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
      )
      .forEach { instruction ->
        val description = instruction.command?.let { "`$it`" } ?: instruction.detail
        lines += "- ${instruction.label}: $description"
      }

  return lines.joinToString("\n") + "\n"
}

suspend fun main(args: Array<String>) {
  ComposablesCli()
      .subcommands(
          Init(),
          Add().subcommands(AddModule()),
          Docs().subcommands(DocsList(), DocsSearch(), DocsGet()),
          Install().subcommands(InstallMcp()))
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

  override fun run() {}

  override fun help(context: Context) =
      """
        If you have any problems or need help, do not hesitate to ask for help at:
            https://github.com/composablehorizons/composables-cli
    """
          .trimIndent()
}

class Init : CliktCommand("init") {
  override fun help(context: Context): String =
      """
        Creates a new Compose Multiplatform app in the specified <directory> path.
    """
          .trimIndent()

  private val directory by
      argument("directory", help = "The directory path to create the new app in").optional()
  private val packageName by option("--package", help = "The package name for the generated app")
  private val appName by option("--app-name", help = "The display name for the generated app")
  private val targetsInput by
      option("--targets", help = "Comma-separated targets: android,jvm,ios,wasm")
  private val overwrite by
      option("--overwrite", help = "Overwrite an existing target directory").flag(default = false)

  override fun run() {
    val anyExplicitInput =
        directory != null ||
            packageName != null ||
            appName != null ||
            targetsInput != null ||
            overwrite
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
        if (error::class.simpleName == "ReadAfterEOFException" ||
            error.message?.contains("EOF has already been reached") == true) {
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
        throw UsageError(
            "When using init non-interactively, specify all required inputs. Missing: ${missingInputs.joinToString(", ")}")
      }

      resolvedPackageName = packageName!!
      resolvedAppName = appName!!

      if (!isValidPackageName(resolvedPackageName)) {
        throw UsageError(
            "Invalid package name. Must be a valid Java package name (e.g., com.example.app)")
      }

      if (!isValidAppName(resolvedAppName)) {
        throw UsageError("Invalid app name. Must contain at least one letter or digit")
      }

      targets =
          try {
            parseTargets(targetsInput!!)
          } catch (error: IllegalArgumentException) {
            throw UsageError(error.message ?: "Invalid targets")
          }
      target =
          resolveTargetDirectory(
              workingDir = workingDir,
              projectPath = directory!!,
          )
      validateInitTargetDirectory(target, overwrite)
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

class Add : CliktCommand("add") {
  override fun help(context: Context): String =
      """
        Adds code to an existing Compose project.
    """
          .trimIndent()

  override fun run() {}
}

class Docs : CliktCommand("docs") {
  override fun help(context: Context): String =
      """
        Reads Composables UI documentation in a machine-friendly format.
    """
          .trimIndent()

  override fun run() {}
}

class DocsList : CliktCommand("list") {
  override fun help(context: Context): String =
      """
        Lists all Composables UI documentation pages.
    """
          .trimIndent()

  override fun run() {
    echo(fetchDocsApi("list"))
  }
}

class DocsSearch : CliktCommand("search") {
  override fun help(context: Context): String =
      """
        Searches Composables UI documentation pages.
    """
          .trimIndent()

  private val query by argument("query", help = "Search query")

  override fun run() {
    echo(fetchDocsApi("search", mapOf("q" to query)))
  }
}

class DocsGet : CliktCommand("get") {
  override fun help(context: Context): String =
      """
        Gets one Composables UI documentation page by slug.
    """
          .trimIndent()

  private val slug by argument("slug", help = "Documentation page slug")

  override fun run() {
    echo(fetchUrl(docsMarkdownUrl(slug)))
  }
}

class Install : CliktCommand("install") {
  override fun help(context: Context): String =
      """
        Installs Composables integrations for supported clients.
    """
          .trimIndent()

  override fun run() {
    if (currentContext.invokedSubcommand == null) {
      echoFormattedHelp()
    }
  }
}

class InstallMcp : CliktCommand("mcp") {
  override fun help(context: Context): String =
      """
        Installs Composables MCP for supported clients.
    """
          .trimIndent()

  private val client by option("--client", help = "Client to install for").required()
  private val overwrite by
      option("--overwrite", help = "Overwrite an existing conflicting Composables MCP server entry")
          .flag(default = false)

  override fun run() {
    when (client.trim().lowercase()) {
      "android-studio" -> {
        val configDir =
            findAndroidStudioConfigDirectory()
                ?: throw UsageError(
                    "Could not find an Android Studio configuration directory. Open Android Studio at least once before running this command.",
                )
        when (val result =
            installAndroidStudioMcpServer(configDir, serverUrl = mcpUrl(), overwrite = overwrite)) {
          is AndroidStudioMcpInstallResult.AlreadyInstalled -> {
            echo("Composables MCP is already installed for Android Studio.")
            echo("Using ${result.configFile.absolutePath}")
          }

          is AndroidStudioMcpInstallResult.Installed -> {
            echo("Installed Composables MCP for Android Studio.")
            echo("Updated ${result.configFile.absolutePath}")
          }
        }
      }

      else -> throw UsageError("Unsupported client '$client'. Available clients: android-studio")
    }
  }
}

class AddModule : CliktCommand("module") {
  override fun help(context: Context): String =
      """
        Adds a new Compose app module group to the current Gradle project.
    """
          .trimIndent()

  private val path by
      argument("path", help = "The path to create the new module group in").optional()
  private val packageName by
      option("--package", help = "The package name for the generated app module group")
  private val appName by option("--app-name", help = "The display name for the generated app")
  private val targetsInput by
      option("--targets", help = "Comma-separated targets: android,jvm,ios,wasm")
  private val overwrite by
      option("--overwrite", help = "Overwrite an existing target directory").flag(default = false)

  override fun run() {
    val projectRoot = File(System.getProperty("user.dir"))
    validateExistingGradleProject(projectRoot)

    val kotlinVersion = getKotlinVersion(projectRoot)
    if (kotlinVersion != null && !isKotlinVersionSupported(kotlinVersion)) {
      throw UsageError(
          "Composables CLI can only be used on projects running Kotlin 2.4.0 and above. " +
              "This project uses Kotlin $kotlinVersion. Use a newer version of Kotlin to use the Composables CLI.",
      )
    }

    val anyExplicitInput =
        path != null || packageName != null || appName != null || targetsInput != null || overwrite

    val target: File
    val resolvedPackageName: String
    val resolvedAppName: String
    val targets: Set<String>

    if (!anyExplicitInput) {
      try {
        target = readNewModuleDirectory(projectRoot)
        resolvedPackageName = readNamespace()
        resolvedAppName = readAppName()
        targets = readTargets()
      } catch (error: RuntimeException) {
        if (error::class.simpleName == "ReadAfterEOFException" ||
            error.message?.contains("EOF has already been reached") == true) {
          throw UsageError(
              "Interactive mode requires stdin. Pass <path>, --package, --app-name, and --targets for non-interactive use.",
          )
        }
        throw error
      }
    } else {
      val missingInputs = buildList {
        if (path == null) add("<path>")
        if (packageName == null) add("--package")
        if (appName == null) add("--app-name")
        if (targetsInput == null) add("--targets")
      }
      if (missingInputs.isNotEmpty()) {
        throw UsageError(
            "When using add module non-interactively, specify all required inputs. Missing: ${missingInputs.joinToString(", ")}")
      }

      resolvedPackageName = packageName!!
      resolvedAppName = appName!!

      if (!isValidPackageName(resolvedPackageName)) {
        throw UsageError(
            "Invalid package name. Must be a valid Java package name (e.g., com.example.app)")
      }

      if (!isValidAppName(resolvedAppName)) {
        throw UsageError("Invalid app name. Must contain at least one letter or digit")
      }

      targets =
          try {
            parseTargets(targetsInput!!)
          } catch (error: IllegalArgumentException) {
            throw UsageError(error.message ?: "Invalid targets")
          }

      target =
          resolveTargetDirectory(
              workingDir = projectRoot.absolutePath,
              projectPath = path!!,
          )
      validateModuleTargetDirectory(projectRoot, target, overwrite)
    }

    val modulePath = toRelativeModulePath(projectRoot, target)
    val conventions = inferProjectConventions(projectRoot, targets)
    val includedModules =
        createModuleGroup(
            projectRoot = projectRoot,
            appRootDir = target,
            packageName = resolvedPackageName,
            appName = resolvedAppName,
            targets = targets,
            conventions = conventions,
        )
    addModuleToSettings(projectRoot, includedModules)

    infoln { "" }
    infoln { "App Module Configuration:" }
    infoln { "\tApp Name: $resolvedAppName" }
    infoln { "\tPackage: $resolvedPackageName" }
    infoln { "\tModule: $modulePath" }
    infoln { "\tTargets: ${targets.joinToString(", ")}" }
    infoln { "" }
    debugln { "Success! Your new Compose app module group is ready at ${target.absolutePath}" }
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

internal sealed interface AndroidStudioMcpInstallResult {
  val configFile: File

  data class Installed(override val configFile: File) : AndroidStudioMcpInstallResult

  data class AlreadyInstalled(override val configFile: File) : AndroidStudioMcpInstallResult
}

internal fun installAndroidStudioMcpServer(
    configDir: File,
    serverUrl: String,
    serverName: String = "composables",
    overwrite: Boolean = false,
): AndroidStudioMcpInstallResult {
  if (!configDir.exists() && !configDir.mkdirs()) {
    throw UsageError(
        "Failed to create Android Studio config directory at ${configDir.absolutePath}")
  }
  if (!configDir.isDirectory) {
    throw UsageError("Android Studio config path ${configDir.absolutePath} is not a directory.")
  }

  val mcpConfigFile = File(configDir, "mcp.json")
  val existingRoot = readJsonObjectOrEmpty(mcpConfigFile)
  val existingServers =
      readNestedJsonObject(existingRoot, "mcpServers", mcpConfigFile) ?: JsonObject(emptyMap())
  val existingServer = existingServers[serverName]?.jsonObject
  val existingUrl =
      existingServer?.get("httpUrl")?.let { element ->
        (element as? JsonPrimitive)?.contentOrNull?.trim()
      }

  if (existingUrl == serverUrl) {
    return AndroidStudioMcpInstallResult.AlreadyInstalled(mcpConfigFile)
  }
  if (existingServer != null && !overwrite) {
    val configuredUrl = existingUrl ?: "an unsupported configuration"
    throw UsageError(
        "Android Studio already has a '$serverName' MCP server configured with $configuredUrl. " +
            "Use --overwrite to replace it.",
    )
  }

  val updatedServers = buildJsonObject {
    existingServers.forEach { (key, value) -> put(key, value) }
    put(
        serverName,
        buildJsonObject { put("httpUrl", JsonPrimitive(serverUrl)) },
    )
  }
  val updatedRoot = buildJsonObject {
    existingRoot.forEach { (key, value) ->
      if (key != "mcpServers") {
        put(key, value)
      }
    }
    put("mcpServers", updatedServers)
  }
  mcpConfigFile.writeText(json.encodeToString(JsonObject.serializer(), updatedRoot) + "\n")
  return AndroidStudioMcpInstallResult.Installed(mcpConfigFile)
}

internal fun findAndroidStudioConfigDirectory(): File? {
  val override = System.getProperty("composables.androidStudio.configDir")?.trim().orEmpty()
  if (override.isNotEmpty()) {
    return File(override)
  }

  val homeDir = File(System.getProperty("user.home"))
  val candidates =
      when {
        isWindows() ->
            listOfNotNull(
                System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { File(it, "Google") },
            )
        isMacOs() -> listOf(File(homeDir, "Library/Application Support/Google"))
        else -> listOf(File(homeDir, ".config/Google"))
      }

  return candidates
      .asSequence()
      .filter { it.isDirectory }
      .flatMap { baseDir ->
        baseDir.listFiles().orEmpty().asSequence().filter {
          it.isDirectory &&
              (it.name.startsWith("AndroidStudio") || it.name.startsWith("AndroidStudioPreview"))
        }
      }
      .maxWithOrNull { left, right ->
        compareAndroidStudioVersions(left.name, right.name).takeIf { it != 0 }
            ?: left.name.compareTo(right.name)
      }
}

private fun readJsonObjectOrEmpty(file: File): JsonObject {
  if (!file.exists()) {
    return JsonObject(emptyMap())
  }

  val content = file.readText().trim()
  if (content.isEmpty()) {
    return JsonObject(emptyMap())
  }

  val element =
      try {
        json.parseToJsonElement(content)
      } catch (error: Exception) {
        throw UsageError("Failed to read ${file.absolutePath}: ${error.message}")
      }

  return element as? JsonObject
      ?: throw UsageError("Expected ${file.absolutePath} to contain a JSON object.")
}

private fun readNestedJsonObject(root: JsonObject, key: String, sourceFile: File): JsonObject? {
  val value = root[key] ?: return null
  return value as? JsonObject
      ?: throw UsageError("Expected '$key' in ${sourceFile.absolutePath} to be a JSON object.")
}

private fun parseAndroidStudioVersion(name: String): List<Int> {
  val version =
      name
          .removePrefix("AndroidStudioPreview")
          .removePrefix("AndroidStudio")
          .split('.')
          .mapNotNull { part -> part.toIntOrNull() }

  return if (version.isEmpty()) listOf(0) else version
}

private fun compareAndroidStudioVersions(left: String, right: String): Int {
  val leftParts = parseAndroidStudioVersion(left)
  val rightParts = parseAndroidStudioVersion(right)
  val maxSize = maxOf(leftParts.size, rightParts.size)

  for (index in 0 until maxSize) {
    val leftPart = leftParts.getOrElse(index) { 0 }
    val rightPart = rightParts.getOrElse(index) { 0 }
    if (leftPart != rightPart) {
      return leftPart.compareTo(rightPart)
    }
  }

  return 0
}

private fun isMacOs(): Boolean = System.getProperty("os.name").contains("Mac", ignoreCase = true)

private fun isWindows(): Boolean =
    System.getProperty("os.name").contains("Windows", ignoreCase = true)

internal fun validateInitTargetDirectory(target: File, overwrite: Boolean) {
  when {
    target.exists() && overwrite -> {
      if (!target.deleteRecursively()) {
        throw UsageError("Failed to overwrite existing directory at ${target.absolutePath}")
      }
    }

    target.exists() && target.isFile -> {
      throw UsageError(
          "Target path ${target.absolutePath} is a file. Choose a different directory or use --overwrite.")
    }

    target.exists() && target.listFiles()?.isNotEmpty() == true -> {
      throw UsageError(
          "Target directory ${target.absolutePath} already exists and is not empty. Use --overwrite to replace it.")
    }

    target.exists() && target.listFiles()?.isEmpty() == true -> {
      target.deleteRecursively()
    }
  }
}

private fun validateExistingGradleProject(projectRoot: File) {
  if (!File(projectRoot, "settings.gradle.kts").exists()) {
    throw UsageError(
        "This command must be run from the root of an existing Gradle project with a settings.gradle.kts file.")
  }
}

private fun validateModuleTargetDirectory(
    projectRoot: File,
    target: File,
    overwrite: Boolean,
) {
  if (!target.toPath().normalize().startsWith(projectRoot.toPath().normalize())) {
    throw UsageError("Module path ${target.absolutePath} must be inside the current project.")
  }

  if (target.toPath().normalize() == projectRoot.toPath().normalize()) {
    throw UsageError("Module path must point to a subdirectory inside the current project.")
  }

  val relativePath = projectRoot.toPath().normalize().relativize(target.toPath().normalize())
  if (relativePath.any { !isValidModuleName(it.toString()) }) {
    throw UsageError(
        "Invalid module path. Each path segment must start with a letter and contain only letters, digits, hyphens, or underscores")
  }

  when {
    target.exists() && overwrite -> {
      if (!target.deleteRecursively()) {
        throw UsageError("Failed to overwrite existing directory at ${target.absolutePath}")
      }
    }

    target.exists() && target.isFile -> {
      throw UsageError(
          "Target path ${target.absolutePath} is a file. Choose a different directory or use --overwrite.")
    }

    target.exists() && target.listFiles()?.isNotEmpty() == true -> {
      throw UsageError(
          "Target directory ${target.absolutePath} already exists and is not empty. Use --overwrite to replace it.")
    }

    target.exists() && target.listFiles()?.isEmpty() == true -> {
      target.deleteRecursively()
    }
  }
}

private fun readNewModuleDirectory(projectRoot: File): File {
  while (true) {
    print("Enter module directory: ")
    val projectPath = readln().trim()
    if (projectPath.isBlank()) {
      println("Module directory is required.")
      continue
    }

    val target =
        resolveTargetDirectory(workingDir = projectRoot.absolutePath, projectPath = projectPath)
    try {
      validateModuleTargetDirectory(projectRoot, target, overwrite = false)
      return target
    } catch (error: UsageError) {
      println(error.message)
    }
  }
}

private fun toRelativeModulePath(projectRoot: File, target: File): String =
    projectRoot.toPath().normalize().relativize(target.toPath().normalize()).joinToString("/") {
      it.toString()
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
        println(
            "Target directory ${target.absolutePath} already exists and is not empty. Choose a different directory.")
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
  val targets = targetsInput.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

  if (targets.isEmpty()) {
    throw IllegalArgumentException(
        "At least one target is required. Use --targets android,jvm,ios,wasm")
  }

  val invalidTargets = targets.filterNot { it in validTargets }
  if (invalidTargets.isNotEmpty()) {
    throw IllegalArgumentException(
        "Unknown targets: ${invalidTargets.joinToString(", ")}. Available targets: android, jvm, ios, wasm",
    )
  }

  return linkedSetOf<String>().apply { addAll(targets) }
}

private fun toCamelCase(input: String): String =
    input
        .split(Regex("[-_]"))
        .mapIndexed { index, part ->
          if (index == 0) {
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
          } else {
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
          }
        }
        .joinToString("")

private fun toProjectAccessorName(input: String): String =
    toCamelCase(input).replaceFirstChar { if (it.isUpperCase()) it.lowercase() else it.toString() }

private fun toProjectAccessorPath(modulePath: String): String =
    modulePath
        .trim(':')
        .split(':', '/')
        .filter { it.isNotEmpty() }
        .joinToString(".") { toProjectAccessorName(it) }

private fun toNamespaceSegment(input: String): String = toProjectAccessorName(input)

private fun listResourceFiles(path: String): List<String> {
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

private fun copyBundledResource(resourcePath: String, targetFile: File) {
  val inputStream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
  if (inputStream != null) {
    targetFile.parentFile?.mkdirs()
    inputStream.use { input -> targetFile.outputStream().use { output -> input.copyTo(output) } }
  } else {
    error("Resource not found: $resourcePath")
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
      val actualTargetFile =
          if (targetFile.name.endsWith(".jarX")) {
            File(targetFile.parent, targetFile.nameWithoutExtension + ".jar")
          } else {
            targetFile
          }

      inputStream.use { input ->
        actualTargetFile.outputStream().use { output -> input.copyTo(output) }
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
          file.name.endsWith(".class")) {
        return@forEach
      }

      try {
        val content = file.readText()
        val updatedContent =
            renderProjectTemplate(
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

  File(target, "README.md")
      .writeText(
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
    conventions: ProjectConventions? = null,
): String {
  val normalizedTargets = normalizeTargets(targets)
  val sharedModuleNamespace = toNamespaceSegment(moduleName)
  val imports = buildList {
    if (normalizedTargets.contains(ANDROID)) add("import org.jetbrains.kotlin.gradle.dsl.JvmTarget")
    if (normalizedTargets.contains(WASM))
        add("import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl")
  }
  val plugins = buildList {
    add(
        "  alias(${conventions?.kotlinMultiplatformPlugin ?: "libs.plugins.jetbrains.kotlin.multiplatform"})")
    add("  alias(${conventions?.composePlugin ?: "libs.plugins.jetbrains.compose"})")
    if (!content.contains("libs.plugins.kotlin.compose")) {
      add(
          "  alias(${conventions?.composeCompilerPlugin ?: "libs.plugins.jetbrains.compose.compiler"})")
    }
    if (normalizedTargets.contains(ANDROID)) {
      add(
          "  alias(${conventions?.androidKotlinMultiplatformLibraryPlugin ?: "libs.plugins.android.kotlin.multiplatform.library"})")
    }
  }
  val kotlinTargets = buildList {
    if (normalizedTargets.contains(ANDROID)) {
      add(
          """  android {
    namespace = "{{namespace}}.$sharedModuleNamespace"
    compileSdk = ${conventions?.androidCompileSdkExpression ?: "libs.versions.android.compileSdk.get().toInt()"}
    minSdk = ${conventions?.androidMinSdkExpression ?: "libs.versions.android.minSdk.get().toInt()"}
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
          """  listOf(
    iosArm64(),
    iosSimulatorArm64(),
  ).forEach { iosTarget ->
    iosTarget.binaries.framework {
      baseName = "${toCamelCase(moduleName)}"
      isStatic = true
    }
  }""",
      )
    }
    if (normalizedTargets.contains(JVM)) add("  jvm()")
    if (normalizedTargets.contains(WASM)) {
      add(
          """  @OptIn(ExperimentalWasmDsl::class)
  wasmJs {
    browser()
  }""",
      )
    }
  }

  val replacements =
      linkedMapOf(
          "{{android_versions}}" to
              if (normalizedTargets.contains(ANDROID)) {
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
          "{{compose_libraries}}" to
              """compose-ui = { group = "org.jetbrains.compose.ui", name = "ui", version.ref = "compose" }
compose-ui-tooling = { group = "org.jetbrains.compose.ui", name = "ui-tooling", version.ref = "compose" }
compose-ui-tooling-preview = { group = "org.jetbrains.compose.ui", name = "ui-tooling-preview", version.ref = "compose" }

""",
          "{{android_libraries}}" to
              if (normalizedTargets.contains(ANDROID)) {
                """androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }

"""
              } else {
                ""
              },
          "{{android_plugins}}" to
              if (normalizedTargets.contains(ANDROID)) {
                """
android-application = { id = "com.android.application", version.ref = "agp" }
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
            """
                    .trimIndent()
              } else {
                ""
              },
          "{{android_root_plugins}}" to
              if (normalizedTargets.contains(ANDROID)) {
                """  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.kotlin.multiplatform.library) apply false
"""
              } else {
                ""
              },
          "{{android_include}}" to
              if (normalizedTargets.contains(ANDROID)) """include(":$ANDROID_APP_MODULE")"""
              else "",
          "{{desktop_include}}" to
              if (normalizedTargets.contains(JVM)) """include(":$DESKTOP_APP_MODULE")""" else "",
          "{{web_include}}" to
              if (normalizedTargets.contains(WASM)) """include(":$WEB_APP_MODULE")""" else "",
          "{{android_properties}}" to
              if (normalizedTargets.contains(ANDROID)) {
                """#Android
android.nonTransitiveRClass=true
android.useAndroidX=true
"""
              } else {
                ""
              },
          "{{web_preload_task_wiring}}" to
              if (normalizedTargets.contains(WASM)) wasmPreloadTaskWiring() else "",
          "{{imports}}" to if (imports.isNotEmpty()) imports.joinToString("\n") + "\n" else "",
          "{{plugins}}" to "plugins {\n" + plugins.joinToString("\n") + "\n}",
          "{{kotlin_targets}}" to
              if (kotlinTargets.isNotEmpty()) kotlinTargets.joinToString("\n\n") + "\n" else "",
          "{{sourcesets}}" to
              if (conventions == null) {
                """  sourceSets {
    commonMain.dependencies {
      implementation(libs.compose.ui.tooling.preview)
      implementation(libs.composables.icons.lucide)
      implementation(libs.composables.uri.painter)
      implementation(libs.composables.ui)
    }
  }"""
              } else {
                """  sourceSets {
    commonMain.dependencies {
      implementation(${conventions.composablesIconsLucideDependency})
      implementation(${conventions.composablesUriPainterDependency})
      implementation(${conventions.composablesUiDependency})
    }
  }"""
              },
          "{{configuration_blocks}}" to
              if (normalizedTargets.contains(ANDROID) && conventions == null) {
                """
dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}
            """
                    .trimIndent()
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

  return replacements.entries
      .fold(content) { updated, (placeholder, value) -> updated.replace(placeholder, value) }
      .trim() + "\n"
}

private fun wasmPreloadTaskWiring(): String =
    """
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
"""
        .trimIndent()

private fun moduleWasmPreloadTaskWiring(): String =
    """
fun registerPreloadInjectionTask(
    distributionTarget: String,
    markerName: String,
    includeWasmArtifacts: Boolean,
) = tasks.run {
    val taskName = "inject${'$'}{distributionTarget.replaceFirstChar(Char::titlecase)}Preloads"
    if (taskName in names) {
        named(taskName)
    } else {
        register(taskName) {
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
"""
        .trimIndent()

private fun stripPreviewSupport(content: String): String =
    content
        .replace(Regex("""import androidx\.compose\.ui\.tooling\.preview\.Preview\r?\n"""), "")
        .replace(
            Regex(
                """\r?\n@Preview\r?\n@Composable\r?\nfun AppPreview\(\) \{\r?\n\s*App\(\)\r?\n\}\r?\n?"""),
            "\n",
        )

private fun inferProjectConventions(projectRoot: File, targets: Set<String>): ProjectConventions {
  val normalizedTargets = normalizeTargets(targets)
  val versionCatalog = File(projectRoot, "gradle/libs.versions.toml")
  if (!versionCatalog.isFile) {
    throw UsageError("Expected gradle/libs.versions.toml in ${projectRoot.absolutePath}")
  }

  val versionCatalogContent = versionCatalog.readText()
  val settingsContent =
      File(projectRoot, "settings.gradle.kts").takeIf { it.isFile }?.readText().orEmpty()
  val buildFiles =
      projectRoot.walkTopDown().filter { it.isFile && it.name == "build.gradle.kts" }.toList()

  val pluginAccessors = parseVersionCatalogPlugins(versionCatalogContent)
  val libraryAccessors = parseVersionCatalogLibraries(versionCatalogContent)

  fun pluginAccessor(pluginId: String): String =
      pluginAccessors[pluginId]
          ?: throw UsageError(
              "Could not infer plugin alias for $pluginId from gradle/libs.versions.toml")

  fun libraryAccessor(moduleCoordinate: String): String? = libraryAccessors[moduleCoordinate]

  val composeUiDependency =
      libraryAccessor("org.jetbrains.compose.ui:ui")
          ?: findDependencyExpression(buildFiles, "libs.compose.ui")
          ?: throw UsageError(
              "Could not infer a Compose UI dependency accessor from the current project.")

  val composablesUiDependency =
      findProjectDependencyExpression(buildFiles, ":ui")
          ?: libraryAccessor("com.composables:ui")
          ?: findDependencyExpression(buildFiles, "libs.composables.ui")
          ?: throw UsageError(
              "Could not infer how this project depends on Composables UI. Expected either project(\":ui\") or a com.composables:ui catalog alias.")

  val composablesIconsLucideDependency =
      libraryAccessor("com.composables:icons-lucide")
          ?: findDependencyExpression(buildFiles, "libs.composables.icons.lucide")
          ?: "libs.composables.icons.lucide"

  val composablesUriPainterDependency =
      libraryAccessor("com.composables:compose-uri-painter")
          ?: findDependencyExpression(buildFiles, "libs.composables.uri.painter")
          ?: "libs.composables.uri.painter"

  val activityComposeDependency =
      if (normalizedTargets.contains(ANDROID)) {
        libraryAccessor("androidx.activity:activity-compose")
            ?: findDependencyExpression(buildFiles, "libs.androidx.activity.compose")
            ?: throw UsageError(
                "Could not infer an Android Activity Compose dependency accessor from the current project.")
      } else {
        "libs.androidx.activity.compose"
      }

  val androidCompileSdkExpression =
      if (normalizedTargets.contains(ANDROID)) {
        findAssignedExpression(buildFiles, "compileSdk")
            ?: "libs.versions.android.compileSdk.get().toInt()"
      } else {
        "libs.versions.android.compileSdk.get().toInt()"
      }
  val androidMinSdkExpression =
      if (normalizedTargets.contains(ANDROID)) {
        findAssignedExpression(buildFiles, "minSdk") ?: "libs.versions.android.minSdk.get().toInt()"
      } else {
        "libs.versions.android.minSdk.get().toInt()"
      }
  val androidTargetSdkExpression =
      if (normalizedTargets.contains(ANDROID)) {
        findAssignedExpression(buildFiles, "targetSdk") ?: androidCompileSdkExpression
      } else {
        androidCompileSdkExpression
      }

  return ProjectConventions(
      kotlinMultiplatformPlugin = pluginAccessor("org.jetbrains.kotlin.multiplatform"),
      composePlugin = pluginAccessor("org.jetbrains.compose"),
      composeCompilerPlugin = pluginAccessor("org.jetbrains.kotlin.plugin.compose"),
      androidApplicationPlugin =
          if (normalizedTargets.contains(ANDROID)) {
            pluginAccessor("com.android.application")
          } else {
            "libs.plugins.android.application"
          },
      androidKotlinMultiplatformLibraryPlugin =
          if (normalizedTargets.contains(ANDROID)) {
            pluginAccessor("com.android.kotlin.multiplatform.library")
          } else {
            "libs.plugins.android.kotlin.multiplatform.library"
          },
      usesTypeSafeProjectAccessors =
          settingsContent.contains("""enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")"""),
      composeUiDependency = composeUiDependency,
      composablesUiDependency = composablesUiDependency,
      composablesIconsLucideDependency = composablesIconsLucideDependency,
      composablesUriPainterDependency = composablesUriPainterDependency,
      androidxActivityComposeDependency = activityComposeDependency,
      androidCompileSdkExpression = androidCompileSdkExpression,
      androidMinSdkExpression = androidMinSdkExpression,
      androidTargetSdkExpression = androidTargetSdkExpression,
  )
}

private fun parseVersionCatalogPlugins(content: String): Map<String, String> {
  val pluginPattern = Regex("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*\\{[^}]*id\\s*=\\s*\"([^\"]+)\"")
  return extractSection(content, "plugins")
      .lineSequence()
      .mapNotNull { line ->
        pluginPattern.find(line)?.let { match ->
          val alias = match.groupValues[1]
          val pluginId = match.groupValues[2]
          pluginId to "libs.plugins.${alias.replace('-', '.')}"
        }
      }
      .toMap()
}

private fun parseVersionCatalogLibraries(content: String): Map<String, String> {
  val modulePattern = Regex("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*\\{[^}]*module\\s*=\\s*\"([^\"]+)\"")
  val groupNamePattern =
      Regex(
          "^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*\\{[^}]*group\\s*=\\s*\"([^\"]+)\"[^}]*name\\s*=\\s*\"([^\"]+)\"")
  return extractSection(content, "libraries")
      .lineSequence()
      .mapNotNull { line ->
        modulePattern.find(line)?.let { match ->
          val alias = match.groupValues[1]
          val module = match.groupValues[2]
          return@mapNotNull module to "libs.${alias.replace('-', '.')}"
        }

        groupNamePattern.find(line)?.let { match ->
          val alias = match.groupValues[1]
          val group = match.groupValues[2]
          val name = match.groupValues[3]
          return@mapNotNull "$group:$name" to "libs.${alias.replace('-', '.')}"
        }

        null
      }
      .toMap()
}

private fun findDependencyExpression(
    buildFiles: List<File>,
    accessor: String,
): String? {
  val pattern = Regex("""\b(?:implementation|api)\((libs\.[A-Za-z0-9_.]+)\)\s*""")
  return buildFiles.firstNotNullOfOrNull { file ->
    pattern.findAll(file.readText()).map { it.groupValues[1] }.firstOrNull { it == accessor }
  }
}

private fun findProjectDependencyExpression(
    buildFiles: List<File>,
    projectPath: String,
): String? {
  val escapedProjectPath = Regex.escape(projectPath)
  val pattern = Regex("""\b(?:implementation|api)\((project\("$escapedProjectPath"\))\)""")
  return buildFiles.firstNotNullOfOrNull { file ->
    pattern.find(file.readText())?.groupValues?.get(1)
  }
}

private fun findAssignedExpression(
    buildFiles: List<File>,
    propertyName: String,
): String? {
  val pattern = Regex("""^\s*$propertyName\s*=\s*(.+)$""", RegexOption.MULTILINE)
  return buildFiles.firstNotNullOfOrNull { file ->
    pattern.find(file.readText())?.groupValues?.get(1)?.substringBefore("//")?.trim()
  }
}

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
    if (!pluginsContent.contains("libs.plugins.jetbrains.compose") &&
        !pluginsContent.contains("libs.plugins.kotlin.compose")) {
      requiredPlugins.add("    alias(libs.plugins.jetbrains.compose) apply false")
    }
    if (!pluginsContent.contains("libs.plugins.jetbrains.compose.compiler")) {
      // Only add compose compiler at root level if kotlin compose plugin is not already present
      if (!pluginsContent.contains("libs.plugins.kotlin.compose")) {
        requiredPlugins.add("    alias(libs.plugins.jetbrains.compose.compiler) apply false")
      }
    }
    if (normalizedTargets.contains(ANDROID) &&
        !pluginsContent.contains("libs.plugins.android.application")) {
      requiredPlugins.add("    alias(libs.plugins.android.application) apply false")
    }
    if (normalizedTargets.contains(ANDROID) &&
        !pluginsContent.contains("libs.plugins.android.kotlin.multiplatform.library")) {
      requiredPlugins.add(
          "    alias(libs.plugins.android.kotlin.multiplatform.library) apply false")
    }

    if (requiredPlugins.isNotEmpty()) {
      // Add missing plugins before closing brace
      requiredPlugins.reversed().forEach { plugin -> lines.add(pluginsEndIndex - 1, plugin) }
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
      requiredPlugins.add(
          "    alias(libs.plugins.android.kotlin.multiplatform.library) apply false")
    }
    requiredPlugins.add("}")

    // Add at the beginning of file
    requiredPlugins.reversed().forEach { line -> lines.add(0, line) }
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
  if (!hasVersionVariable(versionsSection, "iconsLucide")) {
    newVersions.add("iconsLucide = \"1.0.0\"")
  }

  // Add Android versions if android target is selected
  if (targets.contains("android")) {
    if (!hasVersionVariable(versionsSection, "agp")) newVersions.add("agp = \"9.2.1\"")
    if (!hasVersionVariable(versionsSection, "android-compileSdk"))
        newVersions.add("android-compileSdk = \"37\"")
    if (!hasVersionVariable(versionsSection, "android-minSdk"))
        newVersions.add("android-minSdk = \"23\"")
    if (!hasVersionVariable(versionsSection, "android-targetSdk"))
        newVersions.add("android-targetSdk = \"37\"")
    if (!hasVersionVariable(versionsSection, "activityCompose"))
        newVersions.add("activityCompose = \"1.13.0\"")
  }
  if (!hasVersionVariable(versionsSection, "uriPainter")) {
    newVersions.add("uriPainter = \"1.0.4\"")
  }
  // Add required libraries if not present
  val newLibraries = mutableListOf<String>()
  if (!hasLibraryVariable(librariesSection, "composables-ui")) {
    newLibraries.add(
        "composables-ui = { group = \"com.composables\", name = \"ui\", version.ref = \"composablesUi\" }")
  }
  if (!hasLibraryVariable(librariesSection, "composables-icons-lucide")) {
    newLibraries.add(
        "composables-icons-lucide = { group = \"com.composables\", name = \"icons-lucide\", version.ref = \"iconsLucide\" }")
  }
  if (!hasLibraryVariable(librariesSection, "composables-uri-painter")) {
    newLibraries.add(
        "composables-uri-painter = { group = \"com.composables\", name = \"compose-uri-painter\", version.ref = \"uriPainter\" }")
  }
  if (!hasLibraryVariable(librariesSection, "compose-ui")) {
    newLibraries.add(
        "compose-ui = { group = \"org.jetbrains.compose.ui\", name = \"ui\", version.ref = \"compose\" }")
  }
  if (!hasLibraryVariable(librariesSection, "compose-ui-tooling")) {
    newLibraries.add(
        "compose-ui-tooling = { group = \"org.jetbrains.compose.ui\", name = \"ui-tooling\", version.ref = \"compose\" }")
  }
  if (!hasLibraryVariable(librariesSection, "compose-ui-tooling-preview")) {
    newLibraries.add(
        "compose-ui-tooling-preview = { group = \"org.jetbrains.compose.ui\", name = \"ui-tooling-preview\", version.ref = \"compose\" }")
  }
  if (targets.contains("android") &&
      !hasLibraryVariable(librariesSection, "androidx-activity-compose")) {
    newLibraries.add(
        "androidx-activity-compose = { group = \"androidx.activity\", name = \"activity-compose\", version.ref = \"activityCompose\" }")
  }

  // Add required plugins if not present
  val newPlugins = mutableListOf<String>()
  if (!hasPluginVariable(pluginsSection, "jetbrains-kotlin-multiplatform")) {
    newPlugins.add(
        "jetbrains-kotlin-multiplatform = { id = \"org.jetbrains.kotlin.multiplatform\", version.ref = \"kotlin\" }")
  }
  if (!hasPluginVariable(pluginsSection, "jetbrains-compose")) {
    newPlugins.add(
        "jetbrains-compose = { id = \"org.jetbrains.compose\", version.ref = \"compose\" }")
  }
  if (!hasPluginVariable(pluginsSection, "jetbrains-compose-compiler")) {
    newPlugins.add(
        "jetbrains-compose-compiler = { id = \"org.jetbrains.kotlin.plugin.compose\", version.ref = \"kotlin\" }")
  }
  if (targets.contains("android") && !hasPluginVariable(pluginsSection, "android-application")) {
    newPlugins.add(
        "android-application = { id = \"com.android.application\", version.ref = \"agp\" }")
  }
  if (targets.contains("android") &&
      !hasPluginVariable(pluginsSection, "android-kotlin-multiplatform-library")) {
    newPlugins.add(
        "android-kotlin-multiplatform-library = { id = \"com.android.kotlin.multiplatform.library\", version.ref = \"agp\" }")
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
    newEntries.reversed().forEach { entry -> lines.add(sectionIndex + 1, entry) }
  } else {
    // Create new section at end
    lines.add("")
    lines.add("[$sectionName]")
    newEntries.forEach { entry -> lines.add(entry) }
  }

  return lines.joinToString("\n")
}

private fun addModuleToSettings(
    projectRoot: File,
    modulePaths: List<String>,
) {
  val settingsFile = File(projectRoot, "settings.gradle.kts")
  val existingContent = settingsFile.readText()
  val newStatements =
      modulePaths
          .map { ":" + it.split('/').joinToString(":") }
          .map { """include("$it")""" }
          .filterNot { existingContent.contains(it) }

  if (newStatements.isEmpty()) return

  settingsFile.writeText(existingContent.trimEnd() + "\n" + newStatements.joinToString("\n") + "\n")
}

private fun createModuleGroup(
    projectRoot: File,
    appRootDir: File,
    packageName: String,
    appName: String,
    targets: Set<String>,
    conventions: ProjectConventions,
): List<String> {
  val normalizedTargets = normalizeTargets(targets)
  val sharedModuleDir = File(appRootDir, SHARED_MODULE)
  val sharedModulePath = toRelativeModulePath(projectRoot, sharedModuleDir)
  val sharedModuleAccessor = toProjectAccessorPath(sharedModulePath)
  val sharedModuleDependency =
      if (conventions.usesTypeSafeProjectAccessors) {
        "projects.$sharedModuleAccessor"
      } else {
        """project(":${sharedModulePath.replace('/', ':')}")"""
      }
  val includedModules = mutableListOf(sharedModulePath)
  val resources = listResourceFiles("/project/$SHARED_MODULE")
  resources.forEach { resourcePath ->
    var targetPath = resourcePath.removePrefix("/project/$SHARED_MODULE/")

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

    targetPath = targetPath.replace("org/example/project", packageName.replace(".", "/"))
    targetPath = targetPath.replace("org/example", packageName.replace(".", "/"))

    val targetFile = sharedModuleDir.resolve(targetPath)
    copyBundledResource(resourcePath, targetFile)
  }

  sharedModuleDir.walkTopDown().forEach { file ->
    if (file.isFile) {
      if (file.name.endsWith(".jar") ||
          file.name.endsWith(".png") ||
          file.name.endsWith(".jpg") ||
          file.name.endsWith(".jpeg") ||
          file.name.endsWith(".ico") ||
          file.name.endsWith(".icns") ||
          file.name.endsWith(".class")) {
        return@forEach
      }

      try {
        val content = file.readText()
        val updatedContent =
            renderProjectTemplate(
                content = stripPreviewSupport(content),
                packageName = packageName,
                moduleName = SHARED_MODULE,
                appName = appName,
                targets = normalizedTargets,
                conventions = conventions,
            )
        if (content != updatedContent) {
          file.writeText(updatedContent)
        }
      } catch (_: Exception) {
        debugln { "Skipping binary file: ${file.name}" }
      }
    }
  }

  if (normalizedTargets.contains(ANDROID)) {
    createAndroidAppModuleInDirectory(
        appRootDir = appRootDir,
        sharedModuleDependency = sharedModuleDependency,
        namespace = packageName,
        appName = appName,
        conventions = conventions,
    )
    includedModules += toRelativeModulePath(projectRoot, File(appRootDir, ANDROID_APP_MODULE))
  }

  if (normalizedTargets.contains(JVM)) {
    createDesktopAppModuleInDirectory(
        appRootDir = appRootDir,
        sharedModuleDependency = sharedModuleDependency,
        namespace = packageName,
        appName = appName,
        conventions = conventions,
    )
    includedModules += toRelativeModulePath(projectRoot, File(appRootDir, DESKTOP_APP_MODULE))
  }

  if (normalizedTargets.contains(WASM)) {
    createWebAppModuleInDirectory(
        appRootDir = appRootDir,
        sharedModuleDependency = sharedModuleDependency,
        namespace = packageName,
        appName = appName,
        conventions = conventions,
    )
    includedModules += toRelativeModulePath(projectRoot, File(appRootDir, WEB_APP_MODULE))
  }

  if (normalizedTargets.contains(IOS)) {
    createNestedIosAppDirectory(
        projectRoot = projectRoot,
        appRootDir = appRootDir,
        sharedModulePath = sharedModulePath,
        namespace = packageName,
        appName = appName,
    )
  }

  return includedModules
}

private fun createAndroidAppModuleInDirectory(
    appRootDir: File,
    sharedModuleDependency: String,
    namespace: String,
    appName: String,
    conventions: ProjectConventions,
) {
  val androidAppDir = File(appRootDir, ANDROID_APP_MODULE)
  androidAppDir.mkdirs()
  File(androidAppDir, "build.gradle.kts")
      .writeText(
          object {}
              .javaClass
              .getResource("/project/$ANDROID_APP_MODULE/build.gradle.kts")!!
              .readText()
              .replace(
                  "implementation(projects.{{shared_module_accessor}})",
                  "implementation($sharedModuleDependency)")
              .replace("{{namespace}}", namespace)
              .replace(
                  "alias(libs.plugins.android.application)",
                  "alias(${conventions.androidApplicationPlugin})")
              .replace(
                  "alias(libs.plugins.jetbrains.compose.compiler)",
                  "alias(${conventions.composeCompilerPlugin})")
              .replace(
                  "libs.versions.android.compileSdk.get().toInt()",
                  conventions.androidCompileSdkExpression)
              .replace(
                  "libs.versions.android.minSdk.get().toInt()", conventions.androidMinSdkExpression)
              .replace(
                  "libs.versions.android.targetSdk.get().toInt()",
                  conventions.androidTargetSdkExpression)
              .replace(
                  "implementation(libs.androidx.activity.compose)",
                  "implementation(${conventions.androidxActivityComposeDependency})")
              .trim() + "\n",
      )

  listResourceFiles("/project/$ANDROID_APP_MODULE/src/main").forEach { resourcePath ->
    val targetPath =
        resourcePath
            .removePrefix("/project/$ANDROID_APP_MODULE/src/main/")
            .replace("org/example/project", namespace.replace(".", "/"))
    val targetFile = File(androidAppDir, "src/main/$targetPath")
    copyBundledResource(resourcePath, targetFile)

    if (targetFile.name.endsWith(".kt") || targetFile.name.endsWith(".xml")) {
      val content = targetFile.readText()
      val updatedContent =
          content.replace("{{namespace}}", namespace).replace("{{app_name}}", appName)
      if (content != updatedContent) {
        targetFile.writeText(updatedContent)
      }
    }
  }
}

private fun createDesktopAppModuleInDirectory(
    appRootDir: File,
    sharedModuleDependency: String,
    namespace: String,
    appName: String,
    conventions: ProjectConventions,
) {
  val desktopAppDir = File(appRootDir, DESKTOP_APP_MODULE)
  desktopAppDir.mkdirs()
  File(desktopAppDir, "build.gradle.kts")
      .writeText(
          object {}
              .javaClass
              .getResource("/project/$DESKTOP_APP_MODULE/build.gradle.kts")!!
              .readText()
              .replace(
                  "implementation(projects.{{shared_module_accessor}})",
                  "implementation($sharedModuleDependency)")
              .replace("{{namespace}}", namespace)
              .replace(
                  "alias(libs.plugins.jetbrains.kotlin.multiplatform)",
                  "alias(${conventions.kotlinMultiplatformPlugin})")
              .replace(
                  "alias(libs.plugins.jetbrains.compose)", "alias(${conventions.composePlugin})")
              .replace(
                  "alias(libs.plugins.jetbrains.compose.compiler)",
                  "alias(${conventions.composeCompilerPlugin})")
              .trim() + "\n",
      )

  val mainFile = File(desktopAppDir, "src/jvmMain/kotlin/${namespace.replace(".", "/")}/main.kt")
  mainFile.parentFile.mkdirs()
  mainFile.writeText(
      object {}
          .javaClass
          .getResource("/project/$DESKTOP_APP_MODULE/src/jvmMain/kotlin/org/example/main.kt")!!
          .readText()
          .replace("{{namespace}}", namespace)
          .replace("{{app_name}}", appName)
          .trim() + "\n",
  )
}

private fun createWebAppModuleInDirectory(
    appRootDir: File,
    sharedModuleDependency: String,
    namespace: String,
    appName: String,
    conventions: ProjectConventions,
) {
  val webAppDir = File(appRootDir, WEB_APP_MODULE)

  listResourceFiles("/project/$WEB_APP_MODULE").forEach { resourcePath ->
    var targetPath = resourcePath.removePrefix("/project/$WEB_APP_MODULE/")
    targetPath = targetPath.replace("org/example/project", namespace.replace(".", "/"))
    targetPath = targetPath.replace("org/example", namespace.replace(".", "/"))
    val targetFile = webAppDir.resolve(targetPath)
    copyBundledResource(resourcePath, targetFile)

    if (targetFile.isFile && targetFile.extension in setOf("kt", "kts", "html", "js", "css")) {
      val content = targetFile.readText()
      val updatedContent =
          content
              .replace(
                  "implementation(projects.{{shared_module_accessor}})",
                  "implementation($sharedModuleDependency)")
              .replace("{{namespace}}", namespace)
              .replace("{{app_name}}", appName)
              .replace(
                  "alias(libs.plugins.jetbrains.kotlin.multiplatform)",
                  "alias(${conventions.kotlinMultiplatformPlugin})")
              .replace(
                  "alias(libs.plugins.jetbrains.compose)", "alias(${conventions.composePlugin})")
              .replace(
                  "alias(libs.plugins.jetbrains.compose.compiler)",
                  "alias(${conventions.composeCompilerPlugin})")
              .replace(
                  "implementation(libs.compose.ui)",
                  "implementation(${conventions.composeUiDependency})")
      val finalContent =
          if (targetFile.name == "build.gradle.kts" &&
              !updatedContent.contains("injectWasmPreloads")) {
            updatedContent.trimEnd() + "\n\n" + moduleWasmPreloadTaskWiring() + "\n"
          } else {
            updatedContent
          }
      if (content != finalContent) {
        targetFile.writeText(finalContent)
      }
    }
  }
}

private fun createNestedIosAppDirectory(
    projectRoot: File,
    appRootDir: File,
    sharedModulePath: String,
    namespace: String,
    appName: String,
) {
  val iosAppDir = File(appRootDir, IOS_APP_MODULE)
  val relativeRootPath =
      buildString {
            repeat(appRootDir.relativeTo(projectRoot).invariantSeparatorsPath.split('/').size + 1) {
              append("../")
            }
          }
          .removeSuffix("/")
  val sharedGradlePath = ":" + sharedModulePath.split('/').joinToString(":")

  listResourceFiles("/project/$IOS_APP_MODULE").forEach { resourcePath ->
    val targetPath = resourcePath.removePrefix("/project/$IOS_APP_MODULE/")
    val targetFile = iosAppDir.resolve(targetPath)
    copyBundledResource(resourcePath, targetFile)

    if (targetFile.isFile &&
        targetFile.extension in setOf("swift", "h", "m", "pbxproj", "xcconfig")) {
      val content = targetFile.readText()
      val updatedContent =
          content
              .replace("{{module_name}}", sharedGradlePath.removePrefix(":"))
              .replace("{{ios_binary_name}}", toCamelCase(SHARED_MODULE))
              .replace("{{target_name}}", "$IOS_APP_MODULE.app")
              .replace("{{app_name}}", appName)
              .replace("{{namespace}}", namespace)
              .replace("""cd "${'$'}SRCROOT/.."""", """cd "${'$'}SRCROOT/$relativeRootPath"""")
              .replace(
                  "./gradlew :{{module_name}}:embedAndSignAppleFrameworkForXcode",
                  "./gradlew $sharedGradlePath:embedAndSignAppleFrameworkForXcode")
      if (content != updatedContent) {
        targetFile.writeText(updatedContent)
      }
    }
  }
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
      inputStream.use { input -> targetFile.outputStream().use { output -> input.copyTo(output) } }
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
        targetFile.name.endsWith(".xcconfig")) {
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
    val process =
        ProcessBuilder(gradleScript, "properties", "-q", "--no-daemon")
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
