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

package app.lenews.api.utils

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Puts LeNews's own name on every request, on both clients.
 *
 * Left to itself OkHttp sends `okhttp/4.12.0`, which common blocklists answer
 * with a 403. The failure that follows names nothing the app could explain: a
 * feed, an image or a favicon simply does not load.
 */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain.request().newBuilder()
                .header(USER_AGENT_HEADER, userAgent)
                .build()
        )

    companion object {
        const val USER_AGENT_HEADER = "User-Agent"
    }
}
