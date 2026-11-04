plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.spotifyautoplaylist.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.spotifyautoplaylist.spike"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-spike"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        // Keep reviewed/pinned toolchain versions. Update notices are informational,
        // not functional defects; security, API and resource checks remain strict.
        informational += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin { jvmToolchain(17) }
dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(project(":auth-core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
