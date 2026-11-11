package com.irvin.dacrohx.device

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.irvin.dacrohx.MainActivity
import com.irvin.dacrohx.R
import com.irvin.dacrohx.ai.Assistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.text.Normalizer
import java.util.zip.ZipInputStream

/** Modelo de voz pequeño en español (Vosk, ~40 MB) para detectar "Oye Irvin" sin internet. */
class WakeModel(private val ctx: Context) {
    companion object {
        const val URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        private const val PREF = "wake_dl_id"
    }

    sealed class State {
        object Missing : State()
        data class Downloading(val pct: Int) : State()
        object Unpacking : State()
        object Ready : State()
        data class Failed(val reason: String) : State()
    }

    private val prefs = ctx.getSharedPreferences("irvin_model", Context.MODE_PRIVATE)
    private val dm = ctx.getSystemService(DownloadManager::class.java)
    val dir: File get() = File(ctx.filesDir, "vosk-es")
    private val zip: File get() = File(ctx.getExternalFilesDir(null), "vosk-es.zip")

    private val _state = MutableStateFlow<State>(if (isReady()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state.asStateFlow()

    fun isReady() = File(dir, "am").exists() || File(dir, "conf").exists()

    fun start() {
        if (isReady()) { _state.value = State.Ready; return }
        zip.delete()
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(URL))
                .setTitle("Oído de Irvin (Oye Irvin)")
                .setDescription("Modelo de voz en español · 40 MB")
                .setDestinationInExternalFilesDir(ctx, null, "vosk-es.zip")
        )
        prefs.edit().putLong(PREF, id).apply()
        _state.value = State.Downloading(0)
    }

    suspend fun watch() {
        while (true) {
            val id = prefs.getLong(PREF, -1L)
            if (id == -1L) return
            val c = dm.query(DownloadManager.Query().setFilterById(id))
            if (c == null || !c.moveToFirst()) {
                c?.close(); prefs.edit().remove(PREF).apply()
                _state.value = State.Failed("Descarga cancelada."); return
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            c.close()
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove(PREF).apply()
                    _state.value = State.Unpacking
                    _state.value = if (unpack()) State.Ready else State.Failed("No pude descomprimir el modelo de voz.")
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove(PREF).apply()
                    _state.value = State.Failed("Falló la descarga del modelo de voz.")
                    return
                }
                else -> _state.value = State.Downloading(if (total > 0) (done * 100 / total).toInt() else 0)
            }
            delay(600)
        }
    }

    /** Descomprime quitando la carpeta raíz del zip. */
    private suspend fun unpack(): Boolean = withContext(Dispatchers.IO) {
        try {
            val tmp = File(ctx.filesDir, "vosk-es.tmp")
            tmp.deleteRecursively(); tmp.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty() || rel.contains("..")) continue
                    val out = File(tmp, rel)
                    if (e.isDirectory) out.mkdirs()
                    else { out.parentFile?.mkdirs(); out.outputStream().use { zis.copyTo(it) } }
                }
            }
            dir.deleteRecursively()
            tmp.renameTo(dir)
            zip.delete()
            isReady()
        } catch (e: Exception) {
            Log.w("Irvin", "unpack", e); false
        }
    }
}

/**
 * Servicio en primer plano que escucha "Oye Irvin" todo el tiempo, sin internet.
 * Al detectarlo, Irvin escucha tu orden, la piensa con Gemma y te responde en voz alta.
 */
class WakeWordService : Service() {

    companion object {
        const val CHANNEL = "escucha_v1"
        const val ACTION_STOP = "com.irvin.dacrohx.WAKE_STOP"
        private const val NOTIF_ID = 4242

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WakeWordService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }

        private val CALL = setOf("oye", "hey", "ey", "oie", "olle", "oi", "hoy", "ok")
        private val NAME = Regex("^(i|e|y|hi|he|hey)r?(v|b)i(e)?n(g|o)?")
        private val NAME_STRICT = Regex("^(i|e|hi|he)r(v|b)i(e)?n")

        /** Reconoce "oye irvin" aunque el modelo lo escriba como "oye ir bien", "hey ervin", "oye y vino"... */
        fun isWake(t: String): Boolean {
            val w = t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            for (i in w.indices) {
                if (w[i] in CALL && i + 1 < w.size) {
                    val next = (w[i + 1] + (w.getOrNull(i + 2) ?: "")).replace("h", "")
                    if (NAME.containsMatchIn(next)) return true
                }
                if (i <= 1 && w.size <= 3 && NAME_STRICT.containsMatchIn(w[i] + (w.getOrNull(i + 1) ?: ""))) return true
            }
            return false
        }

        fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Assistant.get(this).store.updateSettings { it.copy(wakeWord = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification("Di “Oye Irvin” y te escucho."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            Log.w("Irvin", "No pude iniciar el servicio de escucha", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) { VoiceLog.add("Escucha de “Oye Irvin” iniciada"); loop = scope.launch { listenLoop() } }
        _running.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        loop?.cancel()
        scope.cancel()
        try { wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Oye Irvin (escucha activa)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fijo mientras Irvin escucha su nombre"
                setShowBadge(false)
            }
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 11, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Irvin Dacrohx")
            .setContentText(text)
            .setColor(0xFFFF2340.toInt())
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Desactivar", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
        } catch (_: Exception) {}
    }

    private fun beep() {
        try { Assistant.get(this).playWake() } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private suspend fun listenLoop() {
        val assistant = Assistant.get(this)
        val wm = WakeModel(this)
        if (!wm.isReady()) {
            updateNotification("Falta el modelo de voz. Ábreme y descárgalo en Ajustes.")
            return
        }
        val model = try {
            Model(wm.dir.absolutePath)
        } catch (e: Exception) {
            updateNotification("No pude cargar el modelo de voz.")
            return
        }
        // Precarga el cerebro para responder al instante cuando lo llamen.
        withContext(Dispatchers.Main) { scope.launch(Dispatchers.Main) { assistant.ensureBrain() } }
        val rate = 16000
        try {
            while (scope.isActive) {
                // Si la app está usando el micrófono, espera.
                while ((assistant.micHeld || assistant.mode.value != com.irvin.dacrohx.ai.Mode.IDLE) && scope.isActive) delay(300)

                val rec = Recognizer(model, rate.toFloat())
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val ar = try {
                    AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
                } catch (e: Exception) { rec.close(); delay(2000); continue }
                if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); rec.close(); delay(2000); continue }

                ar.startRecording()
                val buf = ByteArray(4096)
                var woke = false
                val fast = assistant.store.settings.value.fastVoice
                while (scope.isActive && !assistant.micHeld && assistant.mode.value == com.irvin.dacrohx.ai.Mode.IDLE) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val final = rec.acceptWaveForm(buf, n)
                    val text = if (final) JSONObject(rec.result).optString("text")
                    else JSONObject(rec.partialResult).optString("partial")
                    if (text.isNotEmpty()) {
                        if (isWake(norm(text))) { woke = true; break }
                        if (final) rec.reset()
                    }
                }

                try { ar.stop() } catch (_: Exception) {}
                ar.release()
                rec.close()

                if (woke) {
                    Log.i("Irvin", "¡Oye Irvin detectado!")
                    VoiceLog.add("“Oye Irvin” detectado")
                    // El oído pequeño solo detecta el nombre; la orden la escucha un reconocedor preciso.
                    acquireWake()
                    updateNotification("Te escucho…")
                    withContext(Dispatchers.Main) {
                        try {
                            if (fast) assistant.quickVoiceTurn() else { beep(); delay(300); assistant.handsFreeTurn() }
                        } catch (e: Exception) { Log.w("Irvin", "turno", e) }
                    }
                    updateNotification("Di “Oye Irvin” y te escucho.")
                    releaseWake()
                }
            }
        } finally {
            model.close()
        }
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "irvin:turno").apply { acquire(90_000) }
        } catch (_: Exception) {}
    }

    private fun releaseWake() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }
}
