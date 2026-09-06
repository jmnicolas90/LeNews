package app.lenews.api

import app.lenews.api.services.Credentials
import app.lenews.api.services.greader.GReaderDataSource
import app.lenews.api.services.greader.GReaderService
import app.lenews.api.services.greader.adapters.FreshRSSUserInfoAdapter
import app.lenews.api.services.greader.adapters.GReaderFeedsAdapter
import app.lenews.api.services.greader.adapters.GReaderFoldersAdapter
import app.lenews.api.services.greader.adapters.GReaderItemIdsPage
import app.lenews.api.services.greader.adapters.GReaderItemsAdapter
import app.lenews.api.services.greader.adapters.GReaderItemsIdsAdapter
import app.lenews.api.services.greader.adapters.GReaderItemsPage
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/** The client that carries the FreshRSS token. Ask for it by this name. */
const val AUTHENTICATED_CLIENT = "authenticatedClient"

/** The client that carries no credentials at all. Ask for it by this name. */
const val PLAIN_CLIENT = "plainClient"

/**
 * [userAgent] is what both clients call themselves on the network. It is
 * `LeNews/<versionName>`, and this module cannot build it: an Android library
 * has no `versionName`, the version lives in the app module's `BuildConfig`, so
 * the app module reads it there and passes it in when it starts Koin.
 */
fun apiModule(userAgent: String) = module {

    single { HttpClients(userAgent) }

    // Two clients, each under its own name, and no unnamed one: a call site has
    // to say which of the two it means, and cannot get the authenticated client
    // by accident.
    //
    // Factories rather than singles. A single would resolve the authenticated
    // client once and hand out the instance built at startup for ever, which is
    // exactly what logging in replaces.
    factory<OkHttpClient>(named(AUTHENTICATED_CLIENT)) { get<HttpClients>().authenticated }

    factory<OkHttpClient>(named(PLAIN_CLIENT)) { get<HttpClients>().plain }

    factory { params -> GReaderDataSource(get(parameters = { params })) }

    factory { (credentials: Credentials) ->
        Retrofit.Builder()
            .baseUrl(credentials.url)
            // Resolved here, once, so this service keeps the client — and so
            // the credentials — it was built with, whatever a later login does.
            .client(get(named(AUTHENTICATED_CLIENT)))
            .addConverterFactory(MoshiConverterFactory.create(get(named("greaderMoshi"))))
            .build()
            .create(GReaderService::class.java)
    }

    single(named("greaderMoshi")) {
        Moshi.Builder()
            .add(GReaderItemsPage::class.java, GReaderItemsAdapter())
            .add(GReaderItemIdsPage::class.java, GReaderItemsIdsAdapter())
            .add(GReaderFeedsAdapter())
            .add(GReaderFoldersAdapter())
            .add(FreshRSSUserInfoAdapter())
            .build()
    }
}
