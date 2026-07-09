plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core-engine"))
    implementation(project(":site-analyzer"))
    implementation(libs.snakeyaml)
    implementation(libs.gson)
    implementation(libs.koin.core)
}
