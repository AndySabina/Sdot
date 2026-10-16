package app.sdot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager

/**
 * The call: a microphone foreground service that streams agent events, speaks them,
 * and sends what the user says back to the pi session. Runs with the screen off.
 */
class CallService : Service(), SdotClient.Listener, Ears.Listener, Mouth.Listener {
    private var client: SdotClient? = null
    private var ears: Ears? = null
    private var mouth: Mouth? = null
    private var earcons: Earcons? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var connected = false
    private var reconnecting = false
    private var agentBusy = false
    private var speaking = false
    private var idleTonePending = false
    private var shownStatus: CallStatus? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCall()
            ACTION_HANG_UP -> hangUp()
            ACTION_INTERRUPT -> mouth?.stop()
            ACTION_ABORT -> abortAgent()
        }
        if (client == null) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hangUp()
        super.onDestroy()
    }

    private fun startCall() {
        if (client != null) return
        // startForegroundService() requires startForeground() even when the call cannot proceed.
        try {
            startForeground(NOTIFICATION_ID, notification(CallStatus.CONNECTING), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) {
            // Missing microphone permission or started from the background.
            CallHub.update { it.copy(notice = getString(R.string.notice_start_failed)) }
            return
        }
        val pairing = PairingStore.load(this) ?: run {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sdot:call")
            .apply { acquire() }
        connected = false
        reconnecting = false
        agentBusy = false
        speaking = false
        idleTonePending = false
        shownStatus = null
        CallHub.update { it.copy(inCall = true, status = CallStatus.CONNECTING, notice = null) }
        earcons = Earcons()
        mouth = Mouth(this, this)
        ears = Ears(this, this).also { it.start() }
        client = SdotClient(pairing, this).also { it.start() }
        refresh()
    }

    private fun hangUp() {
        val active = client ?: return
        client = null
        active.stop()
        ears?.stop()
        ears = null
        mouth?.shutdown()
        mouth = null
        earcons?.release()
        earcons = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        CallHub.update { it.copy(inCall = false, status = CallStatus.IDLE) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun abortAgent() {
        val active = client ?: return
        mouth?.stop()
        active.abort { result ->
            when (result) {
                SdotClient.Result.OK -> Unit
                SdotClient.Result.UNAUTHORIZED -> onUnauthorized()
                SdotClient.Result.FAILED -> notice(getString(R.string.notice_abort_failed))
            }
        }
    }

    // SdotClient.Listener

    override fun onConnecting(reconnecting: Boolean) {
        connected = false
        this.reconnecting = reconnecting
        refresh()
    }

    override fun onEvent(type: String, data: Map<String, Any?>) {
        if (client == null) return
        when (type) {
            "hello" -> {
                connected = true
                agentBusy = data["busy"] == true
            }
            "speech" -> (data["text"] as? String)?.takeIf { it.isNotBlank() }?.let(::speak)
            "activity" -> {
                agentBusy = true
                if (!speaking) earcons?.activity()
            }
            "busy" -> agentBusy = true
            "idle" -> {
                agentBusy = false
                if (data["aborted"] == true) CallHub.addLine(getString(R.string.transcript_stopped))
                if (speaking) idleTonePending = true else earcons?.idle()
            }
        }
        refresh()
    }

    override fun onUnauthorized() {
        if (client == null) return
        hangUp()
        notice(getString(R.string.notice_unauthorized))
    }

    // Ears.Listener

    override fun onHeard(text: String) {
        val active = client ?: return
        CallHub.addLine(getString(R.string.transcript_you, text))
        active.say(text) { result ->
            when (result) {
                SdotClient.Result.OK -> Unit
                SdotClient.Result.UNAUTHORIZED -> onUnauthorized()
                SdotClient.Result.FAILED -> notice(getString(R.string.notice_send_failed))
            }
        }
    }

    override fun onSpeechStart() {
        // Barge-in: only reachable while speaking when a headset keeps the recognizer running.
        if (speaking && headset()) mouth?.stop()
    }

    override fun onNotice(message: String) = notice(message)

    // Mouth.Listener

    override fun onSpeakingChanged(speaking: Boolean) {
        this.speaking = speaking
        if (!speaking) {
            if (idleTonePending) {
                idleTonePending = false
                earcons?.idle()
            }
            ears?.resume(RESUME_AFTER_SPEECH_MS)
        }
        refresh()
    }

    override fun onUnavailable() = notice(getString(R.string.notice_tts_unavailable))

    override fun onVoiceChosen(name: String?, lowQuality: Boolean) {
        val voice = name?.let { getString(R.string.notice_voice, it) }
        val hint = if (lowQuality) getString(R.string.notice_voice_hint) else null
        val message = listOfNotNull(voice, hint).joinToString(" ")
        // Shown once per call; keeps an earlier notice (for example a missing speech pack) visible.
        CallHub.update { state -> state.copy(notice = state.notice?.let { "$it\n$message" } ?: message) }
    }

    private fun speak(text: String) {
        CallHub.addLine(getString(R.string.transcript_agent, text))
        // Without a headset the loudspeaker would feed the agent's voice back into the recognizer.
        if (!headset()) ears?.pause()
        mouth?.speak(text)
    }

    private fun headset() = Headset.connected(getSystemService(AudioManager::class.java))

    private fun notice(message: String) = CallHub.update { it.copy(notice = message) }

    private fun refresh() {
        if (client == null) return
        val status = when {
            !connected && reconnecting -> CallStatus.RECONNECTING
            !connected -> CallStatus.CONNECTING
            speaking -> CallStatus.SPEAKING
            agentBusy -> CallStatus.WORKING
            else -> CallStatus.LISTENING
        }
        CallHub.update { it.copy(status = status) }
        if (status != shownStatus) {
            shownStatus = status
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
        }
    }

    private fun notification(status: CallStatus): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_call), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(statusLabel(status)))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(action(ACTION_INTERRUPT, R.string.interrupt, 1))
            .addAction(action(ACTION_HANG_UP, R.string.hang_up, 2))
            .build()
    }

    private fun action(action: String, label: Int, requestCode: Int): Notification.Action {
        val pending = PendingIntent.getService(this, requestCode, intent(this, action), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_notification), getString(label), pending).build()
    }

    companion object {
        const val ACTION_START = "app.sdot.action.START"
        const val ACTION_HANG_UP = "app.sdot.action.HANG_UP"
        const val ACTION_INTERRUPT = "app.sdot.action.INTERRUPT"
        const val ACTION_ABORT = "app.sdot.action.ABORT"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "call"
        private const val RESUME_AFTER_SPEECH_MS = 400L

        fun intent(context: Context, action: String): Intent =
            Intent(context, CallService::class.java).setAction(action)

        fun statusLabel(status: CallStatus): Int = when (status) {
            CallStatus.IDLE -> R.string.status_idle
            CallStatus.CONNECTING -> R.string.status_connecting
            CallStatus.LISTENING -> R.string.status_listening
            CallStatus.WORKING -> R.string.status_working
            CallStatus.SPEAKING -> R.string.status_speaking
            CallStatus.RECONNECTING -> R.string.status_reconnecting
        }
    }
}
