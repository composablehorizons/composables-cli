plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.jetbrains.kotlin.multiplatform) apply false
    alias(libs.plugins.jetbrains.compose.hotreload) apply false
    alias(libs.plugins.jetbrains.compose) apply false
    alias(libs.plugins.jetbrains.compose.compiler) apply false
{{android_plugin}}}

subprojects {
    fun registerPreloadInjectionTask(
        distributionTarget: String,
        markerName: String,
        includeWasmArtifacts: Boolean,
    ) = tasks.register("inject${distributionTarget.replaceFirstChar(Char::titlecase)}Preloads") {
        description = "Injects preload links for generated $distributionTarget distribution artifacts."
        val distributionDir = layout.buildDirectory.dir("dist/$distributionTarget/productionExecutable")
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
                .map { """  <link rel="preload" href="${it.name}" as="script">""" }

            val artifactPreloads = if (preloadWasmArtifacts) {
                distDir
                    .listFiles { file -> file.isFile && file.extension == "wasm" }
                    .orEmpty()
                    .sortedBy { it.name }
                    .map { """  <link rel="preload" href="${it.name}" as="fetch" type="application/wasm" crossorigin>""" }
            } else {
                emptyList()
            }

            val preloadBlock = (scriptPreloads + artifactPreloads).joinToString(
                separator = "\n",
                prefix = "  <!-- $preloadMarker:start -->\n",
                postfix = "\n  <!-- $preloadMarker:end -->",
            )

            val existingPreloadBlock = Regex(
                pattern = """\n?  <!-- $preloadMarker:start -->.*?  <!-- $preloadMarker:end -->\n?""",
                options = setOf(RegexOption.DOT_MATCHES_ALL),
            )
            val indexHtml = indexFile.readText().replace(existingPreloadBlock, "\n")
            val updatedIndexHtml = indexHtml.replaceFirst("</title>", "</title>\n$preloadBlock")
            indexFile.writeText(updatedIndexHtml)
        }
    }

    val injectJsPreloads = registerPreloadInjectionTask(
        distributionTarget = "js",
        markerName = "js-preloads",
        includeWasmArtifacts = false,
    )
    val injectWasmPreloads = registerPreloadInjectionTask(
        distributionTarget = "wasmJs",
        markerName = "wasm-preloads",
        includeWasmArtifacts = true,
    )
    val injectComposeWebCompatibilityPreloads = registerPreloadInjectionTask(
        distributionTarget = "composeWebCompatibility",
        markerName = "compose-web-compatibility-preloads",
        includeWasmArtifacts = true,
    )

    tasks.matching { it.name in setOf("composeCompatibilityBrowserDistribution", "jsBrowserDistribution") }.configureEach {
        finalizedBy(injectJsPreloads)
    }

    tasks.matching { it.name in setOf("composeCompatibilityBrowserDistribution", "wasmJsBrowserDistribution") }.configureEach {
        finalizedBy(injectWasmPreloads)
    }

    tasks.matching { it.name == "composeCompatibilityBrowserDistribution" }.configureEach {
        finalizedBy(injectComposeWebCompatibilityPreloads)
    }
}
