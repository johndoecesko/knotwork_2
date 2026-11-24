package app.knotwork.android.data.engine.gigachat

import app.knotwork.android.domain.models.GigaChatScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a GigaChat access token is asked for with: the authorization key and the scope.
 *
 * @property authorizationKey The project's authorization key — Base64 of `client_id:client_secret`,
 *   as the GigaChat personal account shows it. A pasted `Basic ` prefix is dropped.
 * @property scope The scope the project was issued for.
 */
class GigaChatCredentials(authorizationKey: String, val scope: GigaChatScope) {

    /** The key as it is sent after `Basic `. */
    val authorizationKey: String = authorizationKey.trim().removePrefix(BASIC_PREFIX).trim()

    /**
     * The cache key of these credentials: a digest, so the cache's map never holds the key itself,
     * and a changed key or scope never reuses a token another pair bought.
     */
    internal val cacheKey: String by lazy {
        MessageDigest.getInstance(DIGEST).digest("${scope.wire}\n${this.authorizationKey}".toByteArray())
            .joinToString("") { HEX_FORMAT.format(it) }
    }

    /** Never prints the key — an instance may end up in a log line or an exception message. */
    override fun toString(): String = "GigaChatCredentials(scope=${scope.wire})"

    private companion object {
        const val BASIC_PREFIX = "Basic "
        const val DIGEST = "SHA-256"
        const val HEX_FORMAT = "%02x"
    }
}

/**
 * An access token as the OAuth endpoint issued it.
 *
 * @property value The Bearer token.
 * @property expiresAtMillis When it stops working, in Unix milliseconds.
 */
data class GigaChatAccessToken(val value: String, val expiresAtMillis: Long) {

    /** Never prints the token. */
    override fun toString(): String = "GigaChatAccessToken(expiresAtMillis=$expiresAtMillis)"

    /** Reading the endpoint's `expires_at`. */
    companion object {
        /** Below this an `expires_at` is read as seconds: 10^10 s is the year 2286, 10^10 ms is 1970. */
        private const val SECONDS_CEILING = 10_000_000_000L
        private const val MILLIS_PER_SECOND = 1_000L

        /**
         * The token of [response], its expiry normalised to milliseconds. The schema says
         * milliseconds; older examples in the documentation show seconds, and reading those as
         * milliseconds would expire every token at once (1970) — so both are accepted.
         */
        internal fun of(response: GigaChatTokenResponse): GigaChatAccessToken = GigaChatAccessToken(
            value = response.accessToken,
            expiresAtMillis = if (response.expiresAt < SECONDS_CEILING) {
                response.expiresAt * MILLIS_PER_SECOND
            } else {
                response.expiresAt
            },
        )
    }
}

/**
 * The GigaChat access tokens of this process, kept in memory only and shared by every client.
 *
 * **Why a singleton outside the client.** `KoogClientFactory` builds a fresh client for every call
 * (the retry wrapper serves one operation), and a token lives about 30 minutes. A cache inside the
 * client would ask the OAuth endpoint again on every node, which is slow and runs into its limit of
 * ten token requests a second. Tokens are never persisted: the authorization key is the credential
 * the user saved, and a token is cheap to ask for again after a restart.
 *
 * **Refresh.** A token is reused until [REFRESH_MARGIN_MS] before it expires, so a request never
 * leaves with a token that dies on the way. A token the API refused with 401 is dropped through
 * [invalidate], and the next [token] asks for a new one.
 *
 * **Thread safety.** One [Mutex] guards the map and is held across the fetch: when several calls
 * find no token at once, the first asks the endpoint and the others wait for, and reuse, its answer
 * instead of each asking. A cancelled fetch releases the lock and caches nothing.
 *
 * @param nowMillis The clock tokens are judged against; replaced only by tests.
 */
@Singleton
class GigaChatTokenCache internal constructor(private val nowMillis: () -> Long) {

    /** The production cache, on the system clock. */
    @Inject
    constructor() : this(System::currentTimeMillis)

    private val mutex = Mutex()

    /** Guarded by [mutex]. */
    private val tokens = HashMap<String, GigaChatAccessToken>()

    /**
     * A token for [credentials] that stays valid for at least [REFRESH_MARGIN_MS]: the cached one,
     * or a new one from [fetch], which is then cached.
     *
     * @param credentials The key and scope the token is for.
     * @param fetch Asks the OAuth endpoint; called at most once per call of this function.
     * @return The token's value.
     * @throws Exception whatever [fetch] throws; nothing is cached then.
     */
    suspend fun token(credentials: GigaChatCredentials, fetch: suspend () -> GigaChatAccessToken): String =
        mutex.withLock {
            val now = nowMillis()
            tokens.values.removeAll { !it.usableAt(now) }
            tokens[credentials.cacheKey]?.let { return@withLock it.value }
            val fresh = fetch()
            // A token that is already inside the margin is used once and not kept.
            if (fresh.usableAt(nowMillis())) tokens[credentials.cacheKey] = fresh
            fresh.value
        }

    /**
     * Drops [token] for [credentials] — the API answered 401 to it. A newer token another call
     * cached in the meantime is kept.
     *
     * @param credentials The key and scope the token was for.
     * @param token The refused token's value.
     */
    suspend fun invalidate(credentials: GigaChatCredentials, token: String) {
        mutex.withLock {
            if (tokens[credentials.cacheKey]?.value == token) tokens.remove(credentials.cacheKey)
        }
    }

    /** Drops every token — for a test, or when the user removes the key. */
    suspend fun clear() {
        mutex.withLock { tokens.clear() }
    }

    private fun GigaChatAccessToken.usableAt(now: Long): Boolean = expiresAtMillis - REFRESH_MARGIN_MS > now

    /** How early a token is refreshed. */
    companion object {
        /** A token is replaced one minute before it expires. */
        const val REFRESH_MARGIN_MS: Long = 60_000L
    }
}
