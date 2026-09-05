package app.lenews.api.services

import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item
import app.lenews.db.entities.Tag

data class DataSourceResult(
    var items: List<Item> = mutableListOf(),
    var starredItems: List<Item> = mutableListOf(),
    var feeds: List<Feed> = listOf(),
    var folders: List<Folder> = listOf(),
    var unreadIds: List<String> = listOf(),
    var readIds: List<String> = listOf(),
    var starredIds: List<String> = listOf(),
    var tags: List<Tag> = listOf()
)
