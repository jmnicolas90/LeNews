package app.lenews.home

import android.content.Context
import android.content.SharedPreferences
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import app.lenews.api.services.Credentials
import app.lenews.api.utils.AuthInterceptor
import app.lenews.repositories.BaseRepository
import app.lenews.util.accounterror.AccountError
import app.lenews.util.accounterror.GReaderError
import app.lenews.db.Database
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

/**
 * What every tab screen shares: the one account, the repository built on it, and
 * the error messages of its service. [accountEvent] emits when the account row
 * appears or changes, which is what a tab waits for before it can query
 * anything.
 */
abstract class TabScreenModel(
    private val database: Database,
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) : ScreenModel, KoinComponent {

    /**
     * Repository intended to be rebuilt when the account changes
     */
    protected var repository: BaseRepository? = null

    protected var currentAccount: Account? = null

    protected var accountError: AccountError = GReaderError(context)

    private val _accountEvent = MutableSharedFlow<Account>()
    protected val accountEvent =
        _accountEvent.shareIn(scope = screenModelScope, started = SharingStarted.Eagerly)

    init {
        screenModelScope.launch(dispatcher) {
            database.accountDao()
                .selectAccount()
                .distinctUntilChanged()
                .collect { account ->
                    if (account != null) {
                        if (account.login == null || account.password == null) {
                            val encryptedPreferences = get<SharedPreferences>()

                            account.login =
                                encryptedPreferences.getString(Account.LOGIN_KEY, null)
                            account.password =
                                encryptedPreferences.getString(Account.PASSWORD_KEY, null)
                        }

                        get<AuthInterceptor>().credentials = Credentials.toCredentials(account)

                        currentAccount = account
                        repository = get(parameters = { parametersOf(account) })

                        _accountEvent.emit(account)
                    }
                }
        }
    }
}