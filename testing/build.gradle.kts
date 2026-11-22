// #771 (docs/architecture/2-enforcement-and-tests.md): FakeMediaServer, ContractLibrary and the
// contract suites of the server ports. Pure Kotlin, for tests only: other modules take it with
// `testImplementation(projects.testing)`, never on an app's runtime classpath (rule R8 in the
// architecture module). The suites are JUnit 4 because the adapter's subclasses need Robolectric,
// which has no Jupiter runner (#699).
plugins { alias(libs.plugins.kotlin.jvm) }

java { toolchain { languageVersion = Versions.JAVA } }

dependencies {
    api(projects.domain)
    api(libs.junit)
    api(libs.kotlin.test)
    api(libs.kotlinx.coroutines.test)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testRuntimeOnly(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

// CI and CLAUDE.md run `./gradlew testDebugUnitTest`. A JVM module has only `test`, so this name
// runs it too.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the fake's contract tests (the test task)."
    dependsOn(tasks.test)
}
