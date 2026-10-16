package app.sdot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseInfoTest {
    private fun release(
        tag: String = "v1.3.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = asset("sdot-1.3.0.apk", "https://github.com/owner/repo/releases/download/v1.3.0/sdot-1.3.0.apk"),
    ) = """
        {"tag_name":"$tag","html_url":"https://github.com/owner/repo/releases/tag/$tag",
         "draft":$draft,"prerelease":$prerelease,"name":"Sdot $tag","assets":[$assets]}
    """.trimIndent()

    private fun asset(name: String, url: String, size: Long = 4_200_000) =
        """{"name":"$name","size":$size,"content_type":"application/vnd.android.package-archive","browser_download_url":"$url"}"""

    @Test
    fun parsesTagPageAndApkAsset() {
        val info = ReleaseInfo.parse(release())!!
        assertEquals("v1.3.0", info.tag)
        assertEquals("1.3.0", info.version)
        assertEquals("https://github.com/owner/repo/releases/tag/v1.3.0", info.htmlUrl)
        val apk = info.apk!!
        assertEquals("sdot-1.3.0.apk", apk.name)
        assertEquals(4_200_000L, apk.size)
        assertEquals("https://github.com/owner/repo/releases/download/v1.3.0/sdot-1.3.0.apk", apk.url)
    }

    @Test
    fun picksTheFirstApkAmongOtherAssets() {
        val assets = listOf(
            asset("checksums.txt", "https://github.com/o/r/releases/download/v1.3.0/checksums.txt"),
            asset("Sdot.APK", "https://github.com/o/r/releases/download/v1.3.0/Sdot.APK", 10),
            asset("other.apk", "https://github.com/o/r/releases/download/v1.3.0/other.apk"),
        ).joinToString(",")
        assertEquals("Sdot.APK", ReleaseInfo.parse(release(assets = assets))!!.apk!!.name)
    }

    @Test
    fun releaseWithoutApkHasNoAsset() {
        val assets = asset("notes.txt", "https://github.com/o/r/releases/download/v1.3.0/notes.txt")
        assertNull(ReleaseInfo.parse(release(assets = assets))!!.apk)
        assertNull(ReleaseInfo.parse(release(assets = ""))!!.apk)
    }

    @Test
    fun ignoresDraftsAndPrereleases() {
        assertNull(ReleaseInfo.parse(release(draft = true)))
        assertNull(ReleaseInfo.parse(release(prerelease = true)))
    }

    @Test
    fun rejectsApkFromAnUntrustedHost() {
        for (url in listOf(
            "http://github.com/o/r/releases/download/v1/a.apk",
            "https://evil.example/a.apk",
            "https://github.com.evil.example/a.apk",
            "https://user@github.com/o/r/a.apk",
            "https://github.com:8443/o/r/a.apk",
        )) {
            assertThrows(url, IllegalArgumentException::class.java) {
                ReleaseInfo.parse(release(assets = asset("a.apk", url)))
            }
        }
    }

    @Test
    fun rejectsImplausibleSizes() {
        val url = "https://github.com/o/r/releases/download/v1/a.apk"
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseInfo.parse(release(assets = asset("a.apk", url, 0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReleaseInfo.parse(release(assets = asset("a.apk", url, ReleaseInfo.MAX_APK_BYTES + 1)))
        }
    }

    @Test
    fun rejectsBadTagsAndMissingFields() {
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse(release(tag = "nightly")) }
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse("""{"html_url":"https://github.com/o/r"}""") }
        assertThrows(IllegalArgumentException::class.java) { ReleaseInfo.parse("[]") }
    }

    @Test
    fun allowsOnlyGithubDownloadHostsOverHttps() {
        assertTrue(ReleaseInfo.isAllowedDownloadUrl("https://github.com/o/r/releases/download/v1/a.apk"))
        assertTrue(ReleaseInfo.isAllowedDownloadUrl("https://objects.githubusercontent.com/github-production-release-asset/1?x=y"))
        assertTrue(ReleaseInfo.isAllowedDownloadUrl("https://release-assets.githubusercontent.com/a?sig=1"))
        assertTrue(ReleaseInfo.isAllowedDownloadUrl("https://GitHub.com/o/r/a.apk"))
        assertFalse(ReleaseInfo.isAllowedDownloadUrl("http://objects.githubusercontent.com/a"))
        assertFalse(ReleaseInfo.isAllowedDownloadUrl("https://raw.githubusercontent.com/a"))
        assertFalse(ReleaseInfo.isAllowedDownloadUrl("https://api.github.com/repos/o/r"))
        assertFalse(ReleaseInfo.isAllowedDownloadUrl("https://evilgithub.com/a"))
        assertFalse(ReleaseInfo.isAllowedDownloadUrl("not a url"))
        assertFalse(ReleaseInfo.isAllowedDownloadUrl("/relative/a.apk"))
    }
}
