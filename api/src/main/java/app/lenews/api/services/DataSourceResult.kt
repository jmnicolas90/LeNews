package app.lenews.api.services

import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item

/**
 * What one pull brought back, before anything is written.
 *
 * The three id lists are the only source of read and starred state: the flags
 * [items] carry are content, not state.
 */
data class DataSourceResult(
    var items: List<Item> = listOf(),
    var feeds: List<Feed> = listOf(),
    var folders: List<Folder> = listOf(),
    /** Every article id the server still holds in the reading list, read or not. */
    var serverIds: List<Long> = listOf(),
    var unreadIds: List<Long> = listOf(),
    var starredIds: List<Long> = listOf()
)
