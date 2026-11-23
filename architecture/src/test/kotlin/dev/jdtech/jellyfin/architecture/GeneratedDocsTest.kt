package dev.jdtech.jellyfin.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The parts of the architecture docs that are read from the tree (#928): the modules with their
 * dependencies, from the build files, and the list of rules, from the tests of this module. Each
 * file is compared with what the tree says now, so a PR that adds a module or a dependency, or a
 * rule, fails until it regenerates them:
 * ```
 * EPHYRA_WRITE_GENERATED=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'
 * ```
 *
 * (PowerShell: `$env:EPHYRA_WRITE_GENERATED=1; ./gradlew …`). The model files next to the generated
 * one (the `.c4` files in `docs/architecture/model/`) are written by hand and refer to its
 * elements.
 */
class GeneratedDocsTest {

    @Test fun `the module model is up to date`() = check(MODEL, ArchitectureModel.likeC4())

    @Test
    fun `the module page is up to date`() = check(MODULES_PAGE, ArchitectureModel.modulesPage())

    @Test fun `the rules page is up to date`() = check(RULES_PAGE, ArchitectureModel.rulesPage())

    @Test
    fun `every module has a layer`() {
        val missing = ArchitectureModel.modules().filter { it !in ArchitectureModel.LAYERS }
        if (missing.isNotEmpty()) {
            fail(
                "No layer for ${missing.joinToString()}: add it to ArchitectureModel.LAYERS, " +
                    "then regenerate the docs (see GeneratedDocsTest)."
            )
        }
    }

    @Test
    fun `module dependencies are read from every way to write them`() {
        val deps =
            ArchitectureModel.dependenciesIn(
                mapOf(
                    "a" to
                        """
                        dependencies {
                            implementation(projects.b)
                            api(project(":c"))
                            testImplementation(projects.d)
                            // implementation(projects.e)
                        }
                        """
                            .trimIndent(),
                    "b" to "",
                    "c" to "",
                    "d" to "",
                    "e" to "",
                )
            )
        assertEquals(
            listOf(
                ArchitectureModel.Dependency("a", "b", test = false),
                ArchitectureModel.Dependency("a", "c", test = false),
                ArchitectureModel.Dependency("a", "d", test = true),
            ),
            deps,
        )
    }

    private fun check(path: String, expected: String) {
        val file = ProjectTree.root.resolve(path)
        if (System.getenv(WRITE) == "1") {
            file.parentFile.mkdirs()
            file.writeText(expected)
            return
        }
        val actual = if (file.isFile) file.readText().replace("\r\n", "\n") else ""
        if (actual != expected) {
            fail(
                "$path is out of date. Regenerate it with " +
                    "`$WRITE=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'` " +
                    "and commit the result. Don't edit it by hand."
            )
        }
    }

    private companion object {
        const val WRITE = "EPHYRA_WRITE_GENERATED"
        const val MODEL = "docs/architecture/model/modules.generated.c4"
        const val MODULES_PAGE = "docs/architecture/modules.md"
        const val RULES_PAGE = "docs/architecture/rules.md"
    }
}

/** Renders the modules and rules of the tree as a LikeC4 model and as Markdown. */
object ArchitectureModel {

    /**
     * The layer of each module: how the model groups and colours it. A new module fails `every
     * module has a layer` until it is added here.
     */
    val LAYERS: Map<String, Layer> =
        mapOf(
            "app/phone" to Layer.APP,
            "app/tv" to Layer.APP,
            "modes/film" to Layer.FEATURE,
            "setup" to Layer.FEATURE,
            "settings" to Layer.FEATURE,
            "player/local" to Layer.FEATURE,
            "player/cast" to Layer.FEATURE,
            "player/core" to Layer.FEATURE,
            "core" to Layer.FEATURE,
            "downloads" to Layer.FEATURE,
            "application" to Layer.APPLICATION,
            "domain" to Layer.DOMAIN,
            "data" to Layer.ADAPTER,
            "server/jellyfin" to Layer.ADAPTER,
            "testing" to Layer.TOOLING,
            "architecture" to Layer.TOOLING,
            "baselineprofile" to Layer.TOOLING,
            "baselineprofile-tv" to Layer.TOOLING,
        )

    enum class Layer(val kind: String, val title: String) {
        APP("app", "Apps (composition root)"),
        FEATURE("feature", "Features"),
        APPLICATION("application", "Application"),
        DOMAIN("domain", "Domain"),
        ADAPTER("adapter", "Adapters"),
        TOOLING("tooling", "Tests and tooling"),
    }

    data class Dependency(val from: String, val to: String, val test: Boolean)

    /** The module paths, without the root build file, sorted. */
    fun modules(): List<String> = ProjectTree.moduleBuildFiles.keys.filter { it != "." }.sorted()

    fun dependencies(): List<Dependency> =
        dependenciesIn(
            ProjectTree.moduleBuildFiles.filterKeys { it != "." }.mapValues { it.value.readText() }
        )

    /**
     * The dependencies between the modules of [buildFiles] (module path to build file text): one
     * per pair, production when any mention of the module is outside a test configuration.
     */
    fun dependenciesIn(buildFiles: Map<String, String>): List<Dependency> =
        buildFiles.keys.sorted().flatMap { from ->
            buildFiles.keys.sorted().mapNotNull { to ->
                if (from == to) return@mapNotNull null
                val configurations =
                    BuildFileDependencies.configurationsOf(
                        buildFiles.getValue(from),
                        BuildFileDependencies.markerOf(to),
                    )
                if (configurations.isEmpty()) null
                else Dependency(from, to, configurations.all(BuildFileDependencies::isTestOnly))
            }
        }

    /** The LikeC4 name of a module: `server/jellyfin` → `serverJellyfin`. */
    fun idOf(module: String): String =
        module
            .split('/', '-')
            .mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercase) }
            .joinToString("")

    private fun layerOf(module: String): Layer = LAYERS[module] ?: Layer.TOOLING

    private fun header(comment: String) =
        "$comment Generated by architecture/…/GeneratedDocsTest.kt from the build files and the rule tests. Don't edit:\n" +
            "$comment EPHYRA_WRITE_GENERATED=1 ./gradlew :architecture:test --rerun --tests '*GeneratedDocsTest*'\n"

    fun likeC4(): String = buildString {
        append(header("//"))
        append("\nmodel {\n  extend ephyra {\n")
        for (module in modules()) {
            val layer = layerOf(module)
            append("    ${idOf(module)} = ${layer.kind} '$module' {\n")
            append("      description '${layer.title}'\n")
            append("      link https://github.com/midasvo/ephyra/tree/main/$module 'Source'\n")
            append("    }\n")
        }
        append("\n")
        for (dep in dependencies()) {
            val arrow = if (dep.test) "-[test]->" else "->"
            append("    ${idOf(dep.from)} $arrow ${idOf(dep.to)}\n")
        }
        append("  }\n}\n")
    }

    fun modulesPage(): String = buildString {
        val deps = dependencies()
        append("# Modules\n\n")
        append(header("<!--").lines().filter { it.isNotEmpty() }.joinToString("\n") { "$it -->" })
        append("\n\n")
        append(
            "The Gradle modules and what each depends on, read from their `build.gradle.kts`. " +
                "The graph leaves out test-only dependencies (`testImplementation`, …) and the " +
                "arrows of the apps, which depend on every module (see the table). " +
                "The rules that guard these arrows are in [rules.md](rules.md); the interactive " +
                "model is [model/](model/).\n\n"
        )
        append("```mermaid\nflowchart TD\n")
        for (layer in Layer.entries.filter { it != Layer.TOOLING }) {
            append("  subgraph ${layer.kind}[\"${layer.title}\"]\n")
            for (module in modules().filter { layerOf(it) == layer }) {
                append("    ${idOf(module)}[\"$module\"]\n")
            }
            append("  end\n")
        }
        val shown = modules().filter { layerOf(it) != Layer.TOOLING }.toSet()
        val arrows = deps.filter {
            !it.test && it.from in shown && it.to in shown && layerOf(it.from) != Layer.APP
        }
        for (dep in arrows) {
            append("  ${idOf(dep.from)} --> ${idOf(dep.to)}\n")
        }
        append("```\n\n")
        append("| Module | Layer | Depends on | Test-only |\n|---|---|---|---|\n")
        for (module in modules()) {
            val prod = deps.filter { it.from == module && !it.test }.joinToString { "`${it.to}`" }
            val test = deps.filter { it.from == module && it.test }.joinToString { "`${it.to}`" }
            append("| `$module` | ${layerOf(module).title} | $prod | $test |\n")
        }
    }

    data class Rule(val id: String, val tests: List<String>, val files: List<String>)

    private val RULE_TEST = Regex("""fun `(R\d+) ([^`]*)`""")

    fun rules(): List<Rule> {
        val dir = "architecture/src/test/kotlin/dev/jdtech/jellyfin/architecture"
        val found = mutableMapOf<String, Pair<MutableSet<String>, MutableList<String>>>()
        ProjectTree.root
            .resolve(dir)
            .listFiles { f: File -> f.name.endsWith(".kt") }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                RULE_TEST.findAll(file.readText()).forEach { match ->
                    val (id, name) = match.destructured
                    val entry = found.getOrPut(id) { sortedSetOf<String>() to mutableListOf() }
                    entry.first += "$dir/${file.name}"
                    entry.second += name
                }
            }
        return found.entries
            .sortedBy { it.key.removePrefix("R").toInt() }
            .map { (id, value) -> Rule(id, value.second, value.first.toList()) }
    }

    fun rulesPage(): String = buildString {
        append("# Architecture rules\n\n")
        append(header("<!--").lines().filter { it.isNotEmpty() }.joinToString("\n") { "$it -->" })
        append("\n\n")
        append(
            "The Konsist rules in `architecture/`, read from the names of their tests. Run them " +
                "with `./gradlew :architecture:test` (also part of `testDebugUnitTest`). When one " +
                "fails, fix the code: the two files in `architecture/baseline/` are allowlists, " +
                "not debt. Why each rule exists is in [README.md](README.md#the-rules) and the " +
                "ADRs.\n\n"
        )
        append("| Rule | Says | Tests |\n|---|---|---|\n")
        for (rule in rules()) {
            val links = rule.files.joinToString { "[${it.substringAfterLast('/')}](../../$it)" }
            append("| ${rule.id} | ${rule.tests.first().replace("|", "\\|")} | $links |\n")
        }
    }
}
