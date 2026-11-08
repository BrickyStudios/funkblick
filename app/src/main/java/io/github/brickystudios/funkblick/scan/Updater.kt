package io.github.brickystudios.funkblick.scan

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.brickystudios.funkblick.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Funkblick updates itself from GitHub Releases: ask for the latest tag, download Funkblick.apk, hand it to Android's installer.
private const val LATEST = "https://api.github.com/repos/BrickyStudios/funkblick/releases/latest"
private const val DOWNLOADS = "https://github.com/BrickyStudios/funkblick/releases/download/"

data class Release(val version: String, val apkUrl: String, val bytes: Long)

/** "v1.10.0" is newer than "1.9.2". */
fun isNewer(tag: String, current: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    val a = parts(tag)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}

class GhAsset(val name: String?, val browser_download_url: String?, val size: Long?)
class GhRelease(val tag_name: String?, val assets: List<GhAsset>?)

/** Reads GitHub's answer. Only an APK from this repo's own release downloads counts. */
fun parseRelease(json: String): Release? {
    val r = runCatching { Gson().fromJson(json, GhRelease::class.java) }.getOrNull() ?: return null
    val tag = r.tag_name ?: return null
    val apk = r.assets?.firstOrNull { it.name == "Funkblick.apk" } ?: return null
    val url = apk.browser_download_url?.takeIf { it.startsWith(DOWNLOADS) } ?: return null
    return Release(tag.removePrefix("v"), url, apk.size ?: 0)
}

private fun open(url: String, readTimeout: Int) = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 10_000
    this.readTimeout = readTimeout
}

/** null without network, on errors, or when GitHub has nothing newer. */
suspend fun fetchUpdate(current: String): Release? = withContext(Dispatchers.IO) {
    runCatching {
        val c = open(LATEST, 10_000).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        c.inputStream.bufferedReader().use { parseRelease(it.readText()) }
    }.getOrNull()?.takeIf { isNewer(it.version, current) }
}

/** Downloads into a .part file first, so a broken download never looks like a finished APK. */
suspend fun download(url: String, target: File, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
    val c = open(url, 30_000)
    val total = c.contentLengthLong
    val part = File(target.path + ".part")
    target.parentFile?.mkdirs()
    var done = 0L
    c.inputStream.use { input ->
        part.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                if (total > 0) progress(done.toFloat() / total)
            }
        }
    }
    if (total > 0 && done != total) throw IOException("download cut off at $done of $total bytes")
    if (!part.renameTo(target)) throw IOException("could not move ${part.name}")
}

/** Android checks the signature: only an APK signed with the Funkblick release key replaces the app. */
fun installApk(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** progress: null = waiting for a tap, 0..1 = downloading. */
data class UpdateState(val release: Release, val progress: Float? = null, val failed: Boolean = false)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<UpdateState?>(null)
    val state: StateFlow<UpdateState?> = _state.asStateFlow()
    private val apk = File(application.cacheDir, "updates/Funkblick.apk")

    init {
        // debug builds carry the debug key, Android would refuse to put a release over them
        if (!BuildConfig.DEBUG) viewModelScope.launch {
            withContext(Dispatchers.IO) { apk.parentFile?.deleteRecursively() } // the APK from the last update
            _state.value = fetchUpdate(BuildConfig.VERSION_NAME)?.let(::UpdateState)
        }
    }

    fun later() { if (_state.value?.progress == null) _state.value = null }

    fun install() {
        val s = _state.value?.takeIf { it.progress == null } ?: return
        viewModelScope.launch {
            _state.value = s.copy(progress = 0f, failed = false)
            // if the installer was cancelled, the APK is still here: no second download
            val ok = apk.exists() || runCatching {
                download(s.release.apkUrl, apk) { p -> _state.update { it?.copy(progress = p) } }
            }.isSuccess
            _state.value = s.copy(progress = null, failed = !ok)
            if (ok) installApk(getApplication(), apk)
        }
    }
}
