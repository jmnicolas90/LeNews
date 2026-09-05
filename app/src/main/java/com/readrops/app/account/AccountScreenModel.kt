package com.readrops.app.account

import android.content.Context
import androidx.compose.runtime.Stable
import cafe.adriel.voyager.core.model.screenModelScope
import com.readrops.app.home.TabScreenModel
import com.readrops.app.util.components.TextFieldError
import com.readrops.app.util.components.dialog.TextFieldDialogState
import com.readrops.db.Database
import com.readrops.db.entities.account.Account
import com.readrops.db.entities.account.AccountType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AccountScreenModel(
    private val database: Database,
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : TabScreenModel(database, context) {

    private val _closeHome = MutableStateFlow(false)
    val closeHome = _closeHome.asStateFlow()

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

        screenModelScope.launch(dispatcher) {
            database.accountDao().selectAllAccounts()
                .map { it.filter { account -> !account.isCurrentAccount } }
                .collect { accounts ->
                    _accountState.update { it.copy(accounts = accounts) }
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

    fun deleteAccount() {
        screenModelScope.launch(dispatcher) {
            database.accountDao()
                .delete(currentAccount!!)

            if (_accountState.value.accounts.isNotEmpty()) {
                database.accountDao().updateCurrentAccount(_accountState.value.accounts.first().id)
            } else {
                _closeHome.update { true }
            }
        }
    }

    fun resetCloseHome() = _closeHome.update { false }

    fun updateCurrentAccount(account: Account) {
        screenModelScope.launch(dispatcher) {
            database.accountDao().updateCurrentAccount(account.id)
        }
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
            database.accountDao().renameAccount(value.account.id, value.renameAccountState.value)
            closeDialog()
        }
    }
}

@Stable
data class AccountState(
    val account: Account = Account(name = "account", type = AccountType.FRESHRSS),
    val dialog: DialogState? = null,
    val accounts: List<Account> = emptyList(),
    val renameAccountState: TextFieldDialogState = TextFieldDialogState()
)

sealed interface DialogState {
    data object DeleteAccount : DialogState
    data object NewAccount : DialogState
    data class RenameAccount(val name: String) : DialogState
}