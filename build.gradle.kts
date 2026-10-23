plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("io.github.takahirom.roborazzi") version "1.76.0" apply false
}

// JDK 17 on Windows decodes process argument files in the native charset.
// Do not force the Gradle daemon to UTF-8 when the workspace path is Chinese;
// keep source files and test content UTF-8 independently of the daemon charset.
subprojects {
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    tasks.withType<Test>().configureEach { defaultCharacterEncoding = "UTF-8" }
}
