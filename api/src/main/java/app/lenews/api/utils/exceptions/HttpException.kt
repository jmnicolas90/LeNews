package app.lenews.api.utils.exceptions

import okhttp3.Response
import java.io.IOException

/**
 * A response the server refused the request with, reduced to what the callers
 * read: the status code and the status line's text.
 *
 * It keeps those two rather than the [Response] itself so that whoever throws
 * it can close the response — a response held by an exception is a response
 * nothing ever closes, and holding a closed one would only invite a caller to
 * read a body that is no longer there.
 */
class HttpException(val code: Int, private val status: String) : IOException() {

    constructor(response: Response) : this(response.code, response.message)

    override val message: String
        get() = "HTTP $code $status"
}
