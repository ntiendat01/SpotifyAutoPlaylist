plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencyLocking { lockAllConfigurations() }

dependencies {
    api(project(":auth-core"))
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
}
