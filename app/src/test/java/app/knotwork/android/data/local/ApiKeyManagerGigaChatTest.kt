package app.knotwork.android.data.local

import android.content.Context
import app.knotwork.android.data.local.crypto.FakeAeadCipher
import app.knotwork.android.data.local.crypto.InMemorySharedPreferences
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.GigaChatScope
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ApiKeyManager]'s GigaChat entries: the key under the wire-id name every provider uses, and the
 * scope — a setting, not a secret — under a name of its own that backups carry with the models.
 */
class ApiKeyManagerGigaChatTest {

    private lateinit var context: Context
    private lateinit var prefs: InMemorySharedPreferences

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        prefs = InMemorySharedPreferences()
        every { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) } returns prefs
    }

    private fun manager() = ApiKeyManager(context, FakeAeadCipher())

    @Test
    fun `given a key and a scope saved when read through a fresh instance then both round-trip`() = runTest {
        manager().setApiKey(CloudProvider.GIGACHAT, "a2V5")
        manager().setGigaChatScope(GigaChatScope.BUSINESS_PREPAID)

        assertEquals("a2V5", manager().getApiKey(CloudProvider.GIGACHAT).first())
        assertEquals(GigaChatScope.BUSINESS_PREPAID, manager().getGigaChatScope().first())
        assertTrue(prefs.values.containsKey("gigachat_api_key"))
        assertTrue(prefs.values.containsKey("gigachat_scope"))
    }

    @Test
    fun `given nothing saved when the scope is read then it is the individuals' one`() = runTest {
        assertEquals(GigaChatScope.PERSONAL, manager().getGigaChatScope().first())
    }

    @Test
    fun `given a scope saved when a backup is taken then the scope is a plain entry and the key a secret`() = runTest {
        val subject = manager()
        subject.setApiKey(CloudProvider.GIGACHAT, "a2V5")
        subject.setGigaChatScope(GigaChatScope.BUSINESS_PAYG)

        assertEquals("GIGACHAT_API_CORP", subject.alwaysEntries()["gigachat_scope"])
        assertFalse(subject.alwaysEntries().containsKey("gigachat_api_key"))
        assertEquals("a2V5", subject.secretEntries()["gigachat_api_key"])
    }

    @Test
    fun `given a backup with a scope when restored then the scope is applied`() = runTest {
        val subject = manager()

        subject.restore(mapOf("gigachat_scope" to "GIGACHAT_API_B2B"), secrets = false)

        assertEquals(GigaChatScope.BUSINESS_PREPAID, subject.getGigaChatScope().first())
    }

    private companion object {
        const val PREFS_NAME = "secure_api_keys_v2"
    }
}
