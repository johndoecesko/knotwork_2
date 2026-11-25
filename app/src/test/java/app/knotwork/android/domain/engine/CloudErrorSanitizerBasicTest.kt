package app.knotwork.android.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [CloudErrorSanitizer] and `Basic` credentials — the shape GigaChat's authorization key travels in.
 */
class CloudErrorSanitizerBasicTest {

    @Test
    fun `given a Basic authorization key in a message when redacted then it is masked`() {
        val message = "Authorization: Basic MTIzNDU2Nzg5MDphYmNkZWZnaA== refused" // gitleaks:allow
        val text = CloudErrorSanitizer.redactSecrets(message)

        assertFalse(text.contains("MTIzNDU2"))
        assertEquals("Authorization: Basic *** refused", text)
    }

    @Test
    fun `given prose that starts with the word Basic when redacted then it is left alone`() {
        val prose = "Basic understanding of the question is required"

        assertEquals(prose, CloudErrorSanitizer.redactSecrets(prose))
    }
}
