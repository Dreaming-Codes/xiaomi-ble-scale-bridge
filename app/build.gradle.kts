import java.util.Properties

plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }

val releaseSigning: Map<String, String>? = run {
    val path = System.getenv("S200_KEYSTORE_PROPERTIES") ?: rootProject.file("keystore.properties").takeIf { it.exists() }?.path
    val props = path?.let { f -> Properties().apply { file(f).inputStream().use(::load) } }
    fun pick(key: String, env: String) = props?.getProperty(key) ?: System.getenv(env)
    val m = mapOf(
        "storeFile" to pick("storeFile", "S200_STORE_FILE"), "storePassword" to pick("storePassword", "S200_STORE_PASSWORD"),
        "keyAlias" to pick("keyAlias", "S200_KEY_ALIAS"), "keyPassword" to pick("keyPassword", "S200_KEY_PASSWORD"),
    )
    if (m.values.all { it != null } && file(m["storeFile"]!!).exists()) m.mapValues { it.value!! } else null
}

android {
    namespace = "dev.lorenzo.s200.bridge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "dev.lorenzo.s200.bridge"; minSdk = 34; targetSdk = 36; versionCode = 6; versionName = "0.6" }
    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = file(releaseSigning.getValue("storeFile"))
            storePassword = releaseSigning.getValue("storePassword")
            keyAlias = releaseSigning.getValue("keyAlias")
            keyPassword = releaseSigning.getValue("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":protocol"))
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

abstract class CheckNoBackup : DefaultTask() {
    @get:InputFile abstract val manifest: RegularFileProperty
    @TaskAction fun run() {
        check(Regex("""android:allowBackup\s*=\s*"false"""").containsMatchIn(manifest.get().asFile.readText())) {
            "merged manifest must set android:allowBackup=\"false\" so the bind key is never backed up"
        }
    }
}
androidComponents.onVariants { variant ->
    val t = tasks.register<CheckNoBackup>("check${variant.name.replaceFirstChar(Char::uppercase)}NoBackup") {
        manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
    }
    tasks.named("check") { dependsOn(t) }
}
