package app.lenews.account.selection

import cafe.adriel.voyager.core.model.StateScreenModel
import app.lenews.db.Database
import app.lenews.db.entities.account.AccountType
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent

class AccountSelectionScreenModel(
    private val database: Database
) : StateScreenModel<AccountSelectionState>(AccountSelectionState()), KoinComponent {

    fun accountExists(): Boolean {
        val accountCount = runBlocking {
            database.accountDao().selectAccountCount()
        }

        return accountCount > 0
    }

    fun createAccount(accountType: AccountType) {
        mutableState.update { it.copy(accountTypeToCreate = accountType) }
    }

    fun resetAccountTypeToCreate() {
        mutableState.update { it.copy(accountTypeToCreate = null) }
    }
}

data class AccountSelectionState(
    val accountTypeToCreate: AccountType? = null
)
