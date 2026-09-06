package app.lenews.api.services

import app.lenews.api.services.greader.GReaderCredentials
import org.junit.Test
import kotlin.test.assertEquals

class CredentialsTest {

    @Test
    fun credentialsTest() {
        // named for what it is rather than "credentials", so that the search
        // for a write to the interceptor field this fork deleted stays empty
        val greaderCredentials = GReaderCredentials("token", "https://freshrss.org")

        assertEquals(greaderCredentials.authorization!!, "GoogleLogin auth=token")
        assertEquals(greaderCredentials.url, "https://freshrss.org")
    }
}
