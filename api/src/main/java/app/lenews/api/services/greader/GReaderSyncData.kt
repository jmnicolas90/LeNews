package app.lenews.api.services.greader

data class GReaderSyncData(
    var cursor: Long = 0,
    var readIds: List<Long> = listOf(),
    var unreadIds: List<Long> = listOf(),
    var starredIds: List<Long> = listOf(),
    var unstarredIds: List<Long> = listOf(),
)
