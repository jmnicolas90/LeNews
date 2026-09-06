package app.lenews.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import app.lenews.R
import app.lenews.account.credentials.AccountCredentialsScreen
import app.lenews.account.credentials.AccountCredentialsScreenMode
import app.lenews.notifications.NotificationsScreen
import app.lenews.util.components.SelectableIconText
import app.lenews.util.components.ThreeDotsMenu
import app.lenews.util.components.adaptiveIconPainterResource
import app.lenews.util.components.dialog.TextFieldDialog
import app.lenews.util.theme.LargeSpacer
import app.lenews.util.theme.MediumSpacer
import app.lenews.util.theme.VeryShortSpacer
import app.lenews.util.theme.spacing

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

        val state by screenModel.accountState.collectAsStateWithLifecycle()

        AccountDialogs(
            state = state,
            screenModel = screenModel
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.account)) }
                )
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
                            painter = adaptiveIconPainterResource(id = R.drawable.ic_freshrss),
                            contentDescription = null,
                            modifier = Modifier.size(48.dp)
                        )

                        MediumSpacer()

                        Column {
                            Text(
                                text = state.account.name.orEmpty(),
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
                            screenModel.openDialog(
                                DialogState.RenameAccount(state.account.name.orEmpty())
                            )
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
            }
        }
    }

    @Composable
    private fun AccountDialogs(state: AccountState, screenModel: AccountScreenModel) {
        when (state.dialog) {
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
}
