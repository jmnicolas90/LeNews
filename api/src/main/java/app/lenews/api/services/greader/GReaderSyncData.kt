package app.lenews.api.services.greader

data class GReaderSyncData(
    var lastModified: Long = 0,
    var readIds: List<String> = listOf(),
    var unreadIds: List<String> = listOf(),
    var starredIds: List<String> = listOf(),
    var unstarredIds: List<String> = listOf(),
)