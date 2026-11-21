package dev.jdtech.jellyfin.film.presentation.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.Constants
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.ServerAwayReload
import dev.jdtech.jellyfin.film.presentation.collection.CollectionState
import dev.jdtech.jellyfin.models.CollectionSection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.UiText
import dev.jdtech.jellyfin.repository.JellyfinRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class FavoritesViewModel @Inject constructor(private val repository: JellyfinRepository) :
    ViewModel() {
    private val _state = MutableStateFlow(CollectionState())
    val state = _state.asStateFlow()

    private val serverAwayReload = ServerAwayReload()
    private var loadJob: Job? = null

    /**
     * CE (#775): the server went away or came back. With the server away the favourites are the
     * downloaded ones, so the list loads again.
     */
    fun onServerAwayChanged(away: Boolean) {
        if (serverAwayReload.onServerAway(away)) loadItems()
    }

    /**
     * CE (#775): the sync has sent changes made while the server was away, such as a favourite set
     * offline. A load that is running may have asked before they arrived, so it makes way for a new
     * one.
     */
    fun onSynced(): Job {
        loadJob?.cancel()
        return load(serverAwayReload.begin()).also { loadJob = it }
    }

    fun loadItems(): Job {
        // CE (#775): the same load twice at once, for the server's return and the error page.
        loadJob?.let { if (it.isActive && serverAwayReload.runningIsCurrent()) return it }
        val away = serverAwayReload.begin()
        return load(away).also { loadJob = it }
    }

    private fun load(away: Boolean): Job = viewModelScope.launch {
        // With content on screen the refresh happens quietly (#251).
        val hasContent = _state.value.sections.isNotEmpty()
        _state.emit(_state.value.copy(isLoading = !hasContent, error = null))

        try {
            val items = repository.getFavoriteItems()

            val sections = mutableListOf<CollectionSection>()

            withContext(Dispatchers.Default) {
                CollectionSection(
                        Constants.FAVORITE_TYPE_MOVIES,
                        UiText.StringResource(CoreR.string.movies_label),
                        items.filterIsInstance<FindroidMovie>(),
                    )
                    .let {
                        if (it.items.isNotEmpty()) {
                            sections.add(it)
                        }
                    }
                CollectionSection(
                        Constants.FAVORITE_TYPE_SHOWS,
                        UiText.StringResource(CoreR.string.shows_label),
                        items.filterIsInstance<FindroidShow>(),
                    )
                    .let {
                        if (it.items.isNotEmpty()) {
                            sections.add(it)
                        }
                    }
                CollectionSection(
                        Constants.FAVORITE_TYPE_EPISODES,
                        UiText.StringResource(CoreR.string.episodes_label),
                        items.filterIsInstance<FindroidEpisode>(),
                    )
                    .let {
                        if (it.items.isNotEmpty()) {
                            sections.add(it)
                        }
                    }
            }

            // CE (#775): a failed load keeps the list on screen, and with it what it was.
            _state.emit(
                _state.value.copy(isLoading = false, sections = sections, downloadsOnly = away)
            )
        } catch (e: CancellationException) {
            // CE (#775): made way for the load after the sync, which shows its own result.
            throw e
        } catch (e: Exception) {
            _state.emit(_state.value.copy(isLoading = false, error = e))
        }
    }
}
