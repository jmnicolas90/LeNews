package com.readrops.app.util.accounterror

import android.content.Context
import com.readrops.api.utils.exceptions.HttpException
import com.readrops.app.R

class GReaderError(context: Context) : AccountError(context) {

    /**
     * FreshRSS answers `subscription/edit?ac=subscribe` with HTTP 400 and the body
     * "Bad Request!" in two different situations: the URL is already subscribed, or
     * the server could not read a feed at that URL. Both go through the same
     * `badRequest()` call in `p/api/greader.php`, so nothing in the response tells
     * them apart. The message names both rather than guessing.
     */
    override fun newFeedMessage(exception: Exception): String = when (exception) {
        is HttpException -> {
            when (exception.code) {
                400 -> context.resources.getString(R.string.freshrss_feed_not_added)
                else -> httpMessage(exception)
            }
        }
        else -> genericMessage(exception)
    }

    /**
     * Renaming or moving a feed uses the same endpoint with `ac=edit`, but there
     * 400 has a single meaning: the server does not know this feed. That is what
     * unsubscribing reports too, so the two share a message.
     */
    override fun updateFeedMessage(exception: Exception): String {
        return deleteFeedMessage(exception)
    }

    override fun deleteFeedMessage(exception: Exception): String = when (exception) {
        is HttpException -> {
            when (exception.code) {
                400 -> context.resources.getString(R.string.feed_doesnt_exist)
                else -> httpMessage(exception)
            }
        }
        else -> genericMessage(exception)
    }

    override fun newFolderMessage(exception: Exception): String = when (exception) {
        is HttpException -> {
            when (exception.code) {
                400 -> context.resources.getString(R.string.folder_already_exists)
                else -> httpMessage(exception)
            }
        }
        else -> genericMessage(exception)
    }

    override fun updateFolderMessage(exception: Exception): String {
        return newFolderMessage(exception)
    }

    override fun deleteFolderMessage(exception: Exception): String = when (exception) {
        is HttpException -> {
            when (exception.code) {
                400 -> context.resources.getString(R.string.folder_doesnt_exist)
                else -> httpMessage(exception)
            }
        }
        else -> genericMessage(exception)
    }

}
