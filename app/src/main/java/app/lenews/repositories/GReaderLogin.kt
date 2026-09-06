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

package app.lenews.repositories

import app.lenews.api.HttpClients
import app.lenews.api.services.Credentials
import app.lenews.api.services.greader.GReaderDataSource
import app.lenews.db.entities.account.Account

/**
 * Logs in, in the order the two clients force.
 *
 * ClientLogin is the one call with no token to send, so it goes out on the
 * plain client — the client that has no interceptor which could add an
 * authorization header at all. It is asked for by name rather than taken from
 * whatever the authenticated client happens to be, because the account handed
 * in here may still carry a token: the credentials screen can edit the server
 * URL and keeps the rest of the account, so that token may have been issued by
 * a different server from the one this login is aimed at. Sending it there
 * would hand one server's token to another.
 *
 * So the previous token is dropped before anything goes out, and the
 * authenticated client is bound only once the destination has issued a token of
 * its own. A login that fails therefore leaves the authenticated client
 * unbound — there is no token to bind it with — rather than leaving the
 * previous one in place.
 *
 * From the moment the token is known, every call needs a client that carries
 * it. That is a different client, because a client's credentials never change
 * once it is built, so the data source is asked for twice: once on the plain
 * client and once on the authenticated one.
 *
 * [dataSourceOnThePlainClient] and [dataSourceOnTheAuthenticatedClient] are how
 * the caller builds a data source for the credentials of the moment; the
 * repository hands in Koin, a test hands in fakes.
 *
 * The account is filled in as it goes: [Account.token], [Account.writeToken]
 * and [Account.displayedName]. The caller is the one that stores it.
 */
suspend fun logIn(
    account: Account,
    httpClients: HttpClients,
    dataSourceOnThePlainClient: (Credentials) -> GReaderDataSource,
    dataSourceOnTheAuthenticatedClient: (Credentials) -> GReaderDataSource
) {
    // Whatever this account carried belongs to whichever server issued it, and
    // this login may well be aimed at another one. Dropped here, before any
    // request is built, so that nothing downstream can send it.
    account.token = null
    account.writeToken = null
    httpClients.forgetCredentials()

    account.token = dataSourceOnThePlainClient(Credentials.toCredentials(account))
        .login(account.login!!, account.password!!)

    // Now there is a token, issued by this server, and it is bound to this
    // server and to no other.
    httpClients.useCredentials(Credentials.toCredentials(account))
    val dataSource = dataSourceOnTheAuthenticatedClient(Credentials.toCredentials(account))

    account.writeToken = dataSource.getWriteToken()
    account.displayedName = dataSource.getUserInfo().userName
}
