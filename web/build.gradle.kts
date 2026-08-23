plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("dev.pluginstudio.web.MainKt")
}

dependencies {
    implementation(project(":core-engine"))
    implementation(project(":site-analyzer"))
    implementation(project(":pattern-db"))
    implementation(project(":plugin-gen"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.core)
}
