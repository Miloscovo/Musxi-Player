plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "tech.musxi.playback"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core"))
    testImplementation("junit:junit:4.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    api("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
}
