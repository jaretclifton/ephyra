package dev.jdtech.jellyfin.film.presentation.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.jdtech.jellyfin.film.presentation.ServerAwayReload
import dev.jdtech.jellyfin.models.ItemKind
import dev.jdtech.jellyfin.models.UserId
import dev.jdtech.jellyfin.repository.CurrentAccount
import dev.jdtech.jellyfin.repository.ItemLists
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class SearchViewModel
@Inject
constructor(private val lists: ItemLists, private val account: CurrentAccount) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()

    private var currentJob: Job? = null

    private var itemKinds: List<ItemKind>? = null

    /**
     * CE: searches [itemKinds] instead of the repository default (movies and shows), for screens
     * that group results per kind (TV).
     */
    fun setup(itemKinds: List<ItemKind>) {
        this.itemKinds = itemKinds
    }

    /** The query typed last, which [onServerAwayChanged] searches again. */
    private var lastQuery = ""

    private val serverAwayReload = ServerAwayReload()

    /** CE (#866): the user the client acts for now. */
    private fun sessionUser(): UserId? = runCatching { account.getUserId() }.getOrNull()

    /**
     * CE (#775): the server went away or came back. With the server away the search looks in the
     * downloads, so an open search asks again. The error page's own retry on the server's return
     * lands in the same debounce, so the two send one request.
     */
    fun onServerAwayChanged(away: Boolean) {
        if (serverAwayReload.onServerAway(away) && lastQuery.isNotBlank()) search(lastQuery)
    }

    private fun search(query: String) {
        lastQuery = query
        currentJob?.cancel()
        // CE (#866): the search stays with the Media tab across a user switch. Another user's
        // results go at once, not when this search answers.
        val user = sessionUser()
        val shown = _state.value.userId
        if (user != null && shown != null && shown != user) {
            _state.value = SearchState(loading = query.isNotBlank(), userId = user)
        }
        currentJob = viewModelScope.launch {
            try {
                if (query.isBlank()) {
                    _state.emit(SearchState(items = emptyList(), loading = false, userId = user))
                    return@launch
                }

                // Inside the job, so the next keystroke's cancel also drops this request.
                delay(SEARCH_DEBOUNCE_MS)

                while (true) {
                    serverAwayReload.begin()
                    _state.emit(_state.value.copy(loading = true))
                    val result = lists.getSearchItems(query, itemKinds)
                    // CE (#866): the request runs as whoever the client acts for when it is sent,
                    // which after the debounce or the store retry can be another user than the one
                    // this search began for. Such results are no one's to show: the next search
                    // (the tab's return, a keystroke) asks again.
                    if (sessionUser() != user) break

                    // CE (#779): downloads only when Room answered.
                    _state.emit(
                        SearchState(
                            query = query,
                            items = result.items,
                            loading = false,
                            downloadsOnly = result.fromStore,
                            userId = user,
                        )
                    )
                    // CE (#779): one more try when Room answered before the server was known to be
                    // away; the next keystroke or a change of state cancels it with this job.
                    if (!serverAwayReload.retriesAfterStore(result.fromStore)) break
                    try {
                        delay(ServerAwayReload.STORE_RETRY_DELAY_MS)
                    } catch (e: CancellationException) {
                        // A newer search took its place before it ran: the try is still to come.
                        serverAwayReload.onStoreRetryCancelled()
                        throw e
                    }
                }
            } catch (_: CancellationException) {} catch (e: Exception) {
                Timber.e(e)
                _state.emit(
                    _state.value.copy(
                        query = query,
                        loading = false,
                        error = e,
                        // CE (#866): an unknown user keeps the owner of what is already there.
                        userId = user ?: _state.value.userId,
                    )
                )
            }
        }
    }

    fun onAction(action: SearchAction) {
        when (action) {
            is SearchAction.Search -> {
                search(query = action.query)
            }
            else -> Unit
        }
    }

    companion object {
        internal const val SEARCH_DEBOUNCE_MS = 300L
    }
}
