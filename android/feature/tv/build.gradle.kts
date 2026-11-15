plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.anistream.feature.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// TV screens only; data and playback logic come from the phone feature modules' ViewModels.
dependencies {
    testImplementation(libs.junit)
    implementation(project(":domain"))
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:account"))
    implementation(project(":core:database"))
    implementation(project(":core:media"))
    implementation(project(":core:trailer"))
    implementation(project(":feature:home"))
    implementation(project(":feature:movies"))
    implementation(project(":feature:anime-detail"))
    implementation(project(":feature:search"))
    implementation(project(":feature:livetv"))
    implementation(project(":feature:favorites"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:player"))
    implementation(project(":feature:party"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.tv.material)
    implementation(libs.core.splashscreen)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.palette)
}
