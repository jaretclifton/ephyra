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
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.repository.DataChangeEvents
import dev.jdtech.jellyfin.repository.MediaRepository
import dev.jdtech.jellyfin.repository.ReloadPolicy
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class FavoritesViewModel
internal constructor(
    private val repository: MediaRepository,
    dataChanges: DataChangeEvents,
    /** CE (#779): where the sections are sorted out; the tests run it on their own dispatcher. */
    private val sectionsDispatcher: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(
        repository: MediaRepository,
        dataChanges: DataChangeEvents = DataChangeEvents(),
    ) : this(repository, dataChanges, Dispatchers.Default)

    private val _state = MutableStateFlow(CollectionState())
    val state = _state.asStateFlow()

    private val serverAwayReload = ServerAwayReload()
    private var loadJob: Job? = null
    private var storeRetryJob: Job? = null

    /**
     * CE (#782): a sync that finished while the screen was not collecting (the app in the
     * background) has still made the list out of date: the screen loads on resume when this says.
     */
    private val reloadPolicy = ReloadPolicy(dataChanges)

    /** Whether coming back to Favorites needs a load, see [ReloadPolicy]. */
    fun shouldReload(): Boolean = switchedTo(sessionUser()) || reloadPolicy.shouldReload()

    /**
     * CE (#866): the user the last load started for. Favorites stays on its stack across a user
     * switch, and [ReloadPolicy] knows nothing of who the list is for.
     */
    private var loadingFor: UserId? = null

    /** CE (#866): the user the client acts for now. */
    private fun sessionUser(): UserId? = runCatching { repository.getUserId() }.getOrNull()

    /** CE (#866): whether the list on screen, or the load running, is for another user. */
    private fun switchedTo(user: UserId?): Boolean =
        user != null && loadingFor != null && loadingFor != user

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
        return start()
    }

    fun loadItems(): Job {
        // CE (#775): the same load twice at once, for the server's return and the error page. CE
        // (#866): not when the one running is for the previous user.
        if (!switchedTo(sessionUser())) {
            loadJob?.let { if (it.isActive && serverAwayReload.runningIsCurrent()) return it }
        }
        // A load for the other state makes way, so it cannot land after this one.
        loadJob?.cancel()
        return start()
    }

    private fun start(): Job {
        // CE (#866): another user's favourites go at once, and the load shows that it runs.
        val user = sessionUser()
        if (switchedTo(user)) _state.value = CollectionState(isLoading = true, userId = user)
        // CE (#866): a load while no one is signed in keeps the last user, or the next switch goes
        // unseen.
        if (user != null) loadingFor = user
        // CE (#779): a newer load stands in for the one more try after a stored answer.
        // A pending one gives its try back: only a retry that ran counts.
        storeRetryJob?.let { if (it.isActive) serverAwayReload.onStoreRetryCancelled() }
        storeRetryJob?.cancel()
        serverAwayReload.begin()
        return load(user).also { loadJob = it }
    }

    private fun load(user: UserId?): Job = viewModelScope.launch {
        val token = reloadPolicy.begin()
        // With content on screen the refresh happens quietly (#251).
        val hasContent = _state.value.sections.isNotEmpty()
        _state.emit(_state.value.copy(isLoading = !hasContent, error = null))

        try {
            val result = repository.getFavoriteItems()
            val items = result.items
            ensureActive()

            val sections = mutableListOf<CollectionSection>()

            withContext(sectionsDispatcher) {
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

            // CE (#775): a failed load keeps the list on screen, and with it what it was. CE
            // (#779): downloads only when Room answered, also before the server is known away.
            reloadPolicy.complete(token)
            _state.emit(
                _state.value.copy(
                    isLoading = false,
                    sections = sections,
                    downloadsOnly = result.fromStore,
                    userId = user,
                )
            )
            if (serverAwayReload.retriesAfterStore(result.fromStore)) {
                storeRetryJob = viewModelScope.launch {
                    delay(ServerAwayReload.STORE_RETRY_DELAY_MS)
                    storeRetryJob = null
                    loadItems()
                }
            }
        } catch (e: CancellationException) {
            // CE (#775): made way for a newer load, which shows its own result.
            throw e
        } catch (e: Exception) {
            _state.emit(_state.value.copy(isLoading = false, error = e))
        }
    }
}
