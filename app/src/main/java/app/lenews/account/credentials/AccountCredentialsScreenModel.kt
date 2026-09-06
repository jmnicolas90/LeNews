package app.lenews.account.credentials

import android.content.Context
import android.content.SharedPreferences
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import app.lenews.R
import app.lenews.repositories.BaseRepository
import app.lenews.util.Utils
import app.lenews.util.components.TextFieldError
import app.lenews.db.Database
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf


class AccountCredentialsScreenModel(
    private val account: Account,
    private val mode: AccountCredentialsScreenMode,
    private val database: Database,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    context: Context,
) : StateScreenModel<AccountCredentialsState>(
    initAccountCredentialsState(context)
), KoinComponent {
    init {
        if (mode == AccountCredentialsScreenMode.EDIT_CREDENTIALS) {
            mutableState.update {
                it.copy(
                    name = account.name.orEmpty(),
                    url = account.url.orEmpty(),
                    login = account.login.orEmpty(),
                    password = account.password.orEmpty()
                )
            }
        } else {
            mutableState.update { it.copy(name = account.name.orEmpty()) }
        }
    }

    fun onEvent(event: Event): Unit = with(mutableState) {
        when (event) {
            is Event.LoginEvent -> update { it.copy(login = event.value, loginError = null) }
            is Event.NameEvent -> update { it.copy(name = event.value, nameError = null) }
            is Event.PasswordEvent -> update { it.copy(password = event.value, passwordError = null) }
            is Event.URLEvent -> update { it.copy(url = event.value, urlError = null) }
        }
    }

    fun setPasswordVisibility(isVisible: Boolean) {
        mutableState.update { it.copy(isPasswordVisible = isVisible) }
    }

    fun login() {
        screenModelScope.launch(dispatcher) {
            if (validateFields()) {
                mutableState.update { it.copy(isLoginOnGoing = true) }

                with(state.value) {
                    val newAccount = accountToLogInWith(
                        account = account,
                        url = Utils.normalizeUrl(url),
                        name = name,
                        login = login,
                        password = password
                    )

                    try {
                        get<BaseRepository> { parametersOf(newAccount) }
                            .login(newAccount)
                    } catch (e: Exception) {
                        mutableState.update {
                            it.copy(
                                loginException = e,
                                isLoginOnGoing = false
                            )
                        }

                        return@launch
                    }

                    // one account, one row: logging in writes it, and logging
                    // in again replaces it
                    database.accountDao().upsert(newAccount)

                    get<SharedPreferences>().edit()
                        .putString(Account.LOGIN_KEY, newAccount.login)
                        .putString(Account.PASSWORD_KEY, newAccount.password)
                        .apply()

                    mutableState.update { it.copy(exitScreen = true) }
                }
            }
        }
    }

    private fun validateFields(): Boolean = with(mutableState.value) {
        mutableState.update { it.copy(loginException = null) }

        var validate = true

        if (url.isEmpty()) {
            mutableState.update { it.copy(urlError = TextFieldError.EmptyField) }
            validate = false
        } else if (serverUrlIsCleartext(url)) {
            // refused here, so that no request is built and the password never
            // leaves the phone in the clear
            mutableState.update { it.copy(urlError = TextFieldError.CleartextUrl) }
            validate = false
        }

        if (name.isEmpty()) {
            mutableState.update { it.copy(nameError = TextFieldError.EmptyField) }
            validate = false
        }

        if (login.isEmpty()) {
            mutableState.update { it.copy(loginError = TextFieldError.EmptyField) }
            validate = false
        }

        if (password.isEmpty()) {
            mutableState.update { it.copy(passwordError = TextFieldError.EmptyField) }
            validate = false
        }

        return validate
    }

    companion object {
        // the debug build fills the fields in from local.properties, the release build leaves them empty
        fun initAccountCredentialsState(context: Context): AccountCredentialsState =
            AccountCredentialsState(
                url = context.getString(R.string.debug_freshrss_url),
                login = context.getString(R.string.debug_freshrss_login),
                password = context.getString(R.string.debug_freshrss_password),
            )
    }
}

/**
 * The account this screen logs in with: the one it was opened on, with the
 * fields as they stand on screen and **no token**.
 *
 * The token and the write token are dropped rather than carried over. This
 * screen can edit the server URL, and a token belongs to the server that issued
 * it: keeping it would take one server's token to another one. The login about
 * to run asks the server named here for a token of its own, so there is nothing
 * to preserve either.
 */
internal fun accountToLogInWith(
    account: Account,
    url: String,
    name: String,
    login: String,
    password: String
): Account = account.copy(
    url = url,
    name = name,
    login = login,
    password = password,
    token = null,
    writeToken = null
)

/** A scheme at the start of an address: letters, then `://`. */
private val URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

/**
 * Whether the server address as typed on screen would be fetched in the clear.
 *
 * The login sends the password and every later call carries the token, so an
 * `http://` server means both go out readable by anyone on the way. The screen
 * refuses such an address before it builds a request; the network security
 * config refuses it a second time, at the socket.
 *
 * The decision is made on the **parsed** address, not on the text: a query
 * string may hold `http://` without the address itself being cleartext. The
 * scheme is compared in lower case, because `HTTP://` is the same scheme, and
 * whitespace around what was typed is dropped.
 *
 * An address with no scheme is read as `https://` — which is what
 * [Utils.normalizeUrl] does with it afterwards — so `rss.lan` is accepted and
 * reached over TLS.
 *
 * Text that is no address at all is not cleartext: there is nothing to refuse
 * here, and the empty-field check and the login's own error report it.
 */
internal fun serverUrlIsCleartext(typedUrl: String): Boolean {
    val trimmed = typedUrl.trim()
    val withScheme =
        if (URL_SCHEME.containsMatchIn(trimmed)) trimmed else "https://$trimmed"

    val parsed = withScheme.toHttpUrlOrNull()

    return if (parsed != null) {
        parsed.scheme == "http"
    } else {
        // A scheme with nothing usable after it does not parse, and "http://"
        // on its own is still someone asking for plain HTTP.
        trimmed.startsWith("http://", ignoreCase = true)
    }
}

data class AccountCredentialsState(
    val url: String = "https://",
    val urlError: TextFieldError? = null,
    val name: String = "",
    val nameError: TextFieldError? = null,
    val login: String = "",
    val loginError: TextFieldError? = null,
    val password: String = "",
    val passwordError: TextFieldError? = null,
    val isPasswordVisible: Boolean = false,
    val isLoginOnGoing: Boolean = false,
    val exitScreen: Boolean = false,
    val loginException: Exception? = null
) {
    val isUrlError = urlError != null

    val isNameError = nameError != null

    val isLoginError = loginError != null

    val isPasswordError = passwordError != null
}

sealed class Event(val value: String) {
    class URLEvent(value: String) : Event(value)
    class NameEvent(value: String) : Event(value)
    class LoginEvent(value: String) : Event(value)
    class PasswordEvent(value: String) : Event(value)
}