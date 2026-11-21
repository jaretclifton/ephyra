// Architecture rules over the source tree (#735, docs/architecture/2-enforcement-and-tests.md).
// Tests only: the rules read the Kotlin files and build files of the other modules with Konsist and
// plain file reading, so nothing has to be compiled first.
plugins { alias(libs.plugins.kotlin.jvm) }

java { toolchain { languageVersion = Versions.JAVA } }

dependencies {
    testImplementation(libs.konsist)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The rules read the tree, not this module's classes: rerun them when a source or build file
    // changes anywhere, and when a baseline changes.
    inputs.dir(layout.projectDirectory.dir("baseline"))
    inputs
        .files(
            rootProject.fileTree(rootProject.projectDir) {
                include("**/*.kt", "**/*.java", "**/build.gradle.kts", "gradle/libs.versions.toml")
                exclude("**/build/**", "buildSrc/**", ".claude/**", ".gradle/**")
            }
        )
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("ephyra.rootDir", rootProject.projectDir.absolutePath)
}

// CI and CLAUDE.md run `./gradlew testDebugUnitTest`. A JVM module has only `test`, so this name
// runs it too.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the architecture rules (the test task)."
    dependsOn(tasks.test)
}
