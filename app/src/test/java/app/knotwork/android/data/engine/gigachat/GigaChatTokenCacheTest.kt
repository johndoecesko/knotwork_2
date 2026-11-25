package app.knotwork.android.data.engine.gigachat

import app.knotwork.android.domain.models.GigaChatScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [GigaChatTokenCache]: a token is reused until a minute before it expires, a refused one is
 * dropped, concurrent callers share one fetch, and credentials never mix.
 */
class GigaChatTokenCacheTest {

    private var now = 1_000_000_000_000L
    private val cache = GigaChatTokenCache { now }
    private val personal = GigaChatCredentials("a2V5LW9uZQ==", GigaChatScope.PERSONAL)
    private val fetches = AtomicInteger()

    private fun issuing(value: String, lifetimeMs: Long = THIRTY_MINUTES): suspend () -> GigaChatAccessToken = {
        fetches.incrementAndGet()
        GigaChatAccessToken(value, now + lifetimeMs)
    }

    @Test
    fun `given a fresh token when asked again before the margin then it is reused`() = runTest {
        assertEquals("t1", cache.token(personal, issuing("t1")))
        now += THIRTY_MINUTES - GigaChatTokenCache.REFRESH_MARGIN_MS - 1

        assertEquals("t1", cache.token(personal, issuing("t2")))
        assertEquals(1, fetches.get())
    }

    @Test
    fun `given a token inside the last minute when asked then a new one is fetched`() = runTest {
        cache.token(personal, issuing("t1"))
        now += THIRTY_MINUTES - GigaChatTokenCache.REFRESH_MARGIN_MS

        assertEquals("t2", cache.token(personal, issuing("t2")))
    }

    @Test
    fun `given a refused token when invalidated then the next call fetches, and a newer token survives`() = runTest {
        cache.token(personal, issuing("t1"))
        cache.invalidate(personal, "t1")
        assertEquals("t2", cache.token(personal, issuing("t2")))

        cache.invalidate(personal, "t1") // stale: another call already replaced it
        assertEquals("t2", cache.token(personal, issuing("t3")))
    }

    @Test
    fun `given callers at once when no token is cached then one fetch serves them all`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val slow: suspend () -> GigaChatAccessToken = {
            fetches.incrementAndGet()
            gate.await()
            GigaChatAccessToken("shared", now + THIRTY_MINUTES)
        }
        val calls = List(5) { async { cache.token(personal, slow) } }
        gate.complete(Unit)

        assertEquals(List(5) { "shared" }, calls.awaitAll())
        assertEquals(1, fetches.get())
    }

    @Test
    fun `given another scope or key when asked then a token of its own is fetched`() = runTest {
        cache.token(personal, issuing("pers"))

        val corp = GigaChatCredentials("a2V5LW9uZQ==", GigaChatScope.BUSINESS_PAYG)
        val other = GigaChatCredentials("a2V5LXR3bw==", GigaChatScope.PERSONAL)
        assertEquals("corp", cache.token(corp, issuing("corp")))
        assertEquals("other", cache.token(other, issuing("other")))
        assertEquals(3, fetches.get())
    }

    @Test
    fun `given a failed fetch when asked again then nothing was cached`() = runTest {
        try {
            cache.token(personal) { throw IOException("down") }
            fail("the fetch's failure is the caller's")
        } catch (_: IOException) {
            // expected
        }
        assertEquals("t1", cache.token(personal, issuing("t1")))
    }

    @Test
    fun `given an expiry in seconds or milliseconds when read then both mean the same moment`() {
        val seconds = GigaChatAccessToken.of(GigaChatTokenResponse("t", 1_739_784_663L))
        val millis = GigaChatAccessToken.of(GigaChatTokenResponse("t", 1_739_784_663_000L))

        assertEquals(millis.expiresAtMillis, seconds.expiresAtMillis)
    }

    @Test
    fun `given credentials when printed or keyed then the key never shows`() {
        val credentials = GigaChatCredentials("Basic c2VjcmV0LWtleQ==", GigaChatScope.PERSONAL) // gitleaks:allow

        assertEquals("c2VjcmV0LWtleQ==", credentials.authorizationKey)
        assertFalse(credentials.toString().contains("c2VjcmV0"))
        assertFalse(credentials.cacheKey.contains("c2VjcmV0"))
        val otherScope = GigaChatCredentials("c2VjcmV0LWtleQ==", GigaChatScope.BUSINESS_PAYG)
        assertNotEquals(credentials.cacheKey, otherScope.cacheKey)
    }

    private companion object {
        const val THIRTY_MINUTES = 30 * 60 * 1000L
    }
}
