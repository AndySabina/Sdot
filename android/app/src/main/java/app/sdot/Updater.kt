package app.sdot

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class NeedsPermission(val release: ReleaseInfo) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    data object Verifying : UpdateState
    data object Installing : UpdateState

    /** [message] is a string resource; [arg] fills its single placeholder, if any. */
    data class Failed(val message: Int, val arg: String? = null) : UpdateState
}

/**
 * In-app updates from the latest GitHub release of [BuildConfig.UPDATE_REPO]: check, download the
 * APK into private cache, verify it is a newer build of this app signed with the same key, then
 * hand it to the PackageInstaller session API, which asks the user to confirm.
 * Network and file work runs on a background thread; state changes are delivered on the main thread.
 */
object Updater {
    private const val TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_JSON_BYTES = 1L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val APK_NAME = "update.apk"

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val listeners = LinkedHashSet<(UpdateState) -> Unit>()

    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotEmpty()

    var state: UpdateState = UpdateState.Idle
        private set

    private val busy: Boolean
        get() = state is UpdateState.Checking || state is UpdateState.Downloading ||
            state is UpdateState.Verifying || state is UpdateState.Installing

    fun addListener(listener: (UpdateState) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (UpdateState) -> Unit) {
        listeners.remove(listener)
    }

    fun check() {
        if (!enabled || busy) return
        set(UpdateState.Checking)
        worker.execute { set(fetchLatest()) }
    }

    /** Downloads, verifies and installs [release]; asks for the install permission first if needed. */
    fun install(context: Context, release: ReleaseInfo) {
        if (busy) return
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            set(UpdateState.NeedsPermission(release))
            return
        }
        val asset = release.apk ?: return
        set(UpdateState.Downloading(0))
        worker.execute {
            val dir = File(app.cacheDir, "updates")
            val apk = File(dir, APK_NAME)
            try {
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val downloaded = try {
                    download(asset, apk)
                } catch (e: IOException) {
                    false
                }
                if (!downloaded) {
                    set(UpdateState.Failed(R.string.update_failed_download))
                    return@execute
                }
                set(UpdateState.Verifying)
                val problem = verify(app, apk)
                if (problem != null) {
                    set(UpdateState.Failed(problem))
                    return@execute
                }
                set(UpdateState.Installing)
                commit(app, apk)
            } catch (e: IOException) {
                set(UpdateState.Failed(R.string.update_failed_install, e.javaClass.simpleName))
            } catch (e: PackageManager.NameNotFoundException) {
                set(UpdateState.Failed(R.string.update_failed_install, e.javaClass.simpleName))
            } catch (e: RuntimeException) {
                set(UpdateState.Failed(R.string.update_failed_install, e.javaClass.simpleName))
            } finally {
                // Partial or rejected downloads never stay around; a committed session holds its own copy.
                apk.delete()
            }
        }
    }

    /** Called after the user returns from the "Install unknown apps" settings screen. */
    fun onResume(context: Context) {
        val current = state
        if (current is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) {
            set(UpdateState.Available(current.release))
        }
    }

    /** Result of the installer session, delivered to [UpdateReceiver]. */
    fun onInstallStatus(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    set(UpdateState.Failed(R.string.update_failed_install, status.toString()))
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: RuntimeException) {
                    set(UpdateState.Failed(R.string.update_failed_install, e.javaClass.simpleName))
                }
            }
            // On success Android replaces and restarts the app; this is only seen if it survives.
            PackageInstaller.STATUS_SUCCESS -> set(UpdateState.Idle)
            PackageInstaller.STATUS_FAILURE_ABORTED -> set(UpdateState.Failed(R.string.update_cancelled))
            else -> set(UpdateState.Failed(R.string.update_failed_install, status.toString()))
        }
    }

    private fun fetchLatest(): UpdateState {
        val url = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        val body: String
        try {
            val conn = open(url)
            try {
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val code = conn.responseCode
                if (code == 404) return UpdateState.Failed(R.string.update_failed_no_release)
                if (code != 200) return UpdateState.Failed(R.string.update_failed_http, code.toString())
                val length = conn.contentLengthLong
                if (length > MAX_JSON_BYTES) return UpdateState.Failed(R.string.update_failed_bad_release)
                val bytes = conn.inputStream.use { input -> input.readNBytes((MAX_JSON_BYTES + 1).toInt()) }
                if (bytes.size > MAX_JSON_BYTES) return UpdateState.Failed(R.string.update_failed_bad_release)
                body = String(bytes, Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            return UpdateState.Failed(R.string.update_failed_network)
        }
        val release = try {
            ReleaseInfo.parse(body)
        } catch (e: IllegalArgumentException) {
            return UpdateState.Failed(R.string.update_failed_bad_release)
        } catch (e: ClassCastException) {
            return UpdateState.Failed(R.string.update_failed_bad_release)
        } ?: return UpdateState.UpToDate(BuildConfig.VERSION_NAME)
        if (Versions.compare(release.version, BuildConfig.VERSION_NAME) <= 0) {
            return UpdateState.UpToDate(BuildConfig.VERSION_NAME)
        }
        if (release.apk == null) return UpdateState.Failed(R.string.update_failed_no_apk, release.version)
        return UpdateState.Available(release)
    }

    /** Follows redirects by hand, only to allowed HTTPS hosts, and enforces the declared size. */
    private fun download(asset: ReleaseAsset, target: File): Boolean {
        var url = URL(asset.url)
        repeat(MAX_REDIRECTS + 1) {
            if (!ReleaseInfo.isAllowedDownloadUrl(url.toString())) return false
            val conn = open(url)
            try {
                when (val code = conn.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val location = conn.getHeaderField("Location") ?: return false
                        url = URL(url, location)
                    }
                    200 -> return save(conn, asset.size, target)
                    else -> {
                        if (code >= 400) conn.errorStream?.close()
                        return false
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        return false
    }

    private fun save(conn: HttpsURLConnection, expected: Long, target: File): Boolean {
        val declared = conn.contentLengthLong
        if (declared >= 0 && declared != expected) return false
        var written = 0L
        var shown = -1
        conn.inputStream.use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    written += n
                    if (written > expected || written > ReleaseInfo.MAX_APK_BYTES) return false
                    out.write(buffer, 0, n)
                    val percent = (written * 100 / expected).toInt()
                    if (percent != shown) {
                        shown = percent
                        set(UpdateState.Downloading(percent))
                    }
                }
            }
        }
        return written == expected
    }

    /** Returns a string resource describing why [apk] must not be installed, or null when it is fine. */
    private fun verify(context: Context, apk: File): Int? {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: return R.string.update_failed_not_an_update
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != context.packageName) return R.string.update_failed_not_an_update
        if (archive.longVersionCode <= installed.longVersionCode) return R.string.update_failed_not_an_update
        val archiveSigners = signers(archive)
        if (archiveSigners.isEmpty() || archiveSigners != signers(installed)) return R.string.update_failed_signature
        return null
    }

    private fun signers(info: PackageInfo): Set<Signature> {
        val signing = info.signingInfo ?: return emptySet()
        // Compare every certificate: all signers, or the single signer plus its rotation history.
        val list = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return list?.toSet().orEmpty()
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apk.inputStream().use { input ->
                session.openWrite(APK_NAME, 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, UpdateReceiver::class.java).setPackage(context.packageName),
                // Mutable: the installer adds the status extras to this explicit intent.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        } catch (e: IOException) {
            session.abandon()
            throw e
        } catch (e: RuntimeException) {
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }

    private fun open(url: URL): HttpsURLConnection {
        // Callers only pass https URLs (fixed API URL or allow-listed download hosts).
        val conn = url.openConnection() as HttpsURLConnection
        conn.useCaches = false
        conn.instanceFollowRedirects = false
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "Sdot/${BuildConfig.VERSION_NAME}")
        return conn
    }

    private fun set(next: UpdateState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { set(next) }
            return
        }
        if (next == state) return
        state = next
        for (listener in listeners.toList()) listener(next)
    }
}
