plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.google.android.material"
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

dependencies {
    implementation(projects.core.ui)

    detektPlugins(libs.detekt.compose)
    detektPlugins(libs.detekt.formatting)
}

kotlin {
    jvmToolchain(libs.versions.jvm.get().toInt())
}
