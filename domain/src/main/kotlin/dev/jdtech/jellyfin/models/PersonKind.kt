package dev.jdtech.jellyfin.models

/**
 * What a person did on an item. All 26 constants of the server's kinds, by name: people are grouped
 * by id and kind (`mergeDuplicateRoles`), so kinds must not collapse into one. Nothing stores or
 * matches a serial name of this one, so it has none.
 */
enum class PersonKind {
    UNKNOWN,
    ACTOR,
    DIRECTOR,
    COMPOSER,
    WRITER,
    GUEST_STAR,
    PRODUCER,
    CONDUCTOR,
    LYRICIST,
    ARRANGER,
    ENGINEER,
    MIXER,
    REMIXER,
    CREATOR,
    ARTIST,
    ALBUM_ARTIST,
    AUTHOR,
    ILLUSTRATOR,
    PENCILLER,
    INKER,
    COLORIST,
    LETTERER,
    COVER_ARTIST,
    EDITOR,
    TRANSLATOR,
    NARRATOR,
}
