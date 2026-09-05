package com.readrops.db.entities.account

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.readrops.db.R

enum class AccountType(
    @DrawableRes val iconRes: Int,
    @StringRes val nameRes: Int,
    val config: AccountConfig
) {
    FRESHRSS(R.drawable.ic_freshrss, R.string.freshrss, AccountConfig.FRESHRSS)
}
