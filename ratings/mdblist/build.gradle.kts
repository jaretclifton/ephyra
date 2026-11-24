// CE (#331): the MDBList adapter, the outside service the external ratings on the movie and show
// pages come from. It implements the ratings ports of `application` (RatingsProvider, RatingsCache)
// and carries its own Hilt module. Like the other adapters, only the two apps depend on it.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.ratings.mdblist"
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.timber)

    testImplementation(libs.kotlinx.coroutines.test)
}
