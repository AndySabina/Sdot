package app.sdot

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pairing screen when unpaired; otherwise a phone-call screen: the session circle, the call timer,
 * and Mute and Hang up (or Call when idle). Everything else lives in the overflow menu.
 */
class MainActivity : Activity() {
    private lateinit var moreButton: ImageButton
    private lateinit var circle: FrameLayout
    private lateinit var halo: View
    private lateinit var sessionView: TextView
    private lateinit var timerView: TextView
    private lateinit var statusView: TextView
    private lateinit var noticeView: TextView
    private lateinit var pairingPanel: LinearLayout
    private lateinit var codeInput: EditText
    private lateinit var pairingError: TextView
    private lateinit var idleControls: View
    private lateinit var callControls: View
    private lateinit var muteButton: ImageButton

    private val main = Handler(Looper.getMainLooper())
    private var pairing: Pairing? = null
    private var storedSession: String? = null
    private var shownMuted: Boolean? = null
    private var speaking = false
    private var pulse: ValueAnimator? = null
    private var updateDialog: AlertDialog? = null
    private val notices = ArrayDeque<String>()
    private var noticeShowing = false

    private val render: (CallSnapshot) -> Unit = { render(it) }
    private val renderUpdate: (UpdateState) -> Unit = { renderUpdate(it) }
    private val tick = Runnable { updateTimer() }
    private val nextNotice = Runnable { showNextNotice() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildViews())
    }

    override fun onStart() {
        super.onStart()
        loadPairing()
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
        main.removeCallbacks(tick)
        setSpeaking(false)
        super.onStop()
    }

    override fun onDestroy() {
        updateDialog?.dismiss()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCall()
        } else {
            queueNotice(getString(R.string.notice_mic_permission))
        }
    }

    // Actions

    private fun onPair() {
        try {
            val pairing = PairingCode.decode(codeInput.text.toString())
            PairingStore.save(this, pairing)
            codeInput.text.clear()
            pairingError.visibility = View.GONE
            loadPairing()
            render(CallHub.snapshot)
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

    private fun onCall() {
        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startCall() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun startCall() {
        startForegroundService(CallService.intent(this, CallService.ACTION_START))
    }

    private fun send(action: String) {
        startService(CallService.intent(this, action))
    }

    private fun onUnpair() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpair_confirm_title)
            .setMessage(R.string.unpair_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.unpair) { _, _ ->
                if (!CallHub.snapshot.inCall) {
                    PairingStore.clear(this)
                    CallHub.update { it.copy(session = null) }
                    loadPairing()
                    render(CallHub.snapshot)
                }
            }
            .show()
    }

    private fun showMenu() {
        val inCall = CallHub.snapshot.inCall
        val popup = PopupMenu(this, moreButton)
        val menu = popup.menu
        if (pairing != null) menu.add(0, MENU_TRANSCRIPT, 0, R.string.transcript_title)
        if (inCall) menu.add(0, MENU_STOP_AGENT, 1, R.string.stop_agent)
        if (Updater.enabled) menu.add(0, MENU_UPDATES, 2, R.string.update_check)
        if (pairing != null) menu.add(0, MENU_UNPAIR, 3, R.string.unpair).isEnabled = !inCall
        menu.add(0, MENU_VERSION, 4, getString(R.string.version_label, BuildConfig.VERSION_NAME)).isEnabled = false
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_TRANSCRIPT -> showTranscript()
                MENU_STOP_AGENT -> send(CallService.ACTION_ABORT)
                MENU_UPDATES -> showUpdates()
                MENU_UNPAIR -> onUnpair()
            }
            true
        }
        popup.show()
    }

    private fun showTranscript() {
        val lines = CallHub.snapshot.transcript
        val body = TextView(this).apply {
            text = if (lines.isEmpty()) getString(R.string.transcript_empty) else lines.joinToString("\n\n")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(getColor(R.color.call_on_surface))
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle(R.string.transcript_title)
            .setView(scroll)
            .setPositiveButton(R.string.close, null)
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showUpdates() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_title)
            // A placeholder message creates the message view that renderUpdate() fills in.
            .setMessage(" ")
            .setPositiveButton(R.string.update_check, null)
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.setOnShowListener {
            // Replaces the default listener so the dialog stays open while the update progresses.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onUpdateAction() }
            renderUpdate(Updater.state)
        }
        dialog.setOnDismissListener { if (updateDialog === dialog) updateDialog = null }
        updateDialog = dialog
        dialog.show()
        when (Updater.state) {
            UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed -> Updater.check()
            else -> Unit
        }
    }

    private fun onUpdateAction() {
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

    // Rendering

    private fun loadPairing() {
        pairing = PairingStore.load(this)
        storedSession = PairingStore.loadSession(this)
        val paired = pairing != null
        pairingPanel.visibility = if (paired) View.GONE else View.VISIBLE
        statusView.visibility = if (paired) View.VISIBLE else View.GONE
        if (!paired) {
            idleControls.visibility = View.GONE
            callControls.visibility = View.GONE
            timerView.visibility = View.GONE
        }
    }

    private fun render(snapshot: CallSnapshot) {
        if (snapshot.noticeSeq != lastNoticeSeq) {
            lastNoticeSeq = snapshot.noticeSeq
            snapshot.notice?.let(::queueNotice)
        }
        val current = pairing
        val name = if (current == null) SessionLabel.FALLBACK else snapshot.session ?: storedSession ?: SessionLabel.FALLBACK
        sessionView.text = name
        circle.contentDescription = getString(R.string.session_description, name)
        // Tapping the circle cuts the agent's speech short.
        circle.isClickable = snapshot.inCall
        circle.isFocusable = snapshot.inCall
        if (current == null) {
            setSpeaking(false)
            return
        }
        statusView.text = if (snapshot.inCall) {
            getString(CallService.statusLabel(snapshot.status, snapshot.muted))
        } else {
            getString(R.string.paired_with, current.host)
        }
        idleControls.visibility = if (snapshot.inCall) View.GONE else View.VISIBLE
        callControls.visibility = if (snapshot.inCall) View.VISIBLE else View.GONE
        renderMute(snapshot.muted)
        updateTimer()
        setSpeaking(snapshot.inCall && snapshot.status == CallStatus.SPEAKING)
    }

    private fun renderMute(muted: Boolean) {
        if (muted == shownMuted) return
        shownMuted = muted
        muteButton.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        muteButton.imageTintList = ColorStateList.valueOf(getColor(if (muted) R.color.call_background else R.color.call_on_surface))
        muteButton.background = roundBackground(getColor(if (muted) R.color.call_on_surface else R.color.call_surface))
        muteButton.contentDescription = getString(if (muted) R.string.unmute_description else R.string.mute_description)
        muteButton.stateDescription = getString(if (muted) R.string.state_muted else R.string.state_unmuted)
    }

    /** Shows the call time and schedules the next tick on the second boundary; ticks stop in onStop. */
    private fun updateTimer() {
        main.removeCallbacks(tick)
        val snapshot = CallHub.snapshot
        if (pairing == null || !snapshot.inCall) {
            timerView.visibility = View.GONE
            return
        }
        timerView.visibility = View.VISIBLE
        val started = snapshot.connectedAtMs
        if (started == null) {
            timerView.text = CallTimer.format(0)
            timerView.alpha = 0.4f
            return
        }
        timerView.alpha = 1f
        val now = SystemClock.elapsedRealtime()
        timerView.text = CallTimer.format(CallTimer.elapsedSeconds(started, now))
        main.postDelayed(tick, 1000 - (now - started).coerceAtLeast(0) % 1000)
    }

    /** A slow glow around the circle while the agent talks; still when animations are turned off. */
    private fun setSpeaking(on: Boolean) {
        if (on == speaking) return
        speaking = on
        pulse?.cancel()
        pulse = null
        if (!on) {
            halo.alpha = 0f
            halo.scaleX = 1f
            halo.scaleY = 1f
            circle.scaleX = 1f
            circle.scaleY = 1f
            return
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            halo.alpha = 0.3f
            halo.scaleX = 1.08f
            halo.scaleY = 1.08f
            return
        }
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                halo.scaleX = 1f + 0.2f * f
                halo.scaleY = halo.scaleX
                halo.alpha = 0.45f * (1f - f)
                val breath = 1f + 0.025f * sin(PI * f).toFloat()
                circle.scaleX = breath
                circle.scaleY = breath
            }
            start()
        }
    }

    private fun queueNotice(text: String) {
        notices.addLast(text)
        if (!noticeShowing) showNextNotice()
    }

    /** One notice at a time under the status line, each hidden after a time that grows with its length. */
    private fun showNextNotice() {
        main.removeCallbacks(nextNotice)
        val text = notices.removeFirstOrNull()
        noticeShowing = text != null
        noticeView.text = text
        noticeView.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) main.postDelayed(nextNotice, (NOTICE_MIN_MS + text.length * NOTICE_MS_PER_CHAR).coerceAtMost(NOTICE_MAX_MS))
    }

    private fun showNoticeDetails() {
        val text = noticeView.text?.toString() ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.notice_details)
            .setMessage(text)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun renderUpdate(state: UpdateState) {
        val dialog = updateDialog ?: return
        dialog.setMessage(
            when (state) {
                UpdateState.Idle -> ""
                UpdateState.Checking -> getString(R.string.update_checking)
                is UpdateState.UpToDate -> getString(R.string.update_up_to_date, state.version)
                is UpdateState.Available -> getString(R.string.update_available, state.release.version)
                is UpdateState.NeedsPermission -> getString(R.string.update_permission)
                is UpdateState.Downloading -> getString(R.string.update_downloading, state.percent)
                UpdateState.Verifying -> getString(R.string.update_verifying)
                UpdateState.Installing -> getString(R.string.update_installing)
                is UpdateState.Failed -> if (state.arg == null) getString(state.message) else getString(state.message, state.arg)
            },
        )
        val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        action.setText(
            when (state) {
                is UpdateState.Available -> R.string.update_install
                is UpdateState.NeedsPermission -> R.string.update_open_settings
                else -> R.string.update_check
            },
        )
        action.isEnabled = when (state) {
            UpdateState.Checking, is UpdateState.Downloading, UpdateState.Verifying, UpdateState.Installing -> false
            else -> true
        }
    }

    // Views

    private fun buildViews(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(4), dp(24), dp(32))
        }

        moreButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(getColor(R.color.call_text_secondary))
            background = RippleDrawable(ColorStateList.valueOf(getColor(R.color.call_surface_pressed)), null, null)
            contentDescription = getString(R.string.more_options)
            setOnClickListener { showMenu() }
        }
        val topBar = FrameLayout(this).apply {
            addView(moreButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
        }
        content.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))

        content.addView(buildCircle(), LinearLayout.LayoutParams(dp(CIRCLE_BOX_DP), dp(CIRCLE_BOX_DP)))

        timerView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            setTextColor(getColor(R.color.call_on_surface))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        content.addView(timerView, centered(top = 8))

        statusView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(getColor(R.color.call_text_secondary))
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(statusView, centered(top = 4))

        noticeView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(getColor(R.color.call_text_secondary))
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(48)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
            setOnClickListener { showNoticeDetails() }
        }
        content.addView(noticeView, centered(top = 12).apply { width = MATCH_PARENT })

        content.addView(buildPairingPanel(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })

        // Pushes the call buttons to the bottom; the notice above can never overlap them.
        content.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(32) })

        idleControls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(labeled(roundButton(R.drawable.ic_call, R.color.call_accept, R.string.call_description) { onCall() }, R.string.call))
            visibility = View.GONE
        }
        content.addView(idleControls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        muteButton = roundButton(R.drawable.ic_mic, R.color.call_surface, R.string.mute_description) {
            send(CallService.ACTION_TOGGLE_MUTE)
        }
        val hangUp = roundButton(R.drawable.ic_call_end, R.color.call_hang_up, R.string.hang_up) {
            send(CallService.ACTION_HANG_UP)
        }
        callControls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(labeled(muteButton, R.string.mute), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(labeled(hangUp, R.string.hang_up), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            visibility = View.GONE
        }
        content.addView(callControls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.call_background))
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // targetSdk 35+ is always edge-to-edge: keep content clear of system bars, cutouts and the keyboard.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
    }

    private fun buildCircle(): View {
        halo = View(this).apply {
            background = sessionGradient()
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        sessionView = TextView(this).apply {
            setTextColor(getColor(R.color.call_on_surface))
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(dp(6).toFloat(), 0f, dp(1).toFloat(), SHADOW_COLOR)
            setAutoSizeTextTypeUniformWithConfiguration(14, 34, 1, TypedValue.COMPLEX_UNIT_SP)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            text = SessionLabel.FALLBACK
        }
        circle = FrameLayout(this).apply {
            background = sessionGradient()
            foreground = RippleDrawable(
                ColorStateList.valueOf(getColor(R.color.call_surface_pressed)),
                null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) },
            )
            addView(sessionView, FrameLayout.LayoutParams(dp(CIRCLE_DP * 3 / 4), dp(CIRCLE_DP / 2), Gravity.CENTER))
            setOnClickListener { send(CallService.ACTION_INTERRUPT) }
            isClickable = false
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    if (host.isClickable) {
                        info.addAction(
                            AccessibilityNodeInfo.AccessibilityAction(
                                AccessibilityNodeInfo.ACTION_CLICK,
                                getString(R.string.session_interrupt_action),
                            ),
                        )
                    }
                }
            }
        }
        return FrameLayout(this).apply {
            addView(halo, FrameLayout.LayoutParams(dp(CIRCLE_DP), dp(CIRCLE_DP), Gravity.CENTER))
            addView(circle, FrameLayout.LayoutParams(dp(CIRCLE_DP), dp(CIRCLE_DP), Gravity.CENTER))
        }
    }

    private fun buildPairingPanel(): View {
        pairingPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        pairingPanel.addView(
            TextView(this).apply {
                setText(R.string.pair_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.call_on_surface))
                gravity = Gravity.CENTER
            },
        )
        pairingPanel.addView(
            TextView(this).apply {
                setText(R.string.pair_help)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(getColor(R.color.call_text_secondary))
                setLineSpacing(0f, 1.2f)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) },
        )
        codeInput = EditText(this).apply {
            hint = getString(R.string.pair_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // The pairing code is data to compare by eye, so it is set in monospace.
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.call_on_surface))
            setHintTextColor(getColor(R.color.call_text_secondary))
            setSingleLine(false)
            maxLines = 4
            minHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(getColor(R.color.call_surface))
                setStroke(dp(1), getColor(R.color.call_outline))
            }
        }
        pairingPanel.addView(codeInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        pairingError = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.call_error))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
        }
        pairingPanel.addView(pairingError, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        val pair = Button(this).apply {
            setText(R.string.pair)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.call_background))
            minHeight = dp(56)
            stateListAnimator = null
            background = RippleDrawable(
                ColorStateList.valueOf(getColor(R.color.call_outline)),
                GradientDrawable().apply {
                    cornerRadius = dp(28).toFloat()
                    setColor(getColor(R.color.call_on_surface))
                },
                null,
            )
            setOnClickListener { onPair() }
        }
        pairingPanel.addView(pair, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        return pairingPanel
    }

    private fun sessionGradient() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            getColor(R.color.session_gradient_start),
            getColor(R.color.session_gradient_middle),
            getColor(R.color.session_gradient_end),
        ),
    ).apply { shape = GradientDrawable.OVAL }

    private fun roundBackground(color: Int): Drawable = RippleDrawable(
        ColorStateList.valueOf(getColor(R.color.call_surface_pressed)),
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        },
        null,
    )

    private fun roundButton(icon: Int, fill: Int, description: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(getColor(R.color.call_on_surface))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(22), dp(22), dp(22), dp(22))
        background = roundBackground(getColor(fill))
        contentDescription = getString(description)
        setOnClickListener { onClick() }
    }

    /** A round button with its label underneath; the label is visual only, the button carries the description. */
    private fun labeled(button: ImageButton, label: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(button, LinearLayout.LayoutParams(dp(BUTTON_DP), dp(BUTTON_DP)))
        addView(
            TextView(this@MainActivity).apply {
                setText(label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(getColor(R.color.call_text_secondary))
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) },
        )
    }

    private fun centered(top: Int) = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_PERMISSIONS = 1

        const val MENU_TRANSCRIPT = 1
        const val MENU_STOP_AGENT = 2
        const val MENU_UPDATES = 3
        const val MENU_UNPAIR = 4
        const val MENU_VERSION = 5

        const val CIRCLE_DP = 184
        // Room for the speaking halo, which grows to 1.2x the circle.
        const val CIRCLE_BOX_DP = 232
        const val BUTTON_DP = 72
        const val PULSE_MS = 1800L
        const val SHADOW_COLOR = 0x66000000

        const val NOTICE_MIN_MS = 4000L
        const val NOTICE_MS_PER_CHAR = 40L
        const val NOTICE_MAX_MS = 10_000L

        /** Last notice shown, kept across activity recreation so an old notice is not shown again. */
        var lastNoticeSeq = 0
    }
}
