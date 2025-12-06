fun runBash(command: String) {
    val process = ProcessBuilder("bash", "-c", command).inheritIO().start()
    process.waitFor()
}

val jarPath = "cli/build/libs/composables.jar"
val projectRoot = "/Users/alexstyl/projects/composables-cli"

runBash("cd $projectRoot && ./gradlew jvmShadowJar")
