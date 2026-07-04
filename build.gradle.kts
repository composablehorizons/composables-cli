plugins {
    alias(libs.plugins.jvm).apply(false)
    alias(libs.plugins.spotless)
}

subprojects {
    apply(plugin = rootProject.libs.plugins.spotless.get().pluginId)

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target(
                fileTree(project.projectDir) {
                    include("src/**/*.kt")
                    exclude("src/**/resources/**/*.kt")
                }
            )
            ktfmt()
        }
        kotlinGradle {
            target("*.gradle.kts", "gradle/*.gradle.kts", "**/*.gradle.kts")
            targetExclude("**/build/**/*.gradle.kts", "src/**/resources/**/*.gradle.kts")
            ktfmt()
        }
    }
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
