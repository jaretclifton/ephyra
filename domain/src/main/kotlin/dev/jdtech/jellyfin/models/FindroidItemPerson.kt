package dev.jdtech.jellyfin.models

import java.util.UUID

data class FindroidItemPersonImage(val uri: String?, val blurHash: String?)

data class FindroidItemPerson(
    val id: UUID,
    val name: String,
    val type: PersonKind,
    val role: String,
    val image: FindroidItemPersonImage,
)

/**
 * Jellyfin 12+ returns one entry per role for the same person
 * ([jellyfin/jellyfin#17466](https://github.com/jellyfin/jellyfin/issues/17466)). Collapse entries
 * with the same [FindroidItemPerson.id] and [FindroidItemPerson.type] into one, joining their
 * distinct non-blank roles with `" / "`.
 *
 * Entries are grouped by id and kind, not by id alone, so a person who is both an actor and a
 * director still appears once for each kind. The first entry's name and image are kept, and order
 * is preserved by first appearance. A list without duplicates is returned unchanged.
 */
fun List<FindroidItemPerson>.mergeDuplicateRoles(): List<FindroidItemPerson> {
    val groups = groupBy { it.id to it.type }
    if (groups.size == size) return this
    return groups.values.map { entries ->
        val first = entries.first()
        if (entries.size == 1) {
            first
        } else {
            first.copy(
                role =
                    entries
                        .map { it.role }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" / ")
            )
        }
    }
}
