package app.knotwork.android.domain.models

/**
 * The GigaChat API "version" an authorization key was issued for — the `scope` form field of the
 * OAuth request (`POST https://ngw.devices.sberbank.ru:9443/api/v2/oauth`).
 *
 * The scope is a property of the user's GigaChat project, not a free choice: a key asked for a
 * token under another scope than its project's is refused. It is therefore a setting next to the
 * key rather than something the client could guess. It is not a secret, so it is stored and backed
 * up with the provider's model, never with its key.
 *
 * @property wire The literal value sent as `scope`. Persisted, so it can never change.
 */
enum class GigaChatScope(val wire: String) {
    /** Individuals (физлица), freemium and paid packages. */
    PERSONAL("GIGACHAT_API_PERS"),

    /** Sole proprietors and legal entities on prepaid packages. */
    BUSINESS_PREPAID("GIGACHAT_API_B2B"),

    /** Sole proprietors and legal entities on pay-as-you-go. */
    BUSINESS_PAYG("GIGACHAT_API_CORP"),
    ;

    /** Parsing of the persisted value. */
    companion object {
        /** The scope used while none is saved: the one every new individual project has. */
        val DEFAULT: GigaChatScope = PERSONAL

        /**
         * The scope saved as [wire], or [DEFAULT] for `null` or an unknown value.
         *
         * @param wire The stored value.
         * @return The matching scope.
         */
        fun fromWire(wire: String?): GigaChatScope = entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}
