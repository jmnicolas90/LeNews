package app.lenews.db.entities.account

data class AccountConfig(
    val isFeedUrlReadOnly: Boolean, // Enable or disable feed url modification in Feed Tab
    val addNoFolder: Boolean, // Add a "No folder" option when modifying a feed's folder
    val useSeparateState: Boolean, // Let know if it uses ItemState table to synchronize read/star state
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
            useSeparateState = true,
            showCustomFolderDeleteMessage = true
        )
    }
}
