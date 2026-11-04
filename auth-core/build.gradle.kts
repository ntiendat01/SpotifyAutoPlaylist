plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
}
