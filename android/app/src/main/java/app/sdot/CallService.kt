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
import android.os.SystemClock

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
    private var muted = false
    private var session = SessionLabel.FALLBACK
    private var shownNotification: Pair<CallStatus, Boolean>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCall()
            ACTION_HANG_UP -> hangUp()
            ACTION_INTERRUPT -> mouth?.stop()
            ACTION_TOGGLE_MUTE -> toggleMute()
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
            CallHub.notice(getString(R.string.notice_start_failed))
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
        muted = false
        session = SessionLabel.normalize(PairingStore.loadSession(this))
        shownNotification = null
        CallHub.update {
            it.copy(inCall = true, status = CallStatus.CONNECTING, notice = null, muted = false, connectedAtMs = null)
        }
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
        muted = false
        CallHub.update { it.copy(inCall = false, status = CallStatus.IDLE, muted = false, connectedAtMs = null) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Muting stops the recognizer and drops anything half heard; nothing is sent until unmuted. */
    private fun toggleMute() {
        if (client == null) return
        muted = !muted
        if (muted) {
            ears?.pause(discard = true)
        } else if (!speaking || headset()) {
            ears?.resume(0)
        }
        CallHub.update { it.copy(muted = muted) }
        refresh()
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
                onSession(data["session"] as? String)
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
        if (muted) return
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
        if (speaking && !muted && headset()) mouth?.stop()
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
            if (!muted) ears?.resume(RESUME_AFTER_SPEECH_MS)
        }
        refresh()
    }

    override fun onUnavailable() = notice(getString(R.string.notice_tts_unavailable))

    override fun onVoiceChosen(name: String?, lowQuality: Boolean) {
        val voice = name?.let { getString(R.string.notice_voice, it) }
        val hint = if (lowQuality) getString(R.string.notice_voice_hint) else null
        notice(listOfNotNull(voice, hint).joinToString(" "))
    }

    private fun onSession(raw: String?) {
        val name = SessionLabel.normalize(raw)
        if (name != session) {
            session = name
            PairingStore.saveSession(this, name)
            shownNotification = null
        }
        // The call timer starts at the first connection and keeps running across reconnects.
        CallHub.update { it.copy(session = name, connectedAtMs = it.connectedAtMs ?: SystemClock.elapsedRealtime()) }
    }

    private fun speak(text: String) {
        CallHub.addLine(getString(R.string.transcript_agent, text))
        // Without a headset the loudspeaker would feed the agent's voice back into the recognizer.
        if (!headset()) ears?.pause()
        mouth?.speak(text)
    }

    private fun headset() = Headset.connected(getSystemService(AudioManager::class.java))

    private fun notice(message: String) = CallHub.notice(message)

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
        if (status to muted != shownNotification) {
            shownNotification = status to muted
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
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title, session))
            .setContentText(getString(statusLabel(status, muted)))
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (status == CallStatus.SPEAKING) builder.addAction(action(ACTION_INTERRUPT, R.string.interrupt, R.drawable.ic_notification, 1))
        return builder
            .addAction(
                if (muted) {
                    action(ACTION_TOGGLE_MUTE, R.string.unmute, R.drawable.ic_mic, 3)
                } else {
                    action(ACTION_TOGGLE_MUTE, R.string.mute, R.drawable.ic_mic_off, 3)
                },
            )
            .addAction(action(ACTION_HANG_UP, R.string.hang_up, R.drawable.ic_call_end, 2))
            .build()
    }

    private fun action(action: String, label: Int, icon: Int, requestCode: Int): Notification.Action {
        val pending = PendingIntent.getService(this, requestCode, intent(this, action), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), pending).build()
    }

    companion object {
        const val ACTION_START = "app.sdot.action.START"
        const val ACTION_HANG_UP = "app.sdot.action.HANG_UP"
        const val ACTION_INTERRUPT = "app.sdot.action.INTERRUPT"
        const val ACTION_ABORT = "app.sdot.action.ABORT"
        const val ACTION_TOGGLE_MUTE = "app.sdot.action.TOGGLE_MUTE"

        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "call"
        private const val RESUME_AFTER_SPEECH_MS = 400L

        fun intent(context: Context, action: String): Intent =
            Intent(context, CallService::class.java).setAction(action)

        /** While muted, "Listening" would be untrue; the other states still describe the call. */
        fun statusLabel(status: CallStatus, muted: Boolean): Int =
            if (muted && status == CallStatus.LISTENING) R.string.status_muted else statusLabel(status)

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
