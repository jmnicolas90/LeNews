package app.lenews.db.entities.account

/**
 * What the FreshRSS service lets the user do, as the screens need to know it.
 * One service means one instance, [FRESHRSS].
 */
data class AccountConfig(
    val isFeedUrlReadOnly: Boolean, // Enable or disable feed url modification in Feed Tab
    val addNoFolder: Boolean, // Add a "No folder" option when modifying a feed's folder
    val canCreateFolder: Boolean, // Enable or disable folder creation in Feed Tab
    val canCreateFeed: Boolean = true,
    val canUpdateFolder: Boolean = true,
    val canUpdateFeed: Boolean = true,
    val canDeleteFeed: Boolean = true,
    val canDeleteFolder: Boolean = true,
    val canMarkAllItemsAsRead: Boolean = true,
    val showCustomFolderDeleteMessage: Boolean = false
) {

    companion object {
        val FRESHRSS = AccountConfig(
            isFeedUrlReadOnly = true,
            canCreateFolder = false,
            addNoFolder = false,
            showCustomFolderDeleteMessage = true
        )
    }
}
