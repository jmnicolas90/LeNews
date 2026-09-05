package app.lenews.api

import java.io.InputStream

object TestUtils {

    fun loadResource(path: String): InputStream =
        javaClass.classLoader?.getResourceAsStream(path)!!
}