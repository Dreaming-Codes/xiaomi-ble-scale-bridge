plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.lorenzo.s200.bridge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "dev.lorenzo.s200.bridge"; minSdk = 34; targetSdk = 36; versionCode = 1; versionName = "0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":protocol"))
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
