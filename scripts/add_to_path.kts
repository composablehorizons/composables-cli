fun runBash(command: String) {
    val process = ProcessBuilder("bash", "-c", command).inheritIO().start()
    process.waitFor()
}

val jarPath = "cli/build/libs/composables.jar"
val projectRoot = "/Users/alexstyl/projects/composables-cli"
val installDir = "${System.getenv("HOME")}/.composables/bin"
val jarName = "composables.jar"
val wrapperName = "composables"

println("Installing Composables CLI from local build...")

runBash("if ! command -v java &> /dev/null; then echo 'Error: Java is required but not installed'; exit 1; fi")

println("Building shadowjar...")
runBash("cd $projectRoot && ./gradlew jvmShadowJar")

runBash("mkdir -p $installDir")

println("Installing local JAR...")
runBash("cp $projectRoot/$jarPath $installDir/$jarName")

println("Creating wrapper script...")
val homeDir = System.getenv("HOME")
val wrapperScript = """#!/bin/bash
exec java -jar "$homeDir/.composables/bin/$jarName" "$@"
"""

runBash("echo '$wrapperScript' > $installDir/$wrapperName")
runBash("chmod +x $installDir/$wrapperName")

val currentShell = System.getenv("SHELL")?.substringAfterLast("/") ?: "bash"
val shellRc =
    if (currentShell == "zsh") "${System.getenv("HOME")}/.zshrc"
    else if (currentShell == "bash") {
        if (System.getProperty("os.name").contains("Mac")) "${System.getenv("HOME")}/.bash_profile"
        else "${System.getenv("HOME")}/.bashrc"
    } else "${System.getenv("HOME")}/.bash_profile"

runBash("if ! grep -q '.composables/bin' $shellRc 2>/dev/null; then echo '' >> $shellRc; echo '# Composables CLI' >> $shellRc; echo 'export PATH=\"\$HOME/.composables/bin:\$PATH\"' >> $shellRc; echo 'Added Composables CLI to PATH in $shellRc'; else echo 'Composables CLI already in PATH'; fi")

runBash("export PATH=\"$installDir:\$PATH\"")

println("Testing installation...")
runBash("if command -v composables &> /dev/null; then echo '✓ Composables CLI installed successfully!'; echo ''; echo 'Usage:'; echo '  composables --help               - Show all available commands'; echo ''; echo \"Note: Restart your terminal or run 'source $shellRc' to use composables from anywhere\"; else echo 'Error: Installation verification failed'; exit 1; fi")
