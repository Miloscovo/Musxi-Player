plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "tech.musxi.player"
    compileSdk = 36
    defaultConfig {
        applicationId = "tech.musxi.player"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        release { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(project(":accounts"))
    implementation(project(":playback"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
