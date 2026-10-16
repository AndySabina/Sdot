package app.sdot

import java.net.URI
import java.net.URISyntaxException

data class ReleaseAsset(val name: String, val url: String, val size: Long)

/**
 * The parts of a GitHub `releases/latest` response the updater needs: the tag, its page and the
 * first `.apk` asset. Download URLs must be HTTPS on GitHub's own hosts; anything else is rejected.
 */
data class ReleaseInfo(val tag: String, val version: String, val htmlUrl: String, val apk: ReleaseAsset?) {
    companion object {
        const val MAX_APK_BYTES = 50L * 1024 * 1024

        /** github.com serves the download link; it redirects to one of the asset hosts. */
        private val DOWNLOAD_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
        )

        /** Parses a release, or returns null for drafts and pre-releases. Throws IllegalArgumentException when invalid. */
        fun parse(json: String): ReleaseInfo? {
            val obj = Json.parseObject(json)
            if (obj["draft"] == true || obj["prerelease"] == true) return null
            val tag = obj["tag_name"] as? String ?: throw IllegalArgumentException("missing tag_name")
            val version = Versions.parse(tag)?.joinToString(".") ?: throw IllegalArgumentException("bad tag_name")
            val htmlUrl = obj["html_url"] as? String ?: ""
            val apk = (obj["assets"] as? List<*>).orEmpty()
                .filterIsInstance<Map<*, *>>()
                .firstOrNull { (it["name"] as? String)?.endsWith(".apk", ignoreCase = true) == true }
                ?.let(::asset)
            return ReleaseInfo(tag, version, htmlUrl, apk)
        }

        fun isAllowedDownloadUrl(url: String): Boolean {
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                return false
            }
            return uri.scheme.equals("https", ignoreCase = true) &&
                uri.rawUserInfo == null &&
                uri.port == -1 &&
                uri.host?.lowercase() in DOWNLOAD_HOSTS
        }

        private fun asset(map: Map<*, *>): ReleaseAsset {
            val name = map["name"] as String
            val url = map["browser_download_url"] as? String ?: throw IllegalArgumentException("missing download url")
            require(isAllowedDownloadUrl(url)) { "untrusted download url" }
            val size = (map["size"] as? Double)?.toLong() ?: throw IllegalArgumentException("missing size")
            require(size in 1..MAX_APK_BYTES) { "bad size" }
            return ReleaseAsset(name, url, size)
        }
    }
}
