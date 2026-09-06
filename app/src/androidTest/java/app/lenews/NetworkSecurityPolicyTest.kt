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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the system made of `res/xml/network_security_config.xml`, read back from
 * the platform rather than from the file: the app cannot open a cleartext
 * connection to any host on a network, and that holds for the FreshRSS host as
 * much as for any other. The one exception is the loopback interface, where the
 * stub server the other instrumented tests talk to runs, and it is asserted
 * here too so that widening it means changing this test.
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
        // The platform answers true only when cleartext is permitted for every
        // destination, so the loopback exception below does not make it true.
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
    fun cleartextToTheLoopbackInterfaceOnly() {
        // The single exception, asserted rather than merely commented, so that
        // widening it means changing this test. It is what lets the tests below
        // this one run their stub FreshRSS server on the device itself; bytes
        // sent there never leave it.
        assertTrue(policy.isCleartextTrafficPermitted("localhost"))
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))

        // Nothing else on the machine, and nothing that merely ends in the same
        // letters.
        assertFalse(policy.isCleartextTrafficPermitted("notlocalhost"))
        assertFalse(policy.isCleartextTrafficPermitted("localhost.example.org"))
        assertFalse(policy.isCleartextTrafficPermitted("127.0.0.2"))
    }
}
