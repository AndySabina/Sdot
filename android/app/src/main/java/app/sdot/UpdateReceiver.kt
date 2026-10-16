package app.sdot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives PackageInstaller session results for in-app updates. Not exported. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Updater.onInstallStatus(context, intent)
    }
}
