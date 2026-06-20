plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.jigen.compose.reordering"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
    }

    kotlin {
        compilerOptions {
            freeCompilerArgs.addAll(
                listOf(
                    "-Xcontext-parameters"
                )
            )
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)

    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.formatting)
}
