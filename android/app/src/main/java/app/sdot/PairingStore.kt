package app.sdot

import android.content.Context

/** Persists the pairing in app-private preferences (excluded from backups and device transfer). */
object PairingStore {
    private const val FILE = "pairing"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"

    fun load(context: Context): Pairing? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val url = prefs.getString(KEY_URL, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        return Pairing(url, token)
    }

    fun save(context: Context, pairing: Pairing) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, pairing.url)
            .putString(KEY_TOKEN, pairing.token)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
