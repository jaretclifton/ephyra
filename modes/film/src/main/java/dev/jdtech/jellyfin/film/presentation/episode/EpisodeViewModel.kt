package dev.jdtech.jellyfin.film.presentation.episode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.domain.VideoMetadataParser
import dev.jdtech.jellyfin.film.presentation.detailSections
import dev.jdtech.jellyfin.film.presentation.shownIn
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemPerson
import dev.jdtech.jellyfin.models.PersonKind
import dev.jdtech.jellyfin.models.mergeDuplicateRoles
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.repository.ReloadPolicy
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@HiltViewModel
class EpisodeViewModel
internal constructor(
    private val repository: MediaRepository,
    private val appPreferences: AppPreferences,
    private val videoMetadataParser: VideoMetadataParser,
    dataChanges: DataChangeEvents = DataChangeEvents(),
    /** CE (#809): where the actors are sorted out; the tests run it on their own dispatcher. */
    private val defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        repository: MediaRepository,
        appPreferences: AppPreferences,
        videoMetadataParser: VideoMetadataParser,
        dataChanges: DataChangeEvents = DataChangeEvents(),
    ) : this(repository, appPreferences, videoMetadataParser, dataChanges, Dispatchers.Default)

    private val _state = MutableStateFlow(EpisodeState())
    val state = _state.asStateFlow()

    private var episodeIdOrNull: ItemId? = null
    private val episodeId: ItemId
        get() = checkNotNull(episodeIdOrNull) { "episodeId is read before it is loaded" }

    private val reloadPolicy = ReloadPolicy(dataChanges)
    private var loadJob: Job? = null

    /** Whether coming back to the screen needs a load (#251), see [ReloadPolicy]. */
    fun shouldReload(episodeId: ItemId, stopEventFollows: Boolean = false): Boolean =
        episodeIdOrNull == null ||
            this.episodeId != episodeId ||
            reloadPolicy.shouldReload(stopEventFollows)

    fun loadEpisode(episodeId: ItemId) {
        episodeIdOrNull = episodeId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val token = reloadPolicy.begin()
            // #769: read on every load, as the server may have changed since.
            _state.emit(
                _state.value.copy(
                    error = null,
                    cappedDownloads = repository.capabilities.cappedDownloadTranscode,
                )
            )
            try {
                // The preference doesn't depend on the episode, so it is read while it loads.
                val (episode, displayCastAndCrew, useEpisodeImages) =
                    coroutineScope {
                        val episodeAsync = async { repository.getEpisode(episodeId) }
                        val castAsync = async {
                            appPreferences.getValue(appPreferences.displayCastAndCrew)
                        }
                        val imagesAsync = async {
                            appPreferences.getValue(appPreferences.homeEpisodeImages)
                        }
                        Triple(episodeAsync.await(), castAsync.await(), imagesAsync.await())
                    }
                val videoMetadata =
                    episode.sources.firstOrNull()?.let { videoMetadataParser.parse(it) }
                val actors = if (displayCastAndCrew) getActors(episode) else emptyList()
                _state.emit(
                    _state.value.copy(
                        episode = episode.shownIn(appPreferences.detailSections()),
                        videoMetadata = videoMetadata,
                        actors = actors,
                        useEpisodeImages = useEpisodeImages,
                    )
                )
                reloadPolicy.complete(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.emit(_state.value.copy(error = e))
            }
        }
    }

    private suspend fun getActors(item: Episode): List<ItemPerson> {
        return withContext(defaultDispatcher) {
            item.people.mergeDuplicateRoles().filter { it.type == PersonKind.ACTOR }
        }
    }

    /** Marks the episode played or unplayed and updates the page in place (#252), no full load. */
    private fun setPlayed(played: Boolean) {
        viewModelScope.launch {
            try {
                if (played) repository.markAsPlayed(episodeId)
                else repository.markAsUnplayed(episodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(
                    e,
                    if (played) "Failed to mark as played" else "Failed to unmark as played",
                )
                return@launch
            }
            reloadPolicy.changedHere()
            _state.update { state -> state.copy(episode = state.episode?.copy(played = played)) }
        }
    }

    /** Marks the episode favourite or not and updates the page in place (#252), no full load. */
    private fun setFavorite(favorite: Boolean) {
        viewModelScope.launch {
            try {
                if (favorite) repository.markAsFavorite(episodeId)
                else repository.unmarkAsFavorite(episodeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(
                    e,
                    if (favorite) "Failed to mark as favorite" else "Failed to unmark as favorite",
                )
                return@launch
            }
            reloadPolicy.changedHere()
            _state.update { state ->
                state.copy(episode = state.episode?.copy(favorite = favorite))
            }
        }
    }

    fun onAction(action: EpisodeAction) {
        when (action) {
            is EpisodeAction.OnRetryClick -> loadEpisode(episodeId)
            is EpisodeAction.MarkAsPlayed -> setPlayed(true)
            is EpisodeAction.UnmarkAsPlayed -> setPlayed(false)
            is EpisodeAction.MarkAsFavorite -> setFavorite(true)
            is EpisodeAction.UnmarkAsFavorite -> setFavorite(false)
            else -> Unit
        }
    }
}
