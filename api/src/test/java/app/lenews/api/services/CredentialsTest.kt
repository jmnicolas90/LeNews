package app.lenews.api.services

import app.lenews.api.services.greader.GReaderCredentials
import org.junit.Test
import kotlin.test.assertEquals

class CredentialsTest {

    @Test
    fun credentialsTest() {
        val credentials = GReaderCredentials("token", "https://freshrss.org")

        assertEquals(credentials.authorization!!, "GoogleLogin auth=token")
        assertEquals(credentials.url, "https://freshrss.org")
    }
}
