package io.github.brickystudios.funkblick.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    @Test
    fun comparesVersionsAsNumbers() {
        assertTrue(isNewer("v1.10.0", "1.9.2"))
        assertTrue(isNewer("v2.0", "1.9.9"))
        assertTrue(isNewer("v1.9.2.1", "1.9.2"))
        assertFalse(isNewer("v1.9.2", "1.9.2"))
        assertFalse(isNewer("v1.9.1", "1.9.2"))
        assertFalse(isNewer("v1.9", "1.9.0"))
    }

    private fun json(url: String, name: String = "Funkblick.apk") =
        """{"tag_name":"v1.10.0","assets":[{"name":"notes.txt","browser_download_url":"x"},
           {"name":"$name","browser_download_url":"$url","size":18000000}]}"""

    @Test
    fun readsTheApkFromGithubsAnswer() {
        val url = "https://github.com/BrickyStudios/funkblick/releases/download/v1.10.0/Funkblick.apk"
        assertEquals(Release("1.10.0", url, 18_000_000), parseRelease(json(url)))
    }

    @Test
    fun ignoresForeignDownloadsAndBrokenAnswers() {
        assertNull(parseRelease(json("https://evil.example/Funkblick.apk")))
        assertNull(parseRelease(json("https://github.com/BrickyStudios/funkblick/releases/download/v1.10.0/x.apk", "x.apk")))
        assertNull(parseRelease("""{"message":"API rate limit exceeded"}"""))
        assertNull(parseRelease("<html>"))
    }
}
