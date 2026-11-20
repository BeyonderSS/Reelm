import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.anistream.feature.party"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // Watch-together server: local.properties locally, the REELM_API_URL env var in CI. Blank hides the feature.
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val api = localProps.getProperty("REELM_API_URL")?.takeIf { it.isNotBlank() } ?: System.getenv("REELM_API_URL") ?: ""
        buildConfigField("String", "REELM_API_URL", "\"${api.trim().trimEnd('/')}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":core:account"))
    implementation(project(":core:common"))
    implementation(project(":core:media"))
    implementation(project(":core:ui"))
    implementation(project(":core:trailer"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    api(libs.kotlinx.serialization.json) // payloads are JsonObject in the public API
    implementation(libs.media3.exoplayer)
    implementation(libs.activity.compose) // camera/mic permission requests
    // video and voice: only the phone flavor packages libwebrtc (app "phoneImplementation"); TV checks rtcAvailable
    compileOnly(libs.webrtc)
    compileOnly(libs.webrtc.ui)

    testImplementation(libs.junit)
}
