package dev.jdtech.jellyfin.film.presentation.person

import dev.jdtech.jellyfin.models.Movie
import dev.jdtech.jellyfin.models.Person
import dev.jdtech.jellyfin.models.Show

data class PersonState(
    val person: Person? = null,
    val starredInMovies: List<Movie> = emptyList(),
    val starredInShows: List<Show> = emptyList(),
    val isLoading: Boolean = false,
    val error: Exception? = null,
)
