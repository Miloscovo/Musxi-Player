plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    kotlin("android") version "2.3.21" apply false
    kotlin("jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
    id("io.github.takahirom.roborazzi") version "1.76.0" apply false
}

// One task graph for local verification and CI. New modules join through their plugins.
val verifyUnitTests = tasks.register("verifyUnitTests") {
    group = "verification"
    description = "Runs every module's JVM tests."
}
val verifyLint = tasks.register("verifyLint") {
    group = "verification"
    description = "Runs debug Lint for every Android module."
}
tasks.register("verifyAndroid") {
    group = "verification"
    description = "Builds debug and instrumentation APKs, and runs all JVM tests and Lint."
    dependsOn(verifyUnitTests, verifyLint, ":app:assembleDebug", ":app:assembleDebugAndroidTest")
    doLast {
        val plan = layout.buildDirectory.file("verification/modules.tsv").get().asFile
        plan.parentFile.mkdirs()
        val rows = subprojects.sortedBy { it.path }.mapNotNull { module ->
            val kind = when {
                module.plugins.hasPlugin("com.android.application") || module.plugins.hasPlugin("com.android.library") -> "android"
                module.plugins.hasPlugin("org.jetbrains.kotlin.jvm") -> "jvm"
                else -> return@mapNotNull null
            }
            "$kind\t${module.name}\t${module.projectDir.relativeTo(rootDir).invariantSeparatorsPath}"
        }
        plan.writeText("kind\tmodule\tdirectory\n" + rows.joinToString("\n") + "\n", Charsets.UTF_8)
    }
}

// JDK 17 on Windows decodes process argument files in the native charset.
// Do not force the Gradle daemon to UTF-8 when the workspace path is Chinese;
// keep source files and test content UTF-8 independently of the daemon charset.
subprojects {
    val modulePath = path
    fun addAndroidVerification() {
        verifyUnitTests.configure { dependsOn("$modulePath:testDebugUnitTest") }
        verifyLint.configure { dependsOn("$modulePath:lintDebug") }
    }
    pluginManager.withPlugin("com.android.application") { addAndroidVerification() }
    pluginManager.withPlugin("com.android.library") { addAndroidVerification() }
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        verifyUnitTests.configure { dependsOn("$modulePath:test") }
    }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    tasks.withType<Test>().configureEach {
        defaultCharacterEncoding = "UTF-8"
        if (providers.gradleProperty("musxi.verify.fresh").orNull == "true") {
            // Reuse compilation, but never reuse test results as evidence for a new validation run.
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
    }
}
