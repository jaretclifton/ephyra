package dev.jdtech.jellyfin.core.session

import dev.jdtech.jellyfin.domain.ports.RequestWatcher
import dev.jdtech.jellyfin.domain.ports.ServerSession
import dev.jdtech.jellyfin.models.UserId
import java.util.Collections
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * #763: a [ServerSession] that records what is put on it and answers the token check with
 * [tokenCheck]. [afterUse] runs after every [use], for a change that happens right after one.
 */
internal class FakeServerSession(
    @Volatile override var baseUrl: String? = null,
    @Volatile override var accessToken: String? = null,
    @Volatile override var userId: UserId? = null,
) : ServerSession {
    data class Use(val baseUrl: String?, val accessToken: String?, val userId: UserId?)

    val uses: MutableList<Use> = Collections.synchronizedList(mutableListOf())
    var afterUse: (Use) -> Unit = {}

    var tokenCheck: suspend () -> Boolean = { true }
    @Volatile var tokenChecks = 0

    override val unauthorized = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val serverReturned: Flow<Unit> = emptyFlow()
    @Volatile override var requestWatcher: RequestWatcher? = null
    override var probeTimeoutMs: Long = 3_000L
    var probeAnswer: suspend () -> Boolean? = { baseUrl?.let { true } }
    @Volatile var probes = 0

    override fun use(baseUrl: String?, accessToken: String?, userId: UserId?) {
        this.baseUrl = baseUrl
        this.accessToken = accessToken
        this.userId = userId
        val use = Use(baseUrl, accessToken, userId)
        uses += use
        afterUse(use)
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
}
