package app.lenews.api

import app.lenews.api.services.Credentials
import app.lenews.api.services.greader.GReaderDataSource
import app.lenews.api.services.greader.GReaderService
import app.lenews.api.services.greader.adapters.FreshRSSUserInfoAdapter
import app.lenews.api.services.greader.adapters.GReaderFeedsAdapter
import app.lenews.api.services.greader.adapters.GReaderFoldersAdapter
import app.lenews.api.services.greader.adapters.GReaderItemsAdapter
import app.lenews.api.services.greader.adapters.GReaderItemsIdsAdapter
import app.lenews.api.utils.AuthInterceptor
import app.lenews.api.utils.ErrorInterceptor
import app.lenews.db.entities.Item
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.OkHttpClient
import org.koin.core.qualifier.named
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

val apiModule = module {

    single {
        OkHttpClient.Builder()
            .callTimeout(1, TimeUnit.MINUTES)
            .readTimeout(1, TimeUnit.MINUTES)
            .addInterceptor(get<AuthInterceptor>())
            .addInterceptor(get<ErrorInterceptor>())
            .build()
    }

    single { AuthInterceptor() }

    single { ErrorInterceptor() }

    factory { params -> GReaderDataSource(get(parameters = { params })) }

    factory { (credentials: Credentials) ->
        Retrofit.Builder()
            .baseUrl(credentials.url)
            .client(get())
            .addConverterFactory(MoshiConverterFactory.create(get(named("greaderMoshi"))))
            .build()
            .create(GReaderService::class.java)
    }

    single(named("greaderMoshi")) {
        Moshi.Builder()
            .add(
                Types.newParameterizedType(List::class.java, Item::class.java),
                GReaderItemsAdapter()
            )
            .add(
                Types.newParameterizedType(List::class.java, Long::class.javaObjectType),
                GReaderItemsIdsAdapter()
            )
            .add(GReaderFeedsAdapter())
            .add(GReaderFoldersAdapter())
            .add(FreshRSSUserInfoAdapter())
            .build()
    }
}
