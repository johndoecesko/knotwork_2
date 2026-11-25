package app.knotwork.android.data.engine

import app.knotwork.android.data.engine.gigachat.GigaChatEndpoints
import app.knotwork.android.data.engine.gigachat.GigaChatLLMClient
import app.knotwork.android.data.engine.gigachat.GigaChatTokenCache
import app.knotwork.android.data.engine.retry.CloudRetryWrapper
import app.knotwork.android.domain.engine.CloudClientUnavailability
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.models.GigaChatScope
import app.knotwork.android.domain.repositories.ApiKeyRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KoogClientFactory] for [CloudProvider.GIGACHAT]: the app's own client, built from the saved
 * authorization key and scope, behind the same admission as every hosted provider.
 */
class KoogClientFactoryGigaChatTest {

    private val localOnly = MutableStateFlow(false)

    private val settings = mockk<SettingsRepository>(relaxed = true) {
        every { blockNetworkFromLocalModel } returns localOnly
        every { approvedCleartextOrigins } returns flowOf(emptySet())
        every { cloudRetryMaxAttempts } returns flowOf(1)
    }

    private fun factory(key: String?) = KoogClientFactory(
        apiKeyRepository = mockk<ApiKeyRepository> {
            every { getApiKey(CloudProvider.GIGACHAT) } returns flowOf(key)
            every { getModel(CloudProvider.GIGACHAT) } returns flowOf(null)
            every { getGigaChatScope() } returns flowOf(GigaChatScope.BUSINESS_PAYG)
        },
        modelNetworkGate = ModelNetworkGate(settings),
        retryWrapper = CloudRetryWrapper(settings),
        gigaChatTokens = GigaChatTokenCache(),
    )

    @Test
    fun `given an authorization key when a client is asked for then the GigaChat client is built`() = runTest {
        val client = factory("a2V5").createClient(CloudProvider.GIGACHAT)

        assertTrue("got $client", client is GigaChatLLMClient)
    }

    @Test
    fun `given no key when a client is asked for then none, and the cause is the key`() = runTest {
        val factory = factory(null)

        assertNull(factory.createClient(CloudProvider.GIGACHAT))
        assertEquals(CloudClientUnavailability.MissingCredentials, factory.unavailabilityOf(CloudProvider.GIGACHAT))
    }

    @Test
    fun `given Block network from local model when a client is asked for then GigaChat is refused`() = runTest {
        localOnly.value = true
        val factory = factory("a2V5")

        assertNull(factory.createClient(CloudProvider.GIGACHAT))
        assertEquals(
            CloudClientUnavailability.BlockedByLocalOnlyMode,
            factory.unavailabilityOf(CloudProvider.GIGACHAT),
        )
    }

    @Test
    fun `given the connection checker when it names GigaChat's listing then it is the API's model list`() {
        assertEquals(
            "${GigaChatEndpoints.API_BASE_URL}/${GigaChatEndpoints.MODELS_PATH}",
            KoogProviderConnectionChecker.listingUrl(CloudProvider.GIGACHAT, baseUrl = null),
        )
    }
}
