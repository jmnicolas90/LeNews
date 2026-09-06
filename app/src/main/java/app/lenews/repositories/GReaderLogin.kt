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
 * ClientLogin is the one call with no token to send, so it goes out on a client
 * that has none — which is the plain client. What it answers is the token, and
 * from that moment every call needs a client that carries it. That is a
 * different client, because a client's credentials never change once it is
 * built, so the data source is asked for twice: once before the token is known
 * and once after. Asking once and reusing it would send every call after the
 * login on the client that had no token.
 *
 * [dataSourceFor] is how the caller builds a data source for the credentials of
 * the moment; the repository hands in Koin, a test hands in a fake.
 *
 * The account is filled in as it goes: [Account.token], [Account.writeToken]
 * and [Account.displayedName]. The caller is the one that stores it.
 */
suspend fun logIn(
    account: Account,
    httpClients: HttpClients,
    dataSourceFor: (Credentials) -> GReaderDataSource
) {
    // The token is still null here, so this leaves the plain client in place.
    httpClients.useCredentials(Credentials.toCredentials(account))

    account.token = dataSourceFor(Credentials.toCredentials(account))
        .login(account.login!!, account.password!!)

    // Now there is a token, and it is bound to this server and to no other.
    httpClients.useCredentials(Credentials.toCredentials(account))
    val dataSource = dataSourceFor(Credentials.toCredentials(account))

    account.writeToken = dataSource.getWriteToken()
    account.displayedName = dataSource.getUserInfo().userName
}
