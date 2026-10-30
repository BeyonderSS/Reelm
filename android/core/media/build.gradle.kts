import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.anistream.core.media"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // TMDB key for recommendations: local.properties locally, the TMDB_API_KEY env var in CI. Empty = TMDB skipped.
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val tmdbKey = localProps.getProperty("TMDB_API_KEY")?.takeIf { it.isNotBlank() } ?: System.getenv("TMDB_API_KEY") ?: ""
        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbKey.trim()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions.unitTests.all { test ->
        // Network proof of the Vega engine: ./gradlew :core:media:testDebugUnitTest -Pvega.live
        if (project.hasProperty("vega.live")) test.systemProperty("vega.live", "true")
    }
}

// Unit tests run QuickJS on the host JVM: swap the Android natives for the desktop ones.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "io.github.dokar3", module = "quickjs-kt-android")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:common"))
    implementation(project(":core:account"))
    implementation(project(":core:database"))
    implementation(project(":domain"))

    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.quickjs)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testRuntimeOnly(libs.quickjs.jvm)
}
