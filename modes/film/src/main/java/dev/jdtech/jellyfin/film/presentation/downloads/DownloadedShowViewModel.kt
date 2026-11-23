package dev.jdtech.jellyfin.film.presentation.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.core.presentation.downloader.DownloadQueue
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.DownloadFiles
import dev.jdtech.jellyfin.models.Episode
import dev.jdtech.jellyfin.models.ItemId
import dev.jdtech.jellyfin.models.ItemSourceType
import dev.jdtech.jellyfin.models.getLocalSourcesForItems
import dev.jdtech.jellyfin.models.toEpisode
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import dev.jdtech.jellyfin.utils.Downloader
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The downloaded episodes of one show, grouped by season, for managing them in one place. */
@HiltViewModel
class DownloadedShowViewModel
@Inject
constructor(
    private val repository: MediaRepository,
    private val downloadQueue: DownloadQueue,
    private val downloader: Downloader,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloadedShowState())
    val state = _state.asStateFlow()

    fun loadEpisodes(showId: ItemId) {
        viewModelScope.launch {
            val (episodes, sizes) =
                withContext(Dispatchers.IO) {
                    val userId = repository.getUserId()
                    // Episodes with only a `.download` source are still in progress.
                    // CE (#718): the ones the current server downloaded.
                    val allEpisodes =
                        appPreferences.getValue(appPreferences.currentServer)?.let {
                            database.getEpisodesByShowIdOfServer(showId, it)
                        } ?: emptyList()
                    // One query for every episode's sources, reused for the filter, the
                    // conversion and the sizes.
                    val sourcesByEpisode =
                        database.getLocalSourcesForItems(allEpisodes.map { it.id })
                    val episodes =
                        allEpisodes
                            .filter { ep ->
                                sourcesByEpisode[ep.id].orEmpty().any {
                                    !DownloadFiles.isPartial(it.path)
                                }
                            }
                            .map { it.toEpisode(database, userId, sourcesByEpisode) }
                            .sortedWith(compareBy({ it.parentIndexNumber }, { it.indexNumber }))
                    val sizes = episodes.associate { ep ->
                        ep.id to
                            sourcesByEpisode[ep.id].orEmpty().sumOf { src ->
                                File(src.path).takeIf { it.exists() }?.length() ?: 0L
                            }
                    }
                    episodes to sizes
                }
            val seasons =
                episodes
                    .groupBy { it.parentIndexNumber }
                    .map { (number, seasonEpisodes) ->
                        DownloadedSeason(
                            number = number,
                            name = seasonEpisodes.first().seasonName,
                            episodes = seasonEpisodes,
                        )
                    }
            _state.emit(
                DownloadedShowState(seasons = seasons, itemSizes = sizes, isLoading = false)
            )
        }
    }

    fun deleteEpisodes(showId: ItemId, episodes: List<Episode>) {
        downloadDeletionScope.launch {
            for (episode in episodes) {
                downloadQueue.remove(episode.id)
                val source =
                    episode.sources.firstOrNull { it.type == ItemSourceType.LOCAL } ?: continue
                downloader.deleteItem(episode, source)
            }
            loadEpisodes(showId)
        }
    }
}
