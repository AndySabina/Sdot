package app.sdot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pairing screen when unpaired; call controls, status and transcript when paired. */
class MainActivity : Activity() {
    private lateinit var scroll: ScrollView
    private lateinit var pairingPanel: LinearLayout
    private lateinit var callPanel: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var pairingError: TextView
    private lateinit var hostView: TextView
    private lateinit var statusView: TextView
    private lateinit var noticeView: TextView
    private lateinit var callButton: Button
    private lateinit var interruptButton: Button
    private lateinit var abortButton: Button
    private lateinit var unpairButton: Button
    private lateinit var transcriptView: TextView
    private lateinit var updateStatusView: TextView
    private lateinit var updateButton: Button

    private var localNotice: String? = null
    private val render: (CallSnapshot) -> Unit = { render(it) }
    private val renderUpdate: (UpdateState) -> Unit = { renderUpdate(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildViews())
        showPairingState()
    }

    override fun onStart() {
        super.onStart()
        CallHub.addListener(render)
        Updater.addListener(renderUpdate)
    }

    override fun onResume() {
        super.onResume()
        Updater.onResume(this)
    }

    override fun onStop() {
        CallHub.removeListener(render)
        Updater.removeListener(renderUpdate)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCall()
        } else {
            localNotice = getString(R.string.notice_mic_permission)
            render(CallHub.snapshot)
        }
    }

    private fun onPair() {
        try {
            val pairing = PairingCode.decode(codeInput.text.toString())
            PairingStore.save(this, pairing)
            codeInput.text.clear()
            pairingError.visibility = View.GONE
            showPairingState()
        } catch (e: PairingException) {
            pairingError.text = getString(
                when (e.reason) {
                    PairingException.Reason.MALFORMED -> R.string.pair_error_malformed
                    PairingException.Reason.NOT_HTTPS -> R.string.pair_error_not_https
                    PairingException.Reason.BAD_TOKEN -> R.string.pair_error_token
                },
            )
            pairingError.visibility = View.VISIBLE
        }
    }

    private fun onCallButton() {
        if (CallHub.snapshot.inCall) {
            startService(CallService.intent(this, CallService.ACTION_HANG_UP))
            return
        }
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startCall() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun startCall() {
        localNotice = null
        startForegroundService(CallService.intent(this, CallService.ACTION_START))
    }

    private fun onUnpair() {
        if (CallHub.snapshot.inCall) return
        PairingStore.clear(this)
        showPairingState()
    }

    private fun showPairingState() {
        val pairing = PairingStore.load(this)
        pairingPanel.visibility = if (pairing == null) View.VISIBLE else View.GONE
        callPanel.visibility = if (pairing == null) View.GONE else View.VISIBLE
        if (pairing != null) hostView.text = getString(R.string.paired_with, pairing.host)
    }

    private fun render(snapshot: CallSnapshot) {
        statusView.text = getString(CallService.statusLabel(snapshot.status))
        val notice = localNotice ?: snapshot.notice
        noticeView.text = notice
        noticeView.visibility = if (notice == null) View.GONE else View.VISIBLE
        callButton.text = getString(if (snapshot.inCall) R.string.hang_up else R.string.call)
        interruptButton.isEnabled = snapshot.inCall
        abortButton.isEnabled = snapshot.inCall
        unpairButton.isEnabled = !snapshot.inCall
        transcriptView.text = if (snapshot.transcript.isEmpty()) {
            getString(R.string.transcript_empty)
        } else {
            snapshot.transcript.joinToString("\n")
        }
        if (snapshot.inCall) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun onUpdateButton() {
        when (val state = Updater.state) {
            is UpdateState.Available -> Updater.install(this, state.release)
            is UpdateState.NeedsPermission -> {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                try {
                    startActivity(intent)
                } catch (e: RuntimeException) {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                }
            }
            else -> Updater.check()
        }
    }

    private fun renderUpdate(state: UpdateState) {
        updateStatusView.text = when (state) {
            UpdateState.Idle -> null
            UpdateState.Checking -> getString(R.string.update_checking)
            is UpdateState.UpToDate -> getString(R.string.update_up_to_date, state.version)
            is UpdateState.Available -> getString(R.string.update_available, state.release.version)
            is UpdateState.NeedsPermission -> getString(R.string.update_permission)
            is UpdateState.Downloading -> getString(R.string.update_downloading, state.percent)
            UpdateState.Verifying -> getString(R.string.update_verifying)
            UpdateState.Installing -> getString(R.string.update_installing)
            is UpdateState.Failed -> if (state.arg == null) getString(state.message) else getString(state.message, state.arg)
        }
        updateStatusView.visibility = if (state == UpdateState.Idle) View.GONE else View.VISIBLE
        updateButton.setText(
            when (state) {
                is UpdateState.Available -> R.string.update_install
                is UpdateState.NeedsPermission -> R.string.update_open_settings
                else -> R.string.update_check
            },
        )
        updateButton.isEnabled = when (state) {
            UpdateState.Checking, is UpdateState.Downloading, UpdateState.Verifying, UpdateState.Installing -> false
            else -> true
        }
    }

    private fun buildViews(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(text(R.string.app_name, 28f, bold = true))

        pairingPanel = column()
        pairingPanel.addView(text(R.string.pair_title, 20f, bold = true))
        pairingPanel.addView(text(R.string.pair_help, 15f))
        codeInput = EditText(this).apply {
            hint = getString(R.string.pair_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            setSingleLine(false)
            maxLines = 4
        }
        pairingPanel.addView(codeInput, wide())
        pairingError = text(0, 14f).apply {
            setTextColor(getColor(android.R.color.holo_red_dark))
            visibility = View.GONE
        }
        pairingPanel.addView(pairingError)
        pairingPanel.addView(button(R.string.pair) { onPair() }, wide())
        content.addView(pairingPanel, wide())

        callPanel = column()
        hostView = text(0, 15f)
        callPanel.addView(hostView)
        statusView = text(R.string.status_idle, 22f, bold = true)
        callPanel.addView(statusView)
        noticeView = text(0, 15f).apply { visibility = View.GONE }
        callPanel.addView(noticeView)
        callButton = button(R.string.call) { onCallButton() }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            minHeight = dp(88)
        }
        callPanel.addView(callButton, wide())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        interruptButton = button(R.string.interrupt) {
            startService(CallService.intent(this, CallService.ACTION_INTERRUPT))
        }
        abortButton = button(R.string.stop_agent) {
            startService(CallService.intent(this, CallService.ACTION_ABORT))
        }
        row.addView(interruptButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(abortButton, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        callPanel.addView(row, wide())
        callPanel.addView(text(R.string.transcript_title, 18f, bold = true))
        transcriptView = text(R.string.transcript_empty, 15f).apply { setTextIsSelectable(true) }
        callPanel.addView(transcriptView, wide())
        unpairButton = button(R.string.unpair) { onUnpair() }
        callPanel.addView(unpairButton, wide())
        content.addView(callPanel, wide())

        // Updates sit below both panels, so they are reachable paired or not.
        updateStatusView = text(0, 14f).apply { visibility = View.GONE }
        updateButton = button(R.string.update_check) { onUpdateButton() }
        if (Updater.enabled) {
            content.addView(updateStatusView, wide())
            content.addView(updateButton, wide())
        }
        content.addView(
            text(0, 12f).apply {
                text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
                alpha = 0.6f
            },
        )

        scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // targetSdk 35+ is always edge-to-edge: keep content clear of system bars and the keyboard.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        return scroll
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun text(res: Int, sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
        if (res != 0) setText(res)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun button(res: Int, onClick: () -> Unit) = Button(this).apply {
        setText(res)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun wide() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
