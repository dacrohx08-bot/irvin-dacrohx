package com.irvin.dacrohx.device

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.irvin.dacrohx.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizaciones dentro de la app: revisa GitHub Releases, descarga el APK nuevo
 * y abre el instalador. Se instala encima: no se pierde el cerebro ni los recuerdos.
 */
class Updater(private val ctx: Context) {

    companion object {
        const val REPO = "dacrohx08-bot/irvin-dacrohx"
    }

    data class Release(val code: Int, val name: String, val notes: String, val url: String, val size: Long)

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        data class Available(val r: Release) : State()
        data class Downloading(val r: Release, val pct: Int) : State()
        data class ReadyToInstall(val r: Release) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentCode: Int get() = BuildConfig.VERSION_CODE
    val currentName: String get() = BuildConfig.VERSION_NAME

    private val apk: File get() = File(ctx.getExternalFilesDir(null), "update.apk")
    private val dm = ctx.getSystemService(DownloadManager::class.java)

    suspend fun check(silent: Boolean = false) {
        if (_state.value is State.Downloading) return
        if (!silent) _state.value = State.Checking
        try {
            val r = withContext(Dispatchers.IO) { fetchLatest() }
            _state.value = when {
                r == null -> if (silent) State.Idle else State.Failed("No encontré versiones publicadas.")
                r.code > currentCode -> State.Available(r)
                else -> if (silent) State.Idle else State.UpToDate
            }
        } catch (e: Exception) {
            _state.value = if (silent) State.Idle else State.Failed(
                if (e.message?.contains("404") == true) "No puedo ver las versiones: el repositorio debe ser público."
                else "No pude revisar (¿hay internet?)."
            )
        }
    }

    private fun fetchLatest(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "IrvinDacrohx")
        try {
            if (c.responseCode >= 400) throw RuntimeException("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name") // v1.0.N
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return Release(
                        code, tag, j.optString("body").take(1200),
                        a.getString("browser_download_url"), a.optLong("size"),
                    )
                }
            }
            return null
        } finally { c.disconnect() }
    }

    suspend fun download(r: Release) {
        apk.delete()
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(r.url))
                .setTitle("Actualización de Irvin Dacrohx ${r.name}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, null, "update.apk")
        )
        _state.value = State.Downloading(r, 0)
        while (true) {
            val c = dm.query(DownloadManager.Query().setFilterById(id))
            if (c == null || !c.moveToFirst()) { c?.close(); _state.value = State.Failed("Se canceló la descarga."); return }
            val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).let { if (it > 0) it else r.size }
            c.close()
            when (st) {
                DownloadManager.STATUS_SUCCESSFUL -> { _state.value = State.ReadyToInstall(r); install(); return }
                DownloadManager.STATUS_FAILED -> { _state.value = State.Failed("Falló la descarga."); return }
                else -> _state.value = State.Downloading(r, if (total > 0) (done * 100 / total).toInt() else 0)
            }
            delay(500)
        }
    }

    /** Abre el instalador de Android. Si falta el permiso, lleva a activarlo. */
    fun install() {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        if (!apk.exists()) { _state.value = State.Failed("No encontré el archivo descargado."); return }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
