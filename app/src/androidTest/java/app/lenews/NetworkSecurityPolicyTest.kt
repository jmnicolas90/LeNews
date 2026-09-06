/*
 * Copyright (C) 2026 Jean-Michel Nicolas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package app.lenews

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the system made of `res/xml/network_security_config.xml`, read back from
 * the platform rather than from the file: the app cannot open a cleartext
 * connection to any host at all. Not to the FreshRSS host, not to an image
 * host, and not to the device itself — the stub servers the other instrumented
 * tests talk to serve TLS for that reason, and this file is the shipped one
 * rather than a test copy, so what is asserted here is what a phone gets.
 *
 * The rest of the file — that user-installed authorities are trusted for
 * `rss.lan` and for nothing else — has no public API to read it back. The
 * platform's per-host trust decision is only reachable through a real
 * handshake, and a handshake needs a certificate signed by a user-installed
 * authority, which no emulator this suite runs on is guaranteed to have. So
 * that half is verified by hand against the debug FreshRSS account, with the
 * authority in place and then moved out of the way; ticket 19's answer records
 * what happened both times.
 */
@RunWith(AndroidJUnit4::class)
class NetworkSecurityPolicyTest {

    private val policy = NetworkSecurityPolicy.getInstance()

    @Test
    fun noCleartextAnywhere() {
        assertFalse(policy.isCleartextTrafficPermitted)
    }

    @Test
    fun noCleartextToTheFreshRssHost() {
        assertFalse(policy.isCleartextTrafficPermitted("rss.lan"))
    }

    @Test
    fun noCleartextToAnyOtherHost() {
        assertFalse(policy.isCleartextTrafficPermitted("example.org"))
    }

    @Test
    fun noCleartextToTheDeviceItselfEither() {
        // There was an exception here once, for the stub servers the sync tests
        // run on the device. It went: it was reachable outside the tests — an
        // article image or a redirect to http://127.0.0.1:port would have been
        // fetched in the clear, and an account saved with an http loopback URL
        // by an older build would have synced over it. The stub servers serve
        // TLS instead.
        assertFalse(policy.isCleartextTrafficPermitted("localhost"))
        assertFalse(policy.isCleartextTrafficPermitted("127.0.0.1"))

        // What the emulator calls the machine it runs on, which is a network.
        assertFalse(policy.isCleartextTrafficPermitted("10.0.2.2"))
    }
}
