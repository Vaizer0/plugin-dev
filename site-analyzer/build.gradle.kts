plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core-engine"))
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.koin.core)
}
