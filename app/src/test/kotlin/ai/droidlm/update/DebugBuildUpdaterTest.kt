package ai.droidlm.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DebugBuildUpdaterTest {
    @Test fun parseDebugTagParsesVersionAndIteration() {
        val parsed = parseDebugTag("v0.1.27-debug.12")
        assertNotNull(parsed)
        assertEquals(0, parsed?.major)
        assertEquals(1, parsed?.minor)
        assertEquals(27, parsed?.patch)
        assertEquals(12, parsed?.iteration)
    }

    @Test fun parseDebugTagParsesCompactVersionAndIteration() {
        val parsed = parseDebugTag("v0.2.27-3")
        assertNotNull(parsed)
        assertEquals(0, parsed?.major)
        assertEquals(2, parsed?.minor)
        assertEquals(27, parsed?.patch)
        assertEquals(3, parsed?.iteration)
        assertEquals("0.2.27-3", parsed?.compactName)
    }

    @Test fun compactDebugVersionNameDisplaysIterationWithoutChannelSuffix() {
        assertEquals("0.2.27-3", compactDebugVersionName("0.2.27-debug.3"))
        assertEquals("0.2.27-3", compactDebugVersionName("0.2.27-3"))
    }

    @Test fun latestDebugReleaseFromJsonPrefersNewestDebugPrerelease() {
        val json = """
            [
              {
                "tag_name": "v0.1.27-debug.2",
                "prerelease": true,
                "published_at": "2026-05-11T00:00:00Z",
                "assets": [
                  {"name": "DroidLM-0.1.27-debug.2-debug.apk", "browser_download_url": "https://example.com/v0.1.27-debug.2.apk"}
                ]
              },
              {
                "tag_name": "v0.1.27-debug.10",
                "prerelease": true,
                "published_at": "2026-05-12T00:00:00Z",
                "assets": [
                  {"name": "DroidLM-0.1.27-debug.10-debug.apk", "browser_download_url": "https://example.com/v0.1.27-debug.10.apk"}
                ]
              },
              {
                "tag_name": "v0.1.27-11",
                "prerelease": true,
                "published_at": "2026-05-13T00:00:00Z",
                "assets": [
                  {"name": "DroidLM-0.1.27-11-debug.apk", "browser_download_url": "https://example.com/v0.1.27-11.apk"}
                ]
              },
              {
                "tag_name": "v0.1.27",
                "prerelease": false,
                "published_at": "2026-05-12T00:00:00Z",
                "assets": [
                  {"name": "DroidLM-0.1.27-release.apk", "browser_download_url": "https://example.com/v0.1.27-release.apk"}
                ]
              }
            ]
        """.trimIndent()

        val release = latestDebugReleaseFromJson(json)
        assertNotNull(release)
        assertEquals("v0.1.27-11", release?.tagName)
        assertEquals("DroidLM-0.1.27-11-debug.apk", release?.assetName)
        assertEquals("https://example.com/v0.1.27-11.apk", release?.assetUrl)
    }

    @Test fun latestDebugReleaseFromJsonFallsBackToAnyApkOnDebugPrerelease() {
        val json = """
            [
              {
                "tag_name": "v0.1.27-debug.1",
                "prerelease": true,
                "assets": [
                  {"name": "notes.txt", "browser_download_url": "https://example.com/notes.txt"},
                  {"name": "custom-debug-build.apk", "browser_download_url": "https://example.com/custom-debug-build.apk"}
                ]
              }
            ]
        """.trimIndent()

        val release = latestDebugReleaseFromJson(json)
        assertNotNull(release)
        assertEquals("custom-debug-build.apk", release?.assetName)
    }

    @Test fun expectedDebugAssetNameMatchesReleaseNamingConvention() {
        assertEquals(
            "DroidLM-0.1.27-debug.1-debug.apk",
            expectedDebugAssetName("v0.1.27-debug.1")
        )
        assertEquals(
            "DroidLM-0.2.27-3-debug.apk",
            expectedDebugAssetName("v0.2.27-3")
        )
    }
}
