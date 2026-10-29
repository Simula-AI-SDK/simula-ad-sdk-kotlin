package ad.simula.ad.sdk.network

import ad.simula.ad.sdk.privacy.ConsentSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCreatePrivacyTest {
    @Test
    fun `session query omits publisher identity without consent or under COPPA`() {
        for (privacy in listOf(
            ConsentSnapshot(hasPrivacyConsent = false),
            ConsentSnapshot(coppaApplies = true),
            ConsentSnapshot(hasPrivacyConsent = false, coppaApplies = true),
        )) {
            assertEquals("devMode=false", sessionCreateQuery(false, "publisher-user", privacy))
        }
    }

    @Test
    fun `session query includes only an allowed encoded publisher identity`() {
        assertEquals("devMode=false&ppid=user+%2F%26", sessionCreateQuery(false, "user /&", ConsentSnapshot()))
        assertEquals("", sessionCreateQuery(null, null, ConsentSnapshot()))
        assertEquals("", sessionCreateQuery(null, " ", ConsentSnapshot()))
    }
}
