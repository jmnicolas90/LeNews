package app.lenews.api.services

import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.Item

data class DataSourceResult(
    var items: List<Item> = mutableListOf(),
    var starredItems: List<Item> = mutableListOf(),
    var feeds: List<Feed> = listOf(),
    var folders: List<Folder> = listOf(),
    var unreadIds: List<Long> = listOf(),
    var readIds: List<Long> = listOf(),
    var starredIds: List<Long> = listOf()
)
