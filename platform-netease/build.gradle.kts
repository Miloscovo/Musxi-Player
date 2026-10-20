plugins { id("com.android.library") }
android {
    namespace = "tech.musxi.platform.netease"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { testImplementation("junit:junit:4.13.2"); testImplementation("org.json:json:20240303") }
