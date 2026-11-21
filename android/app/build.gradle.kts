plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

// Same URL the :core:account BuildConfig uses; only its host goes into the manifest (https://<host>/pair/<code> deep link).
val apiUrl: String = rootProject.file("local.properties").takeIf { it.exists() }
    ?.readLines()?.firstOrNull { it.startsWith("REELM_API_URL=") }?.substringAfter("=")?.trim()
    ?: System.getenv("REELM_API_URL")?.trim()
    ?: ""

android {
    namespace = "com.anistream.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.anistream.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // CI passes VERSION_CODE / VERSION_NAME per build; local builds stay 1 / 1.0.0
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0"
        manifestPlaceholders["apiHost"] = apiUrl.substringAfter("://").substringBefore("/").ifBlank { "account.invalid" }
    }

    // Release key comes from CI secrets; without it (local builds) release falls back to the debug key.
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // phone = the existing app; tv = Android TV / Fire TV build launching TvActivity (app/src/tv overlay)
    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            minSdk = 26 // phone keeps API 26+; only tv drops to 22 (Fire TV Stick 2nd gen, Fire OS 5)
            isDefault = true
            // libwebrtc (watch-party video) is ~12 MB per ABI: no 32-bit x86 phones exist; x86_64 stays in debug for the emulator
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        create("tv") {
            dimension = "device"
            applicationIdSuffix = ".tv"
            minSdk = 22
            versionNameSuffix = "-tv"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // native libs compressed in the APK (smaller download over slow lines; the TV build at minSdk 22 already does this)
    packaging { jniLibs { useLegacyPackaging = true } }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// phone release ships ARM only (no x86 phones in the wild); debug keeps x86_64 for the emulator
androidComponents {
    onVariants(selector().withBuildType("release").withFlavor("device" to "phone")) {
        it.packaging.jniLibs.excludes.addAll("lib/x86/**", "lib/x86_64/**")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:network"))
    implementation(project(":core:parser"))
    implementation(project(":core:database"))
    implementation(project(":core:media"))
    implementation(project(":core:account"))
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":domain"))
    implementation(project(":player"))
    implementation(project(":feature:home"))
    implementation(project(":feature:search"))
    implementation(project(":feature:anime-detail"))
    implementation(project(":feature:player"))
    implementation(project(":feature:party"))
    implementation(project(":feature:favorites"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:schedule"))
    implementation(project(":feature:genre-browse"))
    implementation(project(":feature:movies"))
    implementation(project(":feature:livetv"))
    implementation(project(":feature:admin"))
    "tvImplementation"(project(":feature:tv"))
    "phoneImplementation"(libs.webrtc) // watch-party video and voice (feature/party PartyRtc); kept out of the TV APK
    "phoneImplementation"(libs.webrtc.ui)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler.androidx)
    implementation(libs.hilt.work)
    implementation(libs.work.runtime)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.navigation.compose)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso.core)
}
