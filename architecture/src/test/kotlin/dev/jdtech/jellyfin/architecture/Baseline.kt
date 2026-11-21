package dev.jdtech.jellyfin.architecture

import kotlin.test.fail

/**
 * A list of known violations that may only shrink: `architecture/baseline/<name>`, one entry per
 * line. Lines starting with `#` and blank lines are ignored.
 *
 * The check is exact. A violation that is not listed fails, so nothing new joins the list unseen,
 * and a listed entry that no longer violates fails too, so a cleanup has to delete its line.
 */
class Baseline(val name: String) {
    val file = ProjectTree.root.resolve("architecture/baseline/$name")

    /** Repository-relative path of the baseline file, for the failure messages. */
    val path = "architecture/baseline/$name"

    val entries: Set<String> by lazy {
        check(file.isFile) { "$path does not exist" }
        file
            .readLines()
            .map { it.trim().replace('\\', '/') }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }

    /**
     * Fails on a violation that is not listed, and on a listed entry that no longer violates.
     * [unlisted] and [stale] write the message for one entry.
     */
    fun assertExactly(
        violations: Set<String>,
        unlisted: (String) -> String = { "$it breaks the rule of $path and is not listed in it." },
        stale: (String) -> String = {
            "$it is in the baseline but no longer breaks the rule: delete its line from $path."
        },
    ) {
        val new = (violations - entries).sorted()
        val gone = (entries - violations).sorted()
        if (new.isEmpty() && gone.isEmpty()) return
        fail((new.map(unlisted) + gone.map(stale)).joinToString("\n"))
    }
}
