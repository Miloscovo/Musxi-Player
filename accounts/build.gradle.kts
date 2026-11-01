plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "tech.musxi.accounts"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    api(project(":core"))
    implementation(project(":platform-kugou"))
    implementation(project(":platform-qq"))
    implementation(project(":platform-netease"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
