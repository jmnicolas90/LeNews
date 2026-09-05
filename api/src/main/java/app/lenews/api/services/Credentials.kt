package app.lenews.api.services

import app.lenews.api.services.greader.GReaderCredentials
import app.lenews.db.entities.account.Account

abstract class Credentials(val authorization: String?, val url: String) {

    companion object {
        fun toCredentials(account: Account): Credentials =
            GReaderCredentials(account.token, account.url + GREADER_END_POINT)

        private const val GREADER_END_POINT = "api/greader.php/"
    }
}
