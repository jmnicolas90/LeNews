package app.lenews.account.credentials

import android.content.Context
import android.content.SharedPreferences
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import app.lenews.R
import app.lenews.repositories.BaseRepository
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
            // The address is read once, here, and what comes back is what the
            // request is built with and what is stored. Checking one reading of
            // the typed text and sending another is how an address refused on
            // screen can still reach a socket.
            val serverUrl = validateFields() ?: return@launch

            mutableState.update { it.copy(isLoginOnGoing = true) }

            with(state.value) {
                val newAccount = accountToLogInWith(
                    account = account,
                    url = serverUrl,
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

    /**
     * The server address to log in with, or null when something on the screen
     * is wrong — in which case the fields now say what.
     *
     * The address it returns is the canonical one [canonicalServerUrl] built,
     * and the login request and the stored account both use exactly that. The
     * address is refused here, before a request exists, so that a password
     * never leaves the phone readable.
     */
    private fun validateFields(): String? = with(mutableState.value) {
        mutableState.update { it.copy(loginException = null) }

        val serverUrl = canonicalServerUrl(url)

        val urlProblem = when (serverUrl) {
            is ServerUrl.Usable -> null
            ServerUrl.Missing -> TextFieldError.EmptyField
            ServerUrl.NotHttps -> TextFieldError.CleartextUrl
            ServerUrl.CarriesAUserName -> TextFieldError.UrlWithUserName
            ServerUrl.Unreadable -> TextFieldError.BadUrl
        }

        if (urlProblem != null) {
            mutableState.update { it.copy(urlError = urlProblem) }
        }

        var validate = urlProblem == null

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

        return if (validate && serverUrl is ServerUrl.Usable) serverUrl.url else null
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
private val URL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://")

/** What [canonicalServerUrl] made of the address someone typed. */
internal sealed interface ServerUrl {

    /**
     * The address to use. This exact string is what the login request is built
     * with and what is written to `Account.url`; nothing reads the typed text
     * again.
     */
    data class Usable(val url: String) : ServerUrl

    /** Nothing was typed, or only blanks. */
    data object Missing : ServerUrl

    /** A scheme other than https, so the password would go out readable. */
    data object NotHttps : ServerUrl

    /** A user name or a password in front of the host. */
    data object CarriesAUserName : ServerUrl

    /** Text OkHttp cannot read as an address. */
    data object Unreadable : ServerUrl
}

/**
 * The server address the login will use, read from what was typed on screen —
 * once, so that what is checked and what is sent cannot differ.
 *
 * That is the whole point of this function. Deciding "is this cleartext?" on
 * one reading of the text and then building the request from another reading is
 * how `http:127.0.0.1:8888/#http://` used to pass the check and still be fetched
 * over plain HTTP: the check parsed it, the request builder did not.
 *
 * What it does, in order:
 *
 * - whitespace around the text is dropped, and text that is then empty is
 *   [ServerUrl.Missing];
 * - a scheme is read from the front, in lower case, and anything but `https` is
 *   [ServerUrl.NotHttps] — `http`, of course, but `ftp` as much;
 * - an address with no scheme is read as `https://`, so `rss.lan` is accepted
 *   and reached over TLS;
 * - what does not parse is [ServerUrl.Unreadable] rather than something to have
 *   a try at;
 * - a user name or a password in front of the host is [ServerUrl.CarriesAUserName]
 *   and refused rather than dropped: this app sends its own login and password,
 *   and a user name is how one host is written to look like another;
 * - the fragment and the query are dropped. Neither reaches a server from a
 *   base address — Retrofit resolves every call against it and keeps neither —
 *   so storing them would say something the requests do not do;
 * - a path that does not end in `/` gets one, because every call is resolved
 *   against this address as a base.
 */
internal fun canonicalServerUrl(typedUrl: String): ServerUrl {
    val trimmed = typedUrl.trim()

    if (trimmed.isEmpty()) return ServerUrl.Missing

    val scheme = URL_SCHEME.find(trimmed)?.groupValues?.get(1)?.lowercase()

    if (scheme != null && scheme != "https") return ServerUrl.NotHttps

    val withScheme = if (scheme == null) "https://$trimmed" else trimmed

    val parsed = withScheme.toHttpUrlOrNull() ?: return ServerUrl.Unreadable

    if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
        return ServerUrl.CarriesAUserName
    }

    val canonical = parsed.newBuilder()
        .query(null)
        .fragment(null)
        .apply { if (!parsed.encodedPath.endsWith("/")) addPathSegment("") }
        .build()

    return ServerUrl.Usable(canonical.toString())
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