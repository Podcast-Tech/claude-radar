plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Version comes from -PradarVersion=1.2.3 (CI passes the git tag). versionCode = 1_02_03.
val radarVersion = (project.findProperty("radarVersion") as String).removePrefix("v")
val radarVersionCode = radarVersion.split(".").map { it.toInt() }
    .let { (a, b, c) -> a * 10000 + b * 100 + c }

android {
    namespace = "com.poddster.clauderadar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.poddster.clauderadar"
        minSdk = 26
        targetSdk = 35
        versionCode = radarVersionCode
        versionName = radarVersion
        buildConfigField("String", "SUPABASE_URL", "\"https://dkfiyqacjdsvvbrcsabw.supabase.co\"")
        // Publishable key: safe to ship in an app. Data is protected by row-level security + login.
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_QYuYaLDjC6862ClbKyhO7w_gDTdHEct\"")
        buildConfigField("String", "GITHUB_REPO", "\"Podcast-Tech/claude-radar\"")
        // Speech engine native code: phones only (ARM), keeps the APK small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // One signing key for every release, so updates install over the previous version.
    val ksPath = System.getenv("RADAR_KEYSTORE") ?: rootProject.file("release.jks").path
    signingConfigs {
        create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("RADAR_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RADAR_KEY_ALIAS") ?: "radar"
            keyPassword = System.getenv("RADAR_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("io.github.jan-tennert.supabase:bom:3.1.4"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-okhttp:3.1.3")
    // Offline speech recognition for "Hey GoGo" (runs on the phone)
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
}
