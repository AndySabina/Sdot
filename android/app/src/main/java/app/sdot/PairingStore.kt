package app.sdot

import android.content.Context

/** Persists the pairing in app-private preferences (excluded from backups and device transfer). */
object PairingStore {
    private const val FILE = "pairing"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"
    private const val KEY_SESSION = "session"

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

    /** Last pi session name seen in a call, shown on the idle screen; cleared with the pairing. */
    fun loadSession(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_SESSION, null)

    fun saveSession(context: Context, name: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_SESSION, name).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
