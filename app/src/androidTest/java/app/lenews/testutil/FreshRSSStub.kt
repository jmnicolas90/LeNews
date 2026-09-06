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
package app.lenews.testutil

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.net.HttpURLConnection
import java.util.Collections

/**
 * A FreshRSS server for the sync tests: it answers the six calls a sync makes,
 * pages them the way the real server does, and records what it was asked.
 *
 * Articles and ids are given as numbers, never as text: [articleJson] derives
 * the long hexadecimal form `stream/contents` uses from the same decimal number
 * the id lists send, so a fixture cannot mix the two forms.
 *
 * Paging is answered from the request rather than from a counter, so the same
 * stream can be walked twice in one test and the second walk starts again at
 * the first page. A page that is not the last carries a continuation, and the
 * page a request asks for is read back out of the `c` parameter it carries: a
 * client that dropped the continuation gets the first page again and the test
 * sees it.
 */
class FreshRSSStub : Dispatcher() {

    /** The articles of `stream/contents` for the reading list, one list a page. */
    var readingListPages: List<List<String>> = listOf(emptyList())

    /** The articles of `stream/contents` for the starred stream, one list a page. */
    var starredContentPages: List<List<String>> = listOf(emptyList())

    /** Every id the server still holds, one list a page. */
    var serverIdPages: List<List<Long>> = listOf(emptyList())

    /** The unread ids, one list a page. */
    var unreadIdPages: List<List<Long>> = listOf(emptyList())

    /** The starred ids, one list a page. */
    var starredIdPages: List<List<Long>> = listOf(emptyList())

    /** What `stream/items/contents` answers, whatever ids it is asked for. */
    var itemsContentsArticles: List<String> = emptyList()

    /** Set to fail every `edit-tag` request, which fails the sync before any pull. */
    var refuseStateUploads: Boolean = false

    /**
     * When set, the reading-list contents send this broken answer once every
     * page above has been sent, so the client has a whole page of content in
     * hand when the answer that must fail it arrives.
     */
    var brokenReadingListPage: BrokenPage? = null

    /** The same, for the full id list. */
    var brokenServerIdPage: BrokenPage? = null

    /** Run just before an `edit-tag` request is answered, with the request body. */
    var onStateUpload: ((String) -> Unit)? = null

    private val recorded = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    /** The path and body of every request, in the order they arrived. */
    val requests: List<Pair<String, String>>
        get() = synchronized(recorded) { recorded.toList() }

    fun requestsTo(pathPart: String): List<Pair<String, String>> =
        requests.filter { (path, _) -> path.contains(pathPart) }

    fun forget() = synchronized(recorded) { recorded.clear() }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val url = request.requestUrl
        val body = request.body.readUtf8()
        recorded += path to body

        // the page a paged call asks for, read out of the continuation it sent
        val continuation = url?.queryParameter("c")

        return when {
            path.contains("edit-tag") -> {
                onStateUpload?.invoke(body)
                if (refuseStateUploads) {
                    MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR)
                } else {
                    MockResponse().setResponseCode(HttpURLConnection.HTTP_OK)
                }
            }

            path.contains("tag/list") ->
                okWith(TestUtils.loadResource("greader/folders.json").bufferedReader().readText())

            path.contains("subscription/list") ->
                okWith(TestUtils.loadResource("greader/feeds.json").bufferedReader().readText())

            path.contains("stream/items/contents") ->
                okWith(contentsJson(itemsContentsArticles, null))

            path.contains("contents/user/-/state/com.google/reading-list") ->
                okWith(contentsPage(readingListPages, continuation, brokenReadingListPage))

            path.contains("contents/user/-/state/com.google/starred") ->
                okWith(contentsPage(starredContentPages, continuation, null))

            path.contains("stream/items/ids") -> when {
                url?.queryParameter("s") == STARRED ->
                    okWith(idsPage(starredIdPages, continuation, null))

                url?.queryParameter("xt") != null ->
                    okWith(idsPage(unreadIdPages, continuation, null))

                else -> okWith(idsPage(serverIdPages, continuation, brokenServerIdPage))
            }

            else -> MockResponse().setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
        }
    }

    private fun okWith(body: String): MockResponse =
        MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody(body)

    private fun contentsPage(
        pages: List<List<String>>,
        continuation: String?,
        broken: BrokenPage?
    ): String {
        val (articles, next) = page(pages, continuation, broken)
        return contentsJson(articles, next)
    }

    private fun idsPage(
        pages: List<List<Long>>,
        continuation: String?,
        broken: BrokenPage?
    ): String {
        val (ids, next) = page(pages, continuation, broken)
        return idsJson(ids, next)
    }

    /**
     * The rows of the page a request asks for and the continuation to send with
     * it. The real server sends a continuation only when there is another page
     * behind the one it just sent.
     *
     * A stream told to send a [BrokenPage] sends every page above first, each
     * with a continuation, and answers the request after them with the broken
     * page instead of ending the walk.
     */
    private fun <T> page(
        pages: List<List<T>>,
        continuation: String?,
        broken: BrokenPage?
    ): Pair<List<T>, Long?> {
        val index = pageIndexOf(continuation)
        val nextContinuation = FIRST_CONTINUATION + index + 1

        if (broken != null && index >= pages.size) {
            return when (broken) {
                BrokenPage.NOTHING_BACK_AND_ANOTHER_PAGE_ASKED_FOR ->
                    emptyList<T>() to nextContinuation

                BrokenPage.THE_CONTINUATION_SENT_BACK ->
                    pages.last() to continuation!!.toLong()
            }
        }

        val rows = pages.getOrElse(index) { emptyList() }
        val thereIsMore = index + 1 < pages.size || broken != null

        return rows to if (thereIsMore) nextContinuation else null
    }

    private fun pageIndexOf(continuation: String?): Int =
        if (continuation.isNullOrEmpty()) 0 else (continuation.toLong() - FIRST_CONTINUATION).toInt()

    /**
     * The two answers that end no walk and are no page: a real FreshRSS sends
     * neither, and a client that took them for an end would treat part of a
     * list as the whole of it.
     */
    enum class BrokenPage {

        /** No row, and a continuation asking the client to come back. */
        NOTHING_BACK_AND_ANOTHER_PAGE_ASKED_FOR,

        /** The continuation the request carried, sent straight back. */
        THE_CONTINUATION_SENT_BACK
    }

    companion object {

        const val FEED_REMOTE_ID = "feed/2"
        const val READ = "user/-/state/com.google/read"
        const val STARRED = "user/-/state/com.google/starred"

        /**
         * The continuation of the first page. A big decimal number, like the
         * article id the real server sends there.
         */
        private const val FIRST_CONTINUATION = 9000000000000000L

        /** The long form `stream/contents` writes an id in. */
        fun longForm(id: Long): String =
            "tag:google.com,2005:reader/item/" +
                    java.lang.Long.toUnsignedString(id, 16).padStart(16, '0')

        /**
         * One article as `stream/contents` sends it. The categories are what the
         * server says about read and starred there; the store ignores them and
         * reads state from the id lists instead, which is what several of these
         * tests check.
         */
        fun articleJson(
            id: Long,
            title: String = "article $id",
            content: String = "content of $id",
            publishedSeconds: Long = 1625234040L,
            categories: List<String> = emptyList()
        ): String {
            val allCategories = (listOf("user/-/state/com.google/reading-list") + categories)
                .joinToString(", ") { "\"$it\"" }

            return """
                {
                  "id": "${longForm(id)}",
                  "published": $publishedSeconds,
                  "title": "$title",
                  "summary": { "content": "$content" },
                  "alternate": [ { "href": "https://example.invalid/$id", "type": "text/html" } ],
                  "categories": [ $allCategories ],
                  "origin": { "streamId": "$FEED_REMOTE_ID", "title": "FreshRSS @ GitHub" },
                  "author": "An author"
                }
            """.trimIndent()
        }

        fun contentsJson(articles: List<String>, continuation: Long?): String {
            val tail = if (continuation == null) "" else ",\n\"continuation\": \"$continuation\""
            return """
                {
                  "id": "user/-/state/com.google/reading-list",
                  "updated": 1625235516,
                  "items": [ ${articles.joinToString(",\n")} ]$tail
                }
            """.trimIndent()
        }

        fun idsJson(ids: List<Long>, continuation: Long?): String {
            val refs = ids.joinToString(",\n") { "{ \"id\": \"$it\" }" }
            val tail = if (continuation == null) "" else ",\n\"continuation\": \"$continuation\""
            return """{ "itemRefs": [ $refs ]$tail }"""
        }
    }
}
