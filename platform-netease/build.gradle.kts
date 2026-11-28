plugins { id("com.android.library") }
android {
    namespace = "tech.musxi.platform.netease"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    // Pure Java X25519 for Android 8+, GPLv3 compatible with this GPL-3.0-or-later app.
    implementation("org.whispersystems:curve25519-java:0.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
