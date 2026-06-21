@file:Suppress("UnstableApiUsage")

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test

plugins {
    application
    alias(libs.plugins.jvm)
    alias(libs.plugins.shadow)
    id("com.github.gmazzo.buildconfig") version "6.0.6"
}

val mainClassName = "com.composables.cli.CliKt"
val cliName = "composables"

group = "com.composables"
version = libs.versions.composables.cli.get()

buildConfig {
    buildConfigField("Version", libs.versions.composables.cli.get())
}

java {
    toolchain {
        vendor = JvmVendorSpec.JETBRAINS
        languageVersion = JavaLanguageVersion.of(17)
    }
}

kotlin {
    jvmToolchain {
        vendor = JvmVendorSpec.JETBRAINS
        languageVersion = JavaLanguageVersion.of(17)
    }
}

application {
    applicationName = cliName
    mainClass.set(mainClassName)
}

sourceSets {
    named("main") {
        java.setSrcDirs(listOf("src/jvmMain/kotlin"))
        resources.setSrcDirs(listOf("src/jvmMain/resources"))
    }
    named("test") {
        java.setSrcDirs(listOf("src/jvmTest/kotlin"))
        resources.setSrcDirs(emptyList<String>())
    }
    create("integrationTest") {
        java.setSrcDirs(listOf("src/integrationTest/kotlin"))
        resources.setSrcDirs(emptyList<String>())
        compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}

val integrationTestSourceSet = sourceSets.named("integrationTest").get()

configurations[integrationTestSourceSet.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    implementation("com.alexstyl:debugln:1.0.3")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")

    testImplementation("com.willowtreeapps.assertk:assertk:0.28.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs end-to-end integration tests for the CLI."
    group = "verification"

    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
    dependsOn(tasks.named("installDist"))
    useJUnitPlatform()
}

tasks.named<ShadowJar>("shadowJar") {
    group = "build"
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get())

    archiveFileName.set("$cliName.jar")

    manifest {
        attributes("Main-Class" to mainClassName)
    }
    mergeServiceFiles()
}

tasks.jar {
    finalizedBy(tasks.named("shadowJar"))
}

val devTemplateOutputDir = layout.buildDirectory.dir("dev-template")

tasks.register<JavaExec>("renderTemplate") {
    group = "application"
    description = "Renders the bundled project template into build/dev-template/app for local JVM template development."

    dependsOn(tasks.jar)

    mainClass.set("com.composables.cli.DevTemplateKt")
    classpath(sourceSets.main.get().runtimeClasspath)

    systemProperty("composables.template.outputRoot", devTemplateOutputDir.get().asFile.absolutePath)
    systemProperty("composables.template.projectDir", "app")
    systemProperty("composables.template.targets", "jvm")
}

tasks.register<Exec>("runTemplate") {
    group = "application"
    description = "Renders the bundled project template and runs its JVM target locally."

    dependsOn("renderTemplate")

    val renderedProjectDir = devTemplateOutputDir.map { it.dir("app") }
    workingDir(renderedProjectDir)
    commandLine("./gradlew", "run")
}
