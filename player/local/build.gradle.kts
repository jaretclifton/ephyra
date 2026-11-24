plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.player.local"
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
    implementation(projects.player.core)
    implementation(projects.application)
    implementation(projects.core)
    implementation(projects.domain)
    // #964: MediaHttpClient, the server adapter's client the media is streamed through.
    implementation(projects.http)
    implementation(projects.settings)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libmpv)
    // The engines and PlayerViewModel use OkHttp themselves, not only through MediaHttpClient.
    implementation(libs.okhttp)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // #896: PlaylistManagerNextUpTest builds the real MediaRepositoryImpl. Tests only (rule R9).
    testImplementation(projects.data)
    // #771: FakeMediaServer.
    testImplementation(projects.testing)
}
