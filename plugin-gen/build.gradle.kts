plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":core-engine"))
    api(project(":site-analyzer"))
    implementation(project(":pattern-db"))
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
}
