// The Jellyfin adapter (#758, docs/architecture/3-target.md): the only module that declares the
// Jellyfin SDK. It implements the ports of `domain` and carries its own Hilt module. Only the two
// app modules depend on it (a rule in the architecture module); its code depends on neither `data`
// nor `core`.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

java { toolchain { languageVersion = Versions.JAVA } }

android {
    namespace = "dev.jdtech.jellyfin.server.jellyfin"
    compileSdk = Versions.COMPILE_SDK
    buildToolsVersion = Versions.BUILD_TOOLS

    defaultConfig {
        minSdk = Versions.MIN_SDK

        consumerProguardFile("proguard-rules.pro")
    }

    // The build types of the apps: JellyfinApi suffixes the device id with BUILD_TYPE unless it is
    // `release`, so the staging beta and the release app do not log each other out.
    buildTypes {
        named("release") { isMinifyEnabled = false }
        register("staging") { initWith(getByName("release")) }
    }

    buildFeatures { buildConfig = true }

    // The image mapper tests need android.net.Uri, which Robolectric provides on the JVM.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// CE (#739): `-Dgolden.update=true` on the Gradle command line reaches the test JVM, so
// ItemMapperGoldenTest can write its expected files instead of comparing with them.
tasks.withType<Test>().configureEach {
    systemProperty("golden.update", System.getProperty("golden.update") ?: "")
}

dependencies {
    implementation(projects.domain)
    implementation(projects.settings)
    implementation(libs.coil.core)
    implementation(libs.coil.network.cache.control)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.jellyfin.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.timber)

    // The characterization tests of #738 run the real repository over this adapter and a stand-in
    // server, so they pin what goes over the wire end to end. Tests only: the adapter's own code
    // cannot see `data`.
    testImplementation(projects.data)
    testImplementation(libs.androidx.room3.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // #761: the SDK's server discovery logs through SLF4J, which the app gets from core.
    testImplementation(libs.slf4j.api)
}
