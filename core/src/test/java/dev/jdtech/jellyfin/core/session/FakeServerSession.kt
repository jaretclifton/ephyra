package dev.jdtech.jellyfin.core.session

import dev.jdtech.jellyfin.domain.ports.ActiveServer
import dev.jdtech.jellyfin.domain.ports.ActiveServerHolder
import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.domain.ports.UserImage
import dev.jdtech.jellyfin.models.UserId
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * #763: a [ServerSession] that records what is put on it and answers the token check with
 * [tokenCheck]. [afterUse] runs after every [use], for a change that happens right after one.
 *
 * #899: it reads [active], as the adapter's session does, and every value put on [active] counts as
 * a [use], whoever puts it there. Setting [accessToken] is a change made elsewhere (a switch the
 * test stages) and is not recorded.
 */
internal class FakeServerSession(
    baseUrl: String? = null,
    accessToken: String? = null,
    userId: UserId? = null,
) : ServerSession {
    data class Use(val baseUrl: String?, val accessToken: String?, val userId: UserId?)

    val active =
        ActiveServerHolder(
            ActiveServer(baseUrl = baseUrl, accessToken = accessToken, userId = userId)
        )

    val uses: MutableList<Use> = Collections.synchronizedList(mutableListOf())
    var afterUse: (Use) -> Unit = {}
    /** The value [accessToken] put on, which is not a use. */
    @Volatile private var notRecorded: ActiveServer? = null

    init {
        // Unconfined: each value is recorded in the write that puts it on, before the write returns
        // (or, for a write made while one is recorded, right after that one).
        CoroutineScope(Dispatchers.Unconfined).launch {
            active.flow.drop(1).collect { value ->
                if (value == notRecorded) {
                    notRecorded = null
                    return@collect
                }
                val use = Use(value.baseUrl, value.accessToken, value.userId)
                uses += use
                afterUse(use)
            }
        }
    }

    override val baseUrl: String?
        get() = active.value.baseUrl

    override var accessToken: String?
        get() = active.value.accessToken
        set(value) {
            val staged = active.value.copy(accessToken = value)
            notRecorded = staged
            active.switchTo(staged)
        }

    override val userId: UserId?
        get() = active.value.userId

    var tokenCheck: suspend () -> Boolean = { true }
    @Volatile var tokenChecks = 0

    override val unauthorized = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val serverReturned: Flow<Unit> = emptyFlow()
    @Volatile override var requestWatcher: RequestWatcher? = null
    override var probeTimeoutMs: Long = 3_000L
    var probeAnswer: suspend () -> Boolean? = { baseUrl?.let { true } }
    @Volatile var probes = 0

    fun use(baseUrl: String?, accessToken: String?, userId: UserId?) {
        active.onSameServer {
            it.copy(baseUrl = baseUrl, accessToken = accessToken, userId = userId)
        }
    }

    override suspend fun probe(): Boolean? {
        probes++
        return probeAnswer()
    }

    override suspend fun isTokenValid(): Boolean {
        tokenChecks++
        return tokenCheck()
    }

    /** How many collectors [keepAlive] has right now. */
    val keepingAlive = java.util.concurrent.atomic.AtomicInteger()

    override fun keepAlive(): Flow<Nothing> = flow {
        keepingAlive.incrementAndGet()
        try {
            awaitCancellation()
        } finally {
            keepingAlive.decrementAndGet()
        }
    }

    override fun userImageUrl(userId: UserId, tag: String): String? = null

    override suspend fun updateDeviceName(name: String) = Unit

    override suspend fun autoPlayNextEpisode(): Boolean? = null

    override suspend fun currentUserImage(): UserImage = UserImage(checkNotNull(userId), null)
}
