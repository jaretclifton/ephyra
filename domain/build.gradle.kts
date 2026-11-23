// The app's own models and the ports (#737, docs/architecture/3-target.md). Pure Kotlin: no
// Android, no Jellyfin SDK, no Room, no media3. Rule R5 in the architecture module keeps it that
// way.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // For Chapter (see below).
    alias(libs.plugins.kotlin.serialization)
}

java { toolchain { languageVersion = Versions.JAVA } }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Chapter is @Serializable: the Room converters in data store the chapters as JSON.
    implementation(libs.kotlinx.serialization.json)
    // For the MediaHttpClient port alone. It returns an OkHttpClient, hence `api`: a module that
    // uses the port compiles without declaring OkHttp. A plain JVM library, so R5 allows it.
    api(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

// CI and CLAUDE.md run `./gradlew testDebugUnitTest`. A JVM module has only `test`, so this name
// runs it too.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the domain tests (the test task)."
    dependsOn(tasks.test)
}
