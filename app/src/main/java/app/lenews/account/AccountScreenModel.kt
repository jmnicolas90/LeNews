package app.lenews.account

import android.content.Context
import androidx.compose.runtime.Stable
import cafe.adriel.voyager.core.model.screenModelScope
import app.lenews.home.TabScreenModel
import app.lenews.util.components.TextFieldError
import app.lenews.util.components.dialog.TextFieldDialogState
import app.lenews.db.Database
import app.lenews.db.entities.account.Account
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AccountScreenModel(
    private val database: Database,
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : TabScreenModel(database, context) {

    private val _accountState = MutableStateFlow(AccountState())
    val accountState = _accountState.asStateFlow()

    init {
        screenModelScope.launch(dispatcher) {
            accountEvent.collect { account ->
                _accountState.update {
                    it.copy(
                        account = account
                    )
                }
            }
        }
    }

    fun openDialog(dialog: DialogState) {
        if (dialog is DialogState.RenameAccount) {
            _accountState.update { it.copy(renameAccountState = TextFieldDialogState(value = dialog.name)) }
        }

        _accountState.update { it.copy(dialog = dialog) }
    }

    fun closeDialog() {
        _accountState.update { it.copy(dialog = null) }
    }

    fun setAccountRenameStateName(name: String) = _accountState.update {
        it.copy(
            renameAccountState = it.renameAccountState.copy(
                value = name,
                textFieldError = null
            )
        )
    }

    fun renameAccount() = with(_accountState) {
        if (value.renameAccountState.value.isEmpty()) {
            update { it.copy(renameAccountState = it.renameAccountState.copy(textFieldError = TextFieldError.EmptyField)) }
            return@with
        }

        screenModelScope.launch(dispatcher) {
            database.accountDao().renameAccount(value.renameAccountState.value)
            closeDialog()
        }
    }
}

@Stable
data class AccountState(
    val account: Account = Account(name = "account"),
    val dialog: DialogState? = null,
    val renameAccountState: TextFieldDialogState = TextFieldDialogState()
)

sealed interface DialogState {
    data class RenameAccount(val name: String) : DialogState
}
