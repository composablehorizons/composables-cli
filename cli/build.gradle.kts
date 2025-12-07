@file:Suppress("UnstableApiUsage")

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget


plugins {
    alias(libs.plugins.multiplatform)
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

val organization = "composablehorizons"

val projectName = "composables-cli"
val githubUrl = "github.com/$organization/$projectName"

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

    jvm {
        binaries {
            executable {
                mainClass.set(mainClassName)
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("com.alexstyl:debugln:1.0.3")
                implementation("com.github.ajalt.clikt:clikt:5.0.3")
            }
        }
    }
}


// see: https://stackoverflow.com/questions/63426211/kotlin-multiplatform-shadowjar-gradle-plugin-creates-empty-jar

fun registerShadowJar(targetName: String) {
    kotlin.targets.named<KotlinJvmTarget>(targetName) {
        compilations.named("main") {
            tasks {
                val shadowJar = register<ShadowJar>("${targetName}ShadowJar") {
                    group = "build"
                    from(output)
                    configurations = listOf(runtimeDependencyFiles)

                    archiveFileName.set("$cliName.jar")

                    manifest {
                        attributes("Main-Class" to mainClassName)
                    }
                    mergeServiceFiles()
                }
                getByName("${targetName}Jar") {
                    finalizedBy(shadowJar)
                }
            }
        }
    }
}

registerShadowJar("jvm")
