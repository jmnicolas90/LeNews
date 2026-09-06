package app.lenews

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import cafe.adriel.voyager.core.annotation.ExperimentalVoyagerApi
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import cafe.adriel.voyager.transitions.SlideTransition
import app.lenews.account.credentials.AccountCredentialsScreen
import app.lenews.account.credentials.AccountCredentialsScreenMode
import app.lenews.home.HomeScreen
import app.lenews.repositories.BaseRepository
import app.lenews.sync.SyncWorker
import app.lenews.timelime.TimelineTab
import app.lenews.util.Preferences
import app.lenews.util.theme.LeNewsTheme
import app.lenews.db.Database
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.KoinAndroidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

class MainActivity : ComponentActivity(), KoinComponent {

    var ready = false

    @OptIn(ExperimentalVoyagerApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Disable waiting for timeline tab list to be populated before removing splash screen
        //splashScreen.setKeepOnScreenCondition { !ready }

        val database = get<Database>()
        val accountExists = runBlocking { database.accountDao().selectAccountCount() > 0 }

        val preferences = get<Preferences>()

        val darkFlag = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val initialUseDarkTheme = runBlocking {
            useDarkTheme(preferences.theme.flow.first(), darkFlag)
        }

        setContent {
            KoinAndroidContext {
                // remember, because map() builds a new Flow every time it is
                // called and calling it in composition would hand collectAsState
                // a different Flow on every recomposition, restarting the
                // collection each time. Nothing here changes across
                // recompositions, so there are no keys.
                val themeFlow = remember {
                    preferences.theme.flow.map { mode -> useDarkTheme(mode, darkFlag) }
                }
                val useDarkTheme by themeFlow.collectAsState(initial = initialUseDarkTheme)

                LeNewsTheme(
                    useDarkTheme = useDarkTheme
                ) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(
                            lightScrim = Color.TRANSPARENT,
                            darkScrim = Color.TRANSPARENT,
                            detectDarkMode = { useDarkTheme }
                        ),
                        navigationBarStyle = SystemBarStyle.light(
                            scrim = BottomAppBarDefaults.containerColor.toArgb(),
                            darkScrim = BottomAppBarDefaults.containerColor.toArgb()
                        )
                    )

                    Navigator(
                        screen = if (accountExists) {
                            HomeScreen
                        } else {
                            AccountCredentialsScreen(
                                account = Account(name = getString(R.string.freshrss)),
                                mode = AccountCredentialsScreenMode.NEW_CREDENTIALS
                            )
                        },
                        disposeBehavior = NavigatorDisposeBehavior(
                            // prevent screenModels being recreated when opening a screen from a tab
                            disposeNestedNavigators = false,
                            disposeSteps = false
                        )
                    ) { navigator ->
                        LaunchedEffect(Unit) {
                            handleIntent(intent)
                        }

                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.background)
                                // custom safe drawing to be able to draw behind the status bar
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(
                                        WindowInsetsSides.Start + WindowInsetsSides.End
                                    )
                                )
                        ) {
                            SlideTransition(
                                navigator = navigator,
                                modifier = Modifier.imePadding(),
                                disposeScreenAfterTransitionEnd = true
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        lifecycleScope.launch {
            handleIntent(intent)
        }
    }

    private suspend fun handleIntent(intent: Intent) = withContext(Dispatchers.IO) {
        when {
            intent.getBooleanExtra(SyncWorker.FROM_SYNC_KEY, false) -> {
                val database = get<Database>()

                HomeScreen.openTab(TimelineTab)

                if (intent.hasExtra(SyncWorker.ITEM_ID_KEY)) {
                    val itemId = intent.getLongExtra(SyncWorker.ITEM_ID_KEY, -1L)
                    val account = database.accountDao().select() ?: return@withContext
                    val item = database.itemDao().select(itemId)
                        .apply { isRead = true }

                    get<BaseRepository>(parameters = { parametersOf(account) })
                        .setItemReadState(item)
                    HomeScreen.openItem(itemId)
                }
            }

            intent.action != null && intent.action == Intent.ACTION_SEND -> {
                HomeScreen.openAddFeedDialog(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            }
        }
    }

    private fun useDarkTheme(mode: String, darkFlag: Int): Boolean {
        return when (mode) {
            "light" -> false
            "dark" -> true
            else -> darkFlag == Configuration.UI_MODE_NIGHT_YES
        }
    }
}