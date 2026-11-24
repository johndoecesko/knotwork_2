package app.knotwork.android.presentation.ui.settings.provider

import app.knotwork.android.data.engine.gigachat.GigaChatEndpoints
import app.knotwork.android.domain.models.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GigaChat on the provider screen: the address shown read-only is the one the client sends to.
 * Spelled in the screen (it reaches no network client), so this holds the two equal.
 */
class ProviderDetailGigaChatTest {

    @Test
    fun `given GigaChat when its fixed address is shown then it is the client's API address`() {
        assertEquals(GigaChatEndpoints.API_BASE_URL, fixedAddressOf(ProviderId.GigaChat))
    }
}
