plugins {
    alias(libs.plugins.multiplatform).apply(false)
}

tasks.register("renderTemplate") {
    group = "application"
    description = "Renders the CLI project template into cli/build/dev-template/app."
    dependsOn(":cli:renderTemplate")
}

tasks.register("runTemplate") {
    group = "application"
    description = "Renders the CLI project template and runs the generated JVM app."
    dependsOn(":cli:runTemplate")
}
