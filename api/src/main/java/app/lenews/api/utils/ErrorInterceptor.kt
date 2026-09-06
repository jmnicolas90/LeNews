package app.lenews.api.utils

import app.lenews.api.utils.exceptions.HttpException
import okhttp3.Interceptor
import okhttp3.Response

class ErrorInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!response.isSuccessful && response.code !in 300..308) {
            // closed before the throw, because nothing downstream will: the
            // exception travels instead of the response, and a body left open
            // holds its connection out of the pool until the garbage collector
            // notices. The status code and text are read out of it first.
            response.use { throw HttpException(it) }
        }

        return response
    }
}
