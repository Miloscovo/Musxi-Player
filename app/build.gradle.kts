plugins {
    id("com.android.application"); kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi")
}
val releaseStorePath = providers.gradleProperty("musxi.release.storeFile").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val releaseKeyAliasValue = providers.gradleProperty("musxi.release.keyAlias").orNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
val releasePasswordValue = providers.environmentVariable("MUSXI_RELEASE_STORE_PASSWORD").orNull
    ?.takeIf { it.isNotEmpty() }
android {
    namespace = "tech.musxi.player"
    compileSdk = 36
    defaultConfig {
        applicationId = "tech.musxi.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        create("release") {
            if (releaseStorePath != null && releaseKeyAliasValue != null && releasePasswordValue != null) {
                storeFile = file(releaseStorePath)
                storePassword = releasePasswordValue
                keyAlias = releaseKeyAliasValue
                keyPassword = releasePasswordValue
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        release {
            if (releaseStorePath != null && releaseKeyAliasValue != null && releasePasswordValue != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // Robolectric screenshot tests render Compose layouts on the JVM; they need merged resources.
    testOptions { unitTests.isIncludeAndroidResources = true }
}
val verifyMusxiReleaseSigningConfiguration by tasks.registering {
    group = "verification"
    description = "Requires the external Musxi release keystore configuration without affecting debug builds."
    notCompatibleWithConfigurationCache("Release signing credentials must stay in process memory.")
    doLast {
        val missing = mutableListOf<String>()
        if (releaseStorePath == null) missing += "musxi.release.storeFile"
        if (releaseKeyAliasValue == null) missing += "musxi.release.keyAlias"
        if (releasePasswordValue == null) missing += "MUSXI_RELEASE_STORE_PASSWORD"
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured. Set ${missing.joinToString()} outside the repository; " +
                    "release builds cannot use debug or unsigned signing.",
            )
        }
        if (!file(requireNotNull(releaseStorePath)).isFile) {
            throw GradleException("musxi.release.storeFile does not point to a readable keystore file.")
        }
        if (file(requireNotNull(releaseStorePath)).canonicalFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
            throw GradleException("The release keystore must be stored outside the repository.")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyMusxiReleaseSigningConfiguration)
}
kotlin { jvmToolchain(17) }
// Design screenshots go to app/build/design/ and are never part of the APK.
roborazzi { outputDir.set(layout.buildDirectory.dir("design")) }
dependencies {
    implementation(project(":accounts"))
    implementation(project(":playback"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    // 1.5 alpha18 is the newest Expressive build compatible with AGP 8.13 / SDK 36; it is needed for the Expressive components (ShortNavigationBar, wavy progress).
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.9.6")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation(project(":platform-qq")) // Isolated official web-login acceptance only.
    debugImplementation(project(":platform-kugou")) // Isolated official web-login acceptance only.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("dev.chrisbanes.haze:haze:1.6.10") // Liquid-glass chrome (ui/components/Glass.kt), Apache-2.0.
    // Refraction for that glass on Android 13+ (AGSL), Apache-2.0. 2.0.0 and later declare minCompileSdk 37,
    // which AGP 8.13.2 rejects; alpha03 is the newest release that builds with compileSdk 36.
    implementation("io.github.kyant0:backdrop:2.0.0-alpha03")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation(project(":platform-kugou"))
    androidTestImplementation(project(":platform-qq"))
    androidTestImplementation(project(":platform-netease"))
}
