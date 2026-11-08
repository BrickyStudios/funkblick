package io.github.brickystudios.funkblick.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.brickystudios.funkblick.BuildConfig
import io.github.brickystudios.funkblick.scan.UpdateState
import io.github.brickystudios.funkblick.core.tr

@Composable
fun UpdateDialog(state: UpdateState, onInstall: () -> Unit, onLater: () -> Unit) {
    val p = state.progress
    val loading = p != null
    val mb = "%.0f\u00A0MB".format(state.release.bytes / 1_000_000.0)
    // the first update leads through Android's "install unknown apps" screen; say so before it shows up
    val firstTime = !LocalContext.current.packageManager.canRequestPackageInstalls()
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(tr("Funkblick ${state.release.version} ist da", "Funkblick ${state.release.version} is out")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    p != null -> {
                        Text(tr("Lädt … ${(p * 100).toInt()} %", "Downloading … ${(p * 100).toInt()} %"))
                        LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                    }
                    state.failed -> Text(tr("Download hat nicht geklappt. Prüf die Verbindung und versuch es nochmal.",
                        "Download failed. Check your connection and try again."))
                    else -> Text(tr("Du hast ${BuildConfig.VERSION_NAME}. Das Update lädt $mb von GitHub.",
                        "You have ${BuildConfig.VERSION_NAME}. The update downloads $mb from GitHub."))
                }
                if (firstTime && !loading) Text(tr("Beim ersten Mal fragt Android, ob Funkblick Apps installieren darf. Erlauben, dann zurück.",
                    "The first time, Android asks whether Funkblick may install apps. Allow it, then go back."))
            }
        },
        confirmButton = {
            TextButton(onInstall, enabled = !loading) { Text(if (state.failed) tr("Nochmal", "Retry") else tr("Installieren", "Install")) }
        },
        dismissButton = { if (!loading) TextButton(onLater) { Text(tr("Später", "Later")) } }
    )
}
