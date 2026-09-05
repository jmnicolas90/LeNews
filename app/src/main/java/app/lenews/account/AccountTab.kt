package app.lenews.account

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import app.lenews.R
import app.lenews.account.credentials.AccountCredentialsScreen
import app.lenews.account.credentials.AccountCredentialsScreenMode
import app.lenews.account.dialog.AccountSelectionDialog
import app.lenews.account.selection.AccountSelectionScreen
import app.lenews.account.selection.adaptiveIconPainterResource
import app.lenews.notifications.NotificationsScreen
import app.lenews.util.components.SelectableIconText
import app.lenews.util.components.SelectableImageText
import app.lenews.util.components.ThreeDotsMenu
import app.lenews.util.components.dialog.TextFieldDialog
import app.lenews.util.components.dialog.TwoChoicesDialog
import app.lenews.util.theme.LargeSpacer
import app.lenews.util.theme.MediumSpacer
import app.lenews.util.theme.VeryShortSpacer
import app.lenews.util.theme.spacing
import app.lenews.db.entities.account.Account
import app.lenews.db.entities.account.AccountType

object AccountTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 3u,
            title = stringResource(R.string.account)
        )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = koinScreenModel<AccountScreenModel>()

        val closeHome by screenModel.closeHome.collectAsStateWithLifecycle()
        val state by screenModel.accountState.collectAsStateWithLifecycle()

        if (closeHome) {
            navigator.replaceAll(AccountSelectionScreen())
            screenModel.resetCloseHome()
        }

        AccountDialogs(
            state = state,
            screenModel = screenModel
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.account)) }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { screenModel.openDialog(DialogState.NewAccount) }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_add_account),
                        contentDescription = null
                    )
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = MaterialTheme.spacing.mediumSpacing)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = adaptiveIconPainterResource(id = state.account.type!!.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )

                        MediumSpacer()

                        Column {
                            Text(
                                text = state.account.name!!,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (state.account.displayedName != null) {
                                VeryShortSpacer()

                                Text(
                                    text = state.account.displayedName!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    ThreeDotsMenu(
                        items = mapOf(1 to stringResource(id = R.string.rename_account)),
                        onItemClick = {
                            screenModel.openDialog(DialogState.RenameAccount(state.account.name!!))
                        },
                    )
                }

                LargeSpacer()

                SelectableIconText(
                    icon = painterResource(id = R.drawable.ic_person),
                    text = stringResource(R.string.credentials),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    spacing = MaterialTheme.spacing.largeSpacing,
                    padding = MaterialTheme.spacing.mediumSpacing,
                    tint = MaterialTheme.colorScheme.primary,
                    iconSize = 24.dp,
                    onClick = {
                        navigator.push(
                            AccountCredentialsScreen(
                                state.account,
                                AccountCredentialsScreenMode.EDIT_CREDENTIALS
                            )
                        )
                    }
                )

                SelectableIconText(
                    icon = painterResource(id = R.drawable.ic_notifications),
                    text = stringResource(R.string.notifications),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    spacing = MaterialTheme.spacing.largeSpacing,
                    padding = MaterialTheme.spacing.mediumSpacing,
                    tint = MaterialTheme.colorScheme.primary,
                    iconSize = 24.dp,
                    onClick = { navigator.push(NotificationsScreen(state.account)) }
                )

                SelectableIconText(
                    icon = rememberVectorPainter(image = Icons.Default.AccountCircle),
                    text = stringResource(R.string.delete_account),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    spacing = MaterialTheme.spacing.largeSpacing,
                    padding = MaterialTheme.spacing.mediumSpacing,
                    color = MaterialTheme.colorScheme.error,
                    tint = MaterialTheme.colorScheme.error,
                    iconSize = 24.dp,
                    onClick = { screenModel.openDialog(DialogState.DeleteAccount) }
                )

                if (state.accounts.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(MaterialTheme.spacing.mediumSpacing)
                    )

                    Text(
                        text = stringResource(id = R.string.other_accounts),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.mediumSpacing)
                    )

                    VeryShortSpacer()

                    for (account in state.accounts) {
                        SelectableImageText(
                            image = adaptiveIconPainterResource(id = account.type!!.iconRes),
                            text = account.name!!,
                            style = MaterialTheme.typography.titleMedium,
                            padding = MaterialTheme.spacing.mediumSpacing,
                            spacing = MaterialTheme.spacing.mediumSpacing,
                            imageSize = 24.dp,
                            onClick = { screenModel.updateCurrentAccount(account) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AccountDialogs(state: AccountState, screenModel: AccountScreenModel) {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        when (state.dialog) {
            is DialogState.DeleteAccount -> {
                TwoChoicesDialog(
                    title = stringResource(R.string.delete_account),
                    text = stringResource(R.string.delete_account_question),
                    icon = rememberVectorPainter(image = Icons.Default.Delete),
                    confirmText = stringResource(R.string.delete),
                    dismissText = stringResource(R.string.cancel),
                    onDismiss = { screenModel.closeDialog() },
                    onConfirm = {
                        screenModel.closeDialog()
                        screenModel.deleteAccount()
                    }
                )
            }

            is DialogState.NewAccount -> {
                AccountSelectionDialog(
                    onDismiss = { screenModel.closeDialog() },
                    onValidate = { accountType ->
                        screenModel.closeDialog()

                        pushAccount(
                            type = accountType,
                            context = context,
                            navigator = navigator
                        )
                    }
                )
            }

            is DialogState.RenameAccount -> {
                TextFieldDialog(
                    title = stringResource(id = R.string.rename_account),
                    icon = painterResource(id = R.drawable.ic_person),
                    label = stringResource(id = R.string.name),
                    state = state.renameAccountState,
                    onValueChange = { screenModel.setAccountRenameStateName(it) },
                    onValidate = { screenModel.renameAccount() },
                    onDismiss = { screenModel.closeDialog() }
                )
            }

            else -> {}
        }
    }

    private fun pushAccount(type: AccountType, context: Context, navigator: Navigator) {
        val account = Account(
            type = type,
            name = context.resources.getString(type.nameRes)
        )

        navigator.push(
            AccountCredentialsScreen(
                account = account,
                mode = AccountCredentialsScreenMode.NEW_CREDENTIALS
            )
        )
    }
}