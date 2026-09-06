package app.lenews.api.services.greader

import app.lenews.api.services.greader.adapters.FreshRSSUserInfo
import app.lenews.api.services.greader.adapters.GReaderFolders
import app.lenews.api.services.greader.adapters.GReaderItemIdsPage
import app.lenews.api.services.greader.adapters.GReaderItemsPage
import app.lenews.db.entities.Feed
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface GReaderService {

    @POST("accounts/ClientLogin")
    suspend fun login(@Body body: RequestBody?): ResponseBody

    @GET("reader/api/0/token")
    suspend fun getWriteToken(): ResponseBody

    @GET("reader/api/0/user-info")
    suspend fun userInfo(): FreshRSSUserInfo

    @GET("reader/api/0/subscription/list?output=json")
    suspend fun getFeeds(): List<Feed>

    @GET("reader/api/0/tag/list?output=json")
    suspend fun getFolders(): GReaderFolders

    @GET("reader/api/0/stream/contents/user/-/state/com.google/reading-list")
    suspend fun getItems(
        @Query("xt") excludeTarget: String?,
        @Query("n") max: Int,
        @Query("ot") cursor: Long?,
        @Query("c") continuation: String?
    ): GReaderItemsPage

    @GET("reader/api/0/stream/contents/user/-/state/com.google/starred")
    suspend fun getStarredItems(
        @Query("n") max: Int,
        @Query("c") continuation: String?
    ): GReaderItemsPage

    @GET("reader/api/0/stream/items/ids")
    suspend fun getItemsIds(
        @Query("xt") excludeTarget: String?,
        @Query("s") includeTarget: String?,
        @Query("n") max: Int,
        @Query("c") continuation: String?
    ): GReaderItemIdsPage

    /**
     * The content of named articles, which is how a starred article the store
     * lacks is fetched. FreshRSS reads the ids from the request body, so this is
     * a POST even though it reads.
     */
    @FormUrlEncoded
    @POST("reader/api/0/stream/items/contents")
    suspend fun getItemsContents(
        @Field("T") token: String,
        @Field("i") itemIds: List<String>
    ): GReaderItemsPage

    @FormUrlEncoded
    @POST("reader/api/0/edit-tag")
    suspend fun setItemsState(
        @Field("T") token: String,
        @Field("a") addAction: String?,
        @Field("r") removeAction: String?,
        @Field("i") itemIds: List<String>
    )

    @FormUrlEncoded
    @POST("reader/api/0/subscription/edit")
    suspend fun createOrDeleteFeed(
        @Field("T") token: String,
        @Field("s") feedUrl: String,
        @Field("ac") action: String,
        @Field("a") folderId: String?
    )

    @FormUrlEncoded
    @POST("reader/api/0/subscription/edit")
    suspend fun updateFeed(
        @Field("T") token: String,
        @Field("s") feedUrl: String,
        @Field("t") title: String,
        @Field("a") folderId: String,
        @Field("ac") action: String
    )

    @FormUrlEncoded
    @POST("reader/api/0/edit-tag")
    suspend fun createFolder(@Field("T") token: String, @Field("a") tagName: String)

    @FormUrlEncoded
    @POST("reader/api/0/rename-tag")
    suspend fun updateFolder(
        @Field("T") token: String,
        @Field("s") folderId: String,
        @Field("dest") newFolderId: String
    )

    @FormUrlEncoded
    @POST("reader/api/0/disable-tag")
    suspend fun deleteFolder(@Field("T") token: String, @Field("s") folderId: String)
}