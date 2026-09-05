package com.readrops.app.account.selection

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.readrops.app.BuildConfig
import com.readrops.app.MainActivity
import com.readrops.app.R
import com.readrops.app.account.credentials.AccountCredentialsScreen
import com.readrops.app.account.credentials.AccountCredentialsScreenMode
import com.readrops.app.util.components.AndroidScreen
import com.readrops.app.util.components.SelectableImageText
import com.readrops.app.util.theme.LargeSpacer
import com.readrops.app.util.theme.ShortSpacer
import com.readrops.app.util.theme.spacing
import com.readrops.db.entities.account.Account
import com.readrops.db.entities.account.AccountType

class AccountSelectionScreen : AndroidScreen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        val screenModel = koinScreenModel<AccountSelectionScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        // remove splash screen when opening the app with no account available
        LaunchedEffect(Unit) {
            (context as MainActivity).ready = true
        }

        val accountType = state.accountTypeToCreate
        if (accountType != null) {
            val account = Account(
                type = accountType,
                name = stringResource(id = accountType.nameRes)
            )

            navigator.push(
                AccountCredentialsScreen(account, AccountCredentialsScreenMode.NEW_CREDENTIALS)
            )
            screenModel.resetAccountTypeToCreate()
        }

        // the empty Scaffold is what keeps the content clear of the system bars
        Scaffold { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .padding(MaterialTheme.spacing.mediumSpacing)
                ) {
                    Image(
                        painter = adaptiveIconPainterResource(id = R.mipmap.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp)
                    )

                    ShortSpacer()

                    Text(
                        text = stringResource(id = R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge,
                    )

                    LargeSpacer()

                    Card {
                        Column {
                            Text(
                                text = stringResource(id = R.string.choose_account),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(top = MaterialTheme.spacing.mediumSpacing)
                            )

                            AccountType.entries.forEach { type ->
                                SelectableImageText(
                                    image = adaptiveIconPainterResource(id = type.iconRes),
                                    text = stringResource(id = type.nameRes),
                                    style = MaterialTheme.typography.bodyLarge,
                                    imageSize = 24.dp,
                                    spacing = MaterialTheme.spacing.mediumSpacing,
                                    padding = MaterialTheme.spacing.mediumSpacing,
                                    onClick = { screenModel.createAccount(type) }
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "v${BuildConfig.VERSION_NAME}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = MaterialTheme.spacing.veryShortSpacing)
                )
            }
        }
    }
}

// from https://gist.github.com/tkuenneth/ddf598663f041dc79960cda503d14448
@Composable
fun adaptiveIconPainterResource(@DrawableRes id: Int): Painter {
    val res = LocalContext.current.resources
    val theme = LocalContext.current.theme

    val adaptiveIcon = ResourcesCompat.getDrawable(res, id, theme) as? AdaptiveIconDrawable
    return if (adaptiveIcon != null) {
        BitmapPainter(adaptiveIcon.toBitmap().asImageBitmap())
    } else {
        // The drawable is not an adaptive icon, load it as a plain resource
        painterResource(id)
    }
}