package app.lenews.db.filters

import java.io.Serializable

enum class MainFilter {
    STARS,
    NEW,
    ALL,

    /**
     * The history: every article that became read, newest first. It is a filter
     * of the timeline rather than a screen of its own, because the timeline
     * already knows how to page articles, show their feed and open them.
     *
     * It ignores [QueryFilters.showReadItems] — every article in the history is
     * read by definition — and it is always ordered by the moment the article
     * became read, so [OrderField] and [OrderType] say nothing here either.
     */
    HISTORY
}

enum class SubFilter {
    FEED,
    FOLDER,
    ALL
}

enum class OrderField {
    DATE,
    ID
}

enum class OrderType {
    DESC,
    ASC
}

data class QueryFilters(
    val showReadItems: Boolean = true,
    val feedId: Int = 0,
    val folderId: Int = 0,
    val mainFilter: MainFilter = MainFilter.ALL,
    val subFilter: SubFilter = SubFilter.ALL,
    val orderField: OrderField = OrderField.DATE,
    val orderType: OrderType = OrderType.DESC,
) : Serializable
