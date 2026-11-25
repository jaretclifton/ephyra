// CE (#965): the adapters over the device. They implement ports of `domain` and `application` by
// asking Android: the codec probe (`DeviceCodecs`, the decoders and the display that the device
// profile is built from) and the connectivity check (`NetworkConnectivity`). It carries its own
// Hilt module. Like the other adapters, only the two apps depend on it, and it depends on no
// feature module (R18).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.device"
    compileSdk = Versions.COMPILE_SDK
    compileSdkMinor = Versions.COMPILE_SDK_MINOR
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig { minSdk = Versions.MIN_SDK }

    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }
}

dependencies {
    implementation(projects.application)
    implementation(projects.domain)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
