package app.lenews.repositories

import app.lenews.db.Database
import app.lenews.db.entities.Feed
import app.lenews.db.entities.Folder
import app.lenews.db.entities.OpenIn
import app.lenews.db.filters.MainFilter
import app.lenews.db.queries.FeedUnreadCountQueryBuilder
import app.lenews.db.queries.FoldersAndFeedsQueryBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class GetFoldersWithFeeds(
    private val database: Database,
) {

    fun get(
        mainFilter: MainFilter,
        hideReadFeeds: Boolean = false
    ): Flow<Map<Folder?, List<Feed>>> {
        val foldersAndFeedsQuery = FoldersAndFeedsQueryBuilder.build(mainFilter, hideReadFeeds)
        val unreadItemsCountQuery = FeedUnreadCountQueryBuilder.build(mainFilter)

        return combine(
            flow = database.folderDao().selectFoldersAndFeeds(foldersAndFeedsQuery),
            flow2 = database.itemDao().selectFeedUnreadItemsCount(unreadItemsCountQuery)
        ) { folders, itemCounts ->
            val foldersWithFeeds = folders.groupBy(
                keySelector = {
                    if (it.folderId != null) {
                        Folder(
                            id = it.folderId!!,
                            name = it.folderName,
                            remoteId = it.folderRemoteId
                        )
                    } else {
                        null
                    }
                },
                valueTransform = {
                    Feed(
                        id = it.feedId,
                        name = it.feedName,
                        iconUrl = it.feedIcon,
                        color = it.feedColor,
                        imageUrl = it.feedImage,
                        url = it.feedUrl,
                        siteUrl = it.feedSiteUrl,
                        description = it.feedDescription,
                        isNotificationEnabled = it.feedNotificationsEnabled,
                        openIn = if (it.feedOpenIn != null) {
                            it.feedOpenIn!!
                        } else {
                            OpenIn.LOCAL_VIEW
                        },
                        remoteId = it.feedRemoteId,
                        unreadCount = itemCounts[it.feedId] ?: 0
                    )
                }
            ).mapValues { listEntry ->
                // Empty folder case
                if (listEntry.value.any { it.id == 0 }) {
                    listOf()
                } else {
                    listEntry.value
                }
            }

            // folders whose name starts with an underscore come first
            val comparator = compareByDescending<Folder?> {
                it?.name?.startsWith("_")
            }
                .then(nullsLast(Folder::compareTo))

            foldersWithFeeds.toSortedMap(comparator)
        }
    }

    fun getNewItemsUnreadCount(): Flow<Int> = database.itemDao().selectUnreadNewItemsCount()
}