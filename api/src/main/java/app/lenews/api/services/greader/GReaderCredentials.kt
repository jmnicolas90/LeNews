package app.lenews.api.services.greader

import app.lenews.api.services.Credentials

class GReaderCredentials(token: String?, url: String) :
        Credentials(token?.let { AUTH_PREFIX + it }, url) {

    companion object {
        private const val AUTH_PREFIX = "GoogleLogin auth="
    }
}