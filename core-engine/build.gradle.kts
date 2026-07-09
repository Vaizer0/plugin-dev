plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.luaj)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.snakeyaml)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
}
