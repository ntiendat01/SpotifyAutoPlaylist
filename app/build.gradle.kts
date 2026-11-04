import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// MAJOR.MINOR.PATCH -> MAJOR * 10000 + MINOR * 100 + PATCH; MINOR/PATCH stay below 100
// so versionCode is strictly increasing for every release.
val appVersionName = providers.gradleProperty("replay.versionName").get()
val appVersionCode = run {
    val match = requireNotNull(Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(appVersionName)) {
        "replay.versionName must be MAJOR.MINOR.PATCH, was '$appVersionName'"
    }
    val (major, minor, patch) = match.destructured.toList().map(String::toInt)
    require(minor < 100 && patch < 100) { "MINOR and PATCH must be below 100" }
    major * 10_000 + minor * 100 + patch
}

// Release key material never lives in the repository. CI supplies REPLAY_SIGNING_* environment
// variables; local builds read a properties file outside the repo (default ~/.replay-signing/).
val releaseSigning: Map<String, String>? = run {
    val keys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val fromEnv = keys.associateWith { System.getenv("REPLAY_SIGNING_" + it.uppercase()) }
    if (fromEnv.values.all { !it.isNullOrBlank() }) return@run fromEnv.mapValues { it.value!! }
    val file = providers.gradleProperty("replay.signingProperties").orNull?.let(::file)
        ?: File(System.getProperty("user.home"), ".replay-signing/signing.properties")
    if (!file.isFile) return@run null
    val props = Properties().apply { file.inputStream().use(::load) }
    val missing = keys.filter { props.getProperty(it).isNullOrBlank() }
    require(missing.isEmpty()) { "Signing properties file is missing $missing" }
    keys.associateWith { props.getProperty(it) }
}

// Public Client ID and Developer App owner user ID (AUTH-006) from the ignored spotify.local.properties.
// Builds without the file (CI) get empty values and the app reports "not configured".
val spotifyConfig: Pair<String, String> = run {
    val file = rootProject.file("spotify.local.properties")
    if (!file.isFile) return@run "" to ""
    val props = Properties().apply { file.inputStream().use(::load) }
    val clientId = props.getProperty("spotify.clientId").orEmpty().trim()
    val ownerId = props.getProperty("spotify.ownerUserId").orEmpty().trim()
    require(clientId.matches(Regex("[0-9a-fA-F]{32}"))) { "spotify.clientId must be the 32-hex Client ID" }
    require(ownerId.matches(Regex("[0-9A-Za-z._-]{1,128}"))) { "spotify.ownerUserId must be the /user/<id> value" }
    clientId to ownerId
}

android {
    namespace = "com.spotifyautoplaylist.spike"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()
    buildToolsVersion = libs.versions.android.build.tools.get()

    defaultConfig {
        applicationId = "io.github.ntiendat01.replay"
        minSdk = libs.versions.android.min.sdk.get().toInt()
        targetSdk = libs.versions.android.target.sdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${spotifyConfig.first}\"")
        buildConfigField("String", "SPOTIFY_OWNER_USER_ID", "\"${spotifyConfig.second}\"")
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Without key material AGP emits app-release-unsigned.apk, which cannot be installed.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
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
    lint {
        warningsAsErrors = true
        // Keep reviewed/pinned toolchain versions. Update notices are informational,
        // not functional defects; security, API and resource checks remain strict.
        informational += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
        // M0 is a sideload-only API 36 / OxygenOS 16 probe. This date-sensitive
        // upgrade reminder stays visible; retargeting requires M1/device validation.
        informational += "OldTargetApi"
    }
}

kotlin { jvmToolchain(17) }
dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(project(":auth-core"))
    implementation(project(":data"))
    implementation(project(":spotify-api"))
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
