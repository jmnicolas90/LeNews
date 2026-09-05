package com.readrops.api.services

import com.readrops.api.services.greader.GReaderCredentials
import com.readrops.db.entities.account.Account

abstract class Credentials(val authorization: String?, val url: String) {

    companion object {
        fun toCredentials(account: Account): Credentials =
            GReaderCredentials(account.token, account.url + GREADER_END_POINT)

        private const val GREADER_END_POINT = "api/greader.php/"
    }
}
