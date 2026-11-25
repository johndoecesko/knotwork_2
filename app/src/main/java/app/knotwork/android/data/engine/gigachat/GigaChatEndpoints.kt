package app.knotwork.android.data.engine.gigachat

/**
 * Where [GigaChatLLMClient] sends: the OAuth endpoint that turns the authorization key into an
 * access token, and the REST API the token is used on. Two hosts, both outside the system trust
 * store on Android — see `res/xml/network_security_config.xml`.
 *
 * @property oauthBaseUrl Base of the OAuth endpoint; [OAUTH_PATH] is resolved against it.
 * @property apiBaseUrl Base of the REST API, ending in its version (`/v1`); the API paths are
 *   resolved against it.
 */
data class GigaChatEndpoints(val oauthBaseUrl: String, val apiBaseUrl: String) {

    /** The production endpoints, and the paths under them. */
    companion object {
        /**
         * The OAuth endpoint (`POST /api/v2/oauth`), unchanged by the 2026 move of the API: the
         * documentation still sends every scope there.
         */
        const val OAUTH_BASE_URL: String = "https://ngw.devices.sberbank.ru:9443/api/v2"

        /**
         * The API address every account uses since 17 July 2026 ("единый целевой URL"). The former
         * `https://gigachat.devices.sberbank.ru/api/v1` stays reachable for accounts connected before
         * that date but is closed to new ones and is to be retired.
         */
        const val API_BASE_URL: String = "https://api.giga.chat/v1"

        /** The token request, relative to [OAUTH_BASE_URL]. */
        const val OAUTH_PATH: String = "oauth"

        /** Chat completions, relative to [API_BASE_URL]. */
        const val CHAT_PATH: String = "chat/completions"

        /** The model list, relative to [API_BASE_URL]. */
        const val MODELS_PATH: String = "models"

        /** Embeddings, relative to [API_BASE_URL]. */
        const val EMBEDDINGS_PATH: String = "embeddings"

        /** The endpoints a run and a connection check use. */
        val PRODUCTION: GigaChatEndpoints = GigaChatEndpoints(OAUTH_BASE_URL, API_BASE_URL)
    }
}
