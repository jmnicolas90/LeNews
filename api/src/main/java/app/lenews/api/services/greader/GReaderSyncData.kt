package app.lenews.api.services.greader

/**
 * The snapshot of the pending changes a sync uploads: what the phone decided
 * and the server has not been told, one list per state change.
 */
data class GReaderSyncData(
    var readIds: List<Long> = listOf(),
    var unreadIds: List<Long> = listOf(),
    var starredIds: List<Long> = listOf(),
    var unstarredIds: List<Long> = listOf(),
)
