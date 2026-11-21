// The app's own models and the ports (#737, docs/architecture/3-target.md). Pure Kotlin: no
// Android, no Jellyfin SDK, no Room, no media3. Rule R5 in the architecture module keeps it that
// way.
plugins { alias(libs.plugins.kotlin.jvm) }

java { toolchain { languageVersion = Versions.JAVA } }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // For the MediaHttpClient port alone. OkHttp is a plain JVM library, so rule R5 allows it.
    implementation(libs.okhttp)

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
