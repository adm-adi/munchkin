package com.munchkin.app.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security unit tests for [UpdateChecker] URL allowlisting, SHA-256 parsing,
 * and GitHub release payload decoding.
 */
class UpdateCheckerTest {

    @Test
    fun `isAllowedDownloadUrl accepts only HTTPS URLs on allowlisted GitHub hosts`() {
        assertTrue(
            UpdateChecker.isAllowedDownloadUrl(
                "https://github.com/adm-adi/munchkin/releases/download/v2.21.3/app-release.apk"
            )
        )
        assertTrue(
            UpdateChecker.isAllowedDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset-2e65be/app-release.apk"
            )
        )
        assertTrue(
            UpdateChecker.isAllowedDownloadUrl(
                "https://api.github.com/repos/adm-adi/munchkin/releases/latest"
            )
        )
        assertTrue(
            UpdateChecker.isAllowedDownloadUrl(
                "https://release-assets.githubusercontent.com/assets/12345/app.apk"
            )
        )
        assertTrue(
            UpdateChecker.isAllowedDownloadUrl(
                "  HTTPS://GITHUB.COM/adm-adi/munchkin/releases/latest  "
            )
        )
    }

    @Test
    fun `isAllowedDownloadUrl rejects HTTP untrusted hosts IP literals and spoofing URLs`() {
        assertFalse(UpdateChecker.isAllowedDownloadUrl("http://github.com/adm-adi/munchkin/releases/latest"))
        assertFalse(UpdateChecker.isAllowedDownloadUrl("http://objects.githubusercontent.com/app.apk"))
        assertFalse(UpdateChecker.isAllowedDownloadUrl("https://evil.example/app-release.apk"))
        assertFalse(UpdateChecker.isAllowedDownloadUrl("https://github.com.evil.example/app-release.apk"))
        assertFalse(UpdateChecker.isAllowedDownloadUrl("https://127.0.0.1/app-release.apk"))
        assertFalse(UpdateChecker.isAllowedDownloadUrl("https://[::1]/app-release.apk"))
        assertFalse(UpdateChecker.isAllowedDownloadUrl("https://github.com@evil.example/app-release.apk"))
        assertFalse(UpdateChecker.isAllowedDownloadUrl("https://evil.example/?redirect=https://github.com/app.apk"))
        assertFalse(UpdateChecker.isAllowedDownloadUrl("file:///data/local/tmp/app.apk"))
        assertFalse(UpdateChecker.isAllowedDownloadUrl(""))
        assertFalse(UpdateChecker.isAllowedDownloadUrl("not a valid url"))
    }

    @Test
    fun `parseSha256Text extracts 64-char hex digest from raw and checksum sidecar formats`() {
        val lowerDigest = "a".repeat(64)
        val upperDigest = "ABCDEF0123456789".repeat(4)

        assertEquals(lowerDigest, UpdateChecker.parseSha256Text(lowerDigest))
        assertEquals(upperDigest.lowercase(), UpdateChecker.parseSha256Text("  $upperDigest  \n"))
        assertEquals(
            lowerDigest,
            UpdateChecker.parseSha256Text("$lowerDigest  munchkin-2.21.3-release.apk\n")
        )
        assertEquals(
            lowerDigest,
            UpdateChecker.parseSha256Text("$lowerDigest *munchkin-2.21.3-release.apk")
        )
    }

    @Test
    fun `parseSha256Text rejects malformed short long or non-hex strings`() {
        assertNull(UpdateChecker.parseSha256Text(""))
        assertNull(UpdateChecker.parseSha256Text("   \n "))
        assertNull(UpdateChecker.parseSha256Text("a".repeat(63)))
        assertNull(UpdateChecker.parseSha256Text("a".repeat(65)))
        assertNull(UpdateChecker.parseSha256Text("g" + "a".repeat(63)))
        assertNull(UpdateChecker.parseSha256Text("sha256:${"a".repeat(64)}"))
        assertNull(UpdateChecker.parseSha256Text("filename.apk ${"a".repeat(64)}"))
    }

    @Test
    fun `GitHubRelease decodes latest release JSON ignoring unknown GitHub fields`() {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val payload = """
            {
              "tag_name": "v2.22.0",
              "name": "v2.22.0",
              "draft": false,
              "prerelease": false,
              "body": "Bug fixes",
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://github.com/adm-adi/munchkin/releases/download/v2.22.0/app-release.apk",
                  "size": 12345678
                },
                {
                  "name": "app-release.apk.sha256",
                  "browser_download_url": "https://github.com/adm-adi/munchkin/releases/download/v2.22.0/app-release.apk.sha256",
                  "size": 85
                }
              ]
            }
        """.trimIndent()

        val release = json.decodeFromString<GitHubRelease>(payload)
        assertEquals("v2.22.0", release.tagName)
        assertEquals("Bug fixes", release.body)
        assertEquals(2, release.assets.size)
        assertTrue(UpdateChecker.isAllowedDownloadUrl(release.assets[0].browserDownloadUrl))
        assertTrue(UpdateChecker.isAllowedDownloadUrl(release.assets[1].browserDownloadUrl))
    }
}
