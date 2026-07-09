plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation(project(":core-engine"))
    implementation(project(":site-analyzer"))
    implementation(project(":pattern-db"))

    implementation(libs.koin.core)
    implementation(libs.okhttp)
}

compose.desktop {
    application {
        mainClass = "dev.pluginstudio.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
            )
            packageName = "PluginDevStudio"
            packageVersion = "1.0.0"
        }
    }
}
