package app.lenews.more

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import app.lenews.BuildConfig
import app.lenews.R
import app.lenews.account.selection.adaptiveIconPainterResource
import app.lenews.more.debug.DebugScreen
import app.lenews.more.preferences.PreferencesScreen
import app.lenews.util.components.IconText
import app.lenews.util.components.SelectableIconText
import app.lenews.util.extensions.openUrl
import app.lenews.util.theme.LargeSpacer
import app.lenews.util.theme.MediumSpacer
import app.lenews.util.theme.ShortSpacer
import app.lenews.util.theme.spacing
import org.koin.core.component.KoinComponent

object MoreTab : Tab, KoinComponent {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 4u,
            title = stringResource(R.string.more)
        )


    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        var showDonationDialog by remember { mutableStateOf(false) }

        if (showDonationDialog) {
            DonationDialog(
                onDismiss = { showDonationDialog = false }
            )
        }

        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                LargeSpacer()

                Image(
                    painter = adaptiveIconPainterResource(id = R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp)
                )

                MediumSpacer()

                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                ShortSpacer()

                IconText(
                    text = if (BuildConfig.DEBUG) {
                        "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    } else {
                        "v${BuildConfig.VERSION_NAME}"
                    },
                    icon = painterResource(id = R.drawable.ic_version),
                    style = MaterialTheme.typography.labelLarge
                )

                ShortSpacer()

                Text(
                    text = stringResource(id = R.string.app_licence),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ShortSpacer()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = { context.openUrl(context.getString(R.string.app_url)) }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = { context.openUrl(context.getString(R.string.changelog_url)) }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_changelog),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    IconButton(
                        onClick = { context.openUrl(context.getString(R.string.app_issues_url)) }
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_bug_report),
                            contentDescription = null
                        )
                    }
                }

                MediumSpacer()

                SelectableIconText(
                    icon = painterResource(id = R.drawable.ic_settings),
                    text = stringResource(R.string.settings),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    spacing = MaterialTheme.spacing.largeSpacing,
                    padding = MaterialTheme.spacing.mediumSpacing,
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = { navigator.push(PreferencesScreen()) }
                )

                SelectableIconText(
                    icon = painterResource(id = R.drawable.ic_library),
                    text = stringResource(id = R.string.open_source_libraries),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    spacing = MaterialTheme.spacing.largeSpacing,
                    padding = MaterialTheme.spacing.mediumSpacing,
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = { navigator.push(AboutLibrariesScreen()) }
                )

                SelectableIconText(
                    icon = painterResource(id = R.drawable.ic_donation),
                    text = stringResource(id = R.string.make_donation),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                    spacing = MaterialTheme.spacing.largeSpacing,
                    padding = MaterialTheme.spacing.mediumSpacing,
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = { showDonationDialog = true }
                )

                if (BuildConfig.DEBUG) {
                    SelectableIconText(
                        icon = painterResource(id = R.drawable.ic_bug),
                        text = "Debug",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
                        spacing = MaterialTheme.spacing.largeSpacing,
                        padding = MaterialTheme.spacing.mediumSpacing,
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { navigator.push(DebugScreen()) }
                    )
                }
            }
        }
    }
}