plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.player.cast"
    compileSdk = Versions.COMPILE_SDK
    compileSdkMinor = Versions.COMPILE_SDK_MINOR

    defaultConfig { minSdk = Versions.MIN_SDK }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    flavorDimensions += "variant"
    productFlavors {
        register("libre") {
            dimension = "variant"
            isDefault = true
        }
        register("proprietary") {
            dimension = "variant"
            matchingFallbacks += listOf("libre")
        }
    }
}

dependencies {
    implementation(projects.application)
    implementation(projects.core)
    implementation(projects.domain)
    implementation(projects.player.core)
    implementation(projects.player.local)
    implementation(projects.settings)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)

    // Only the proprietary flavor has Google Cast; libre gets none of it.
    "proprietaryImplementation"(libs.androidx.mediarouter)
    "proprietaryImplementation"(libs.play.services.cast.framework)

    testImplementation(libs.junit)
}
