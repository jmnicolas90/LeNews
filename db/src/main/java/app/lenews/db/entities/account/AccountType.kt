package app.lenews.db.entities.account

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.lenews.db.R

enum class AccountType(
    @DrawableRes val iconRes: Int,
    @StringRes val nameRes: Int,
    val config: AccountConfig
) {
    FRESHRSS(R.drawable.ic_freshrss, R.string.freshrss, AccountConfig.FRESHRSS)
}
