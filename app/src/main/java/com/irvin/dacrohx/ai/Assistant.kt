package com.irvin.dacrohx.ai

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.irvin.dacrohx.R
import com.google.ai.edge.litertlm.Content
import com.irvin.dacrohx.data.ChatMsg
import com.irvin.dacrohx.data.Store
import com.irvin.dacrohx.device.DeviceActions
import com.irvin.dacrohx.device.IrBlaster
import com.irvin.dacrohx.device.ReminderScheduler
import com.irvin.dacrohx.device.Speaker
import com.irvin.dacrohx.device.VoiceRecorder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * El núcleo de Irvin. Vive una sola vez por proceso y lo comparten la pantalla
 * y el servicio de "Oye Irvin", así el cerebro no se carga dos veces.
 */
class Assistant private constructor(private val app: Application) {

    companion object {
        @Volatile private var instance: Assistant? = null
        fun get(ctx: Context): Assistant = instance ?: synchronized(this) {
            instance ?: Assistant(ctx.applicationContext as Application).also { instance = it }
        }
        const val CH_REPLY = "respuestas_v1"
    }

    val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private fun <T : Application> getApplication(): T {
        @Suppress("UNCHECKED_CAST")
        return app as T
    }

    /** true mientras la app o la conversación por voz usan el micrófono. */
    @Volatile var micBusy = false
        private set

    val store = Store.get(app)
    val models = ModelManager(app)
    val brain = Brain(app)
    val device = DeviceActions(app)
    val ir = IrBlaster(app)
    val speaker = Speaker(app)
    val recorder = VoiceRecorder()
    val wake = com.irvin.dacrohx.device.WakeModel(app)

    private val _mode = MutableStateFlow(Mode.IDLE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _pendingImage = MutableStateFlow<Bitmap?>(null)
    val pendingImage: StateFlow<Bitmap?> = _pendingImage.asStateFlow()

    private val _partialSpeech = MutableStateFlow("")
    val partialSpeech: StateFlow<String> = _partialSpeech.asStateFlow()

    val micLevel get() = recorder.level

    @Volatile private var conversationReady = false
    private var genJob: Job? = null
    private var currentReplyId: String? = null
    private var recognizer: SpeechRecognizer? = null

    init {
        recorder.onAutoStop = { viewModelScope.launch { stopListeningAndSend() } }
        viewModelScope.launch {
            speaker.speaking.collect { s ->
                if (s && _mode.value == Mode.IDLE) _mode.value = Mode.SPEAKING
                if (!s && _mode.value == Mode.SPEAKING) _mode.value = Mode.IDLE
            }
        }
        viewModelScope.launch { models.watchDownload() }
        viewModelScope.launch { wake.watch() }
        viewModelScope.launch {
            models.state.collect { if (it is ModelState.Ready && brain.state.value is BrainState.Off) bootBrain() }
        }
        ReminderScheduler.rescheduleAll(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(CH_REPLY, "Respuestas de Irvin", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun toast(msg: String) { _toast.value = msg }
    fun clearToast() { _toast.value = null }

    // ------------------------------------------------------------------ cerebro
    private fun bootBrain() = viewModelScope.launch {
        brain.load(models.modelFile.absolutePath)
        if (brain.ready) resetConversation(keepHistory = true)
    }

    fun retryBrain() = viewModelScope.launch {
        brain.close()
        bootBrain()
    }

    private fun tools() = IrvinTools(getApplication(), store, device, ir) { label ->
        currentReplyId?.let { id -> store.updateChat(id) { it.copy(actions = it.actions + label) } }
    }

    fun systemPrompt(): String {
        val s = store.settings.value
        val now = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale("es", "MX")).format(Date())
        val mem = store.memories.value.takeLast(80).joinToString("\n") { "- ${it.text}" }.ifEmpty { "(todavía nada)" }
        val rem = store.reminders.value.filter { !it.done && !it.fired }.sortedBy { it.due }.take(15)
            .joinToString("\n") { "- ${it.text} → " + SimpleDateFormat("EEE d MMM HH:mm", Locale("es", "MX")).format(Date(it.due)) }
            .ifEmpty { "(ninguno)" }
        val user = s.name.ifBlank { "tu usuario" }
        return """
Eres IRVIN DACROHX, el asistente personal de $user. Vives dentro de su teléfono gamer RedMagic 11 Pro y funcionas 100% sin internet: tu cerebro corre en el propio teléfono. Eres SU asistente, hecho solo para él.

PERSONALIDAD: ${s.persona}

REGLAS:
- Responde SIEMPRE en español de México, natural y breve (1 a 4 frases) salvo que pida detalle. Tus respuestas se leen en un celular y se escuchan en voz alta: nada de tablas ni encabezados.
- Si recibes audio, es el usuario hablándote: responde a lo que dijo (no describas el audio).
- Si recibes una imagen, analízala y responde a lo que pregunte sobre ella.
- Usa tus herramientas cuando sirvan. Nunca digas que guardaste, programaste o hiciste algo sin haber usado la herramienta correspondiente.
- Cuando el usuario comparta algo personal que valga la pena recordar, usa guardar_recuerdo sin preguntar.
- Para "recuérdame…" usa recordatorio_en_minutos o recordatorio_en_fecha. Si no dice cuándo, pregunta.
- Para cualquier cuenta usa calcular.
- No tienes internet: si te piden noticias, clima en vivo o buscar en la web, dilo con honestidad y ofrece lo que sí puedes hacer.

FECHA Y HORA AL INICIAR: $now (cada mensaje del usuario trae la hora actual entre corchetes).

LO QUE SABES DEL USUARIO:
$mem

RECORDATORIOS PENDIENTES:
$rem
""".trim()
    }

    fun resetConversation(keepHistory: Boolean) = viewModelScope.launch {
        if (!brain.ready) return@launch
        val history = if (!keepHistory) emptyList() else store.chat.value
            .filter { !it.pending && !it.error && (it.role == "user" || it.role == "model") && it.text.isNotBlank() }
            .takeLast(12)
            .map { it.role to it.text }
            .dropWhile { it.first != "user" }
        try {
            brain.newConversation(systemPrompt(), history, tools())
            conversationReady = true
        } catch (e: Throwable) {
            toast("No pude iniciar la conversación: ${e.message}")
        }
    }

    fun newChat() {
        stopAll()
        store.clearChat()
        resetConversation(keepHistory = false)
    }

    // ------------------------------------------------------------------ enviar
    private fun stamp(): String =
        "[" + SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale("es", "MX")).format(Date()) + "]"

    fun sendText(text: String) {
        val t = text.trim()
        val img = _pendingImage.value
        if (t.isEmpty() && img == null) return
        var imgPath: String? = null
        val parts = mutableListOf<Content>()
        if (img != null) {
            if (!brain.visionEnabled) { toast("La visión no está disponible en este modo."); return }
            val bytes = ByteArrayOutputStream().also { img.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            parts += Content.ImageBytes(bytes)
            val f = File(store.imagesDir, "${System.currentTimeMillis()}.jpg")
            try { f.outputStream().use { img.compress(Bitmap.CompressFormat.JPEG, 85, it) }; imgPath = f.absolutePath } catch (_: Exception) {}
            _pendingImage.value = null
        }
        parts += Content.Text("${stamp()} " + t.ifEmpty { "¿Qué ves en esta imagen?" })
        val userMsg = ChatMsg(role = "user", text = t.ifEmpty { "¿Qué ves en esta imagen?" }, kind = if (img != null) "image" else "text", image = imgPath)
        run(userMsg, parts)
    }

    private fun sendVoice(wav: ByteArray, forceSpeak: Boolean = false): Job? {
        if (!brain.audioEnabled) { toast("El audio no está disponible en este modo."); return null }
        val secs = (wav.size - 44) / (VoiceRecorder.SAMPLE_RATE * 2)
        val parts = listOf(
            Content.AudioBytes(wav),
            Content.Text("${stamp()} (Mensaje de voz del usuario. Responde a lo que dijo.)"),
        )
        return run(ChatMsg(role = "user", text = "🎙️ Mensaje de voz · ${secs.coerceAtLeast(1)} s", kind = "voice"), parts, forceSpeak)
    }

    private fun run(userMsg: ChatMsg, parts: List<Content>, forceSpeak: Boolean = false): Job? {
        if (!brain.ready) { toast("El cerebro todavía se está cargando…"); return null }
        if (genJob?.isActive == true) return null
        speaker.stop()
        store.addChat(userMsg)
        val reply = ChatMsg(role = "model", text = "", pending = true)
        store.addChat(reply)
        currentReplyId = reply.id
        _mode.value = Mode.THINKING
        val autoSpeak = forceSpeak || store.settings.value.autoSpeak
        speaker.rate = store.settings.value.speechRate
        genJob = viewModelScope.launch {
            val sb = StringBuilder()
            var failed: String? = null
            try {
                brain.send(parts)
                    .catch { e -> failed = e.message ?: e.toString() }
                    .collect { delta ->
                        if (delta.isNotEmpty()) {
                            sb.append(delta)
                            val shown = sb.toString()
                            store.updateChat(reply.id) { it.copy(text = shown) }
                            if (autoSpeak) speaker.feed(delta)
                        }
                    }
            } catch (e: Throwable) {
                failed = e.message ?: e.toString()
            }
            val finalText = sb.toString().trim()
            store.updateChat(reply.id, persist = true) {
                when {
                    failed != null && finalText.isEmpty() -> it.copy(text = "Algo falló en mis circuitos: $failed", pending = false, error = true)
                    finalText.isEmpty() -> it.copy(text = "Listo.", pending = false)
                    else -> it.copy(text = finalText, pending = false)
                }
            }
            if (autoSpeak && failed == null) speaker.finish()
            lastReplyText = if (failed == null) finalText else null
            currentReplyId = null
            _mode.value = if (speaker.speaking.value) Mode.SPEAKING else Mode.IDLE
        }
        return genJob
    }

    fun stopAll() {
        brain.stop()
        genJob?.cancel()
        speaker.stop()
        if (recorder.isRecording) recorder.cancel()
        micBusy = false
        _mode.value = Mode.IDLE
    }

    fun speak(text: String) {
        speaker.rate = store.settings.value.speechRate
        speaker.say(text, flush = true)
    }

    // ------------------------------------------------------------------ voz
    fun toggleListening() {
        if (_mode.value == Mode.LISTENING) { stopListeningAndSend(); return }
        if (_mode.value == Mode.THINKING) return
        speaker.stop()
        if (brain.audioEnabled) {
            micBusy = true
            if (recorder.start()) _mode.value = Mode.LISTENING
            else toast("No pude usar el micrófono.")
        } else {
            startRecognizer()
        }
    }

    fun stopListeningAndSend() {
        if (recognizer != null) { recognizer?.stopListening(); return }
        if (!recorder.isRecording && _mode.value != Mode.LISTENING) return
        val wav = recorder.stop()
        micBusy = false
        _mode.value = Mode.IDLE
        if (wav == null) { toast("No te escuché, mantén el micrófono un poco más."); return }
        sendVoice(wav)
    }

    /** Respaldo: reconocimiento de voz de Android en modo offline. */
    private fun startRecognizer() {
        val ctx = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { toast("No hay reconocimiento de voz disponible."); return }
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = sr
        _partialSpeech.value = ""
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _mode.value = Mode.LISTENING }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { finishRecognizer(null) }
            override fun onResults(results: Bundle?) {
                finishRecognizer(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
            override fun onPartialResults(partialResults: Bundle?) {
                _partialSpeech.value = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun finishRecognizer(text: String?) {
        recognizer?.destroy()
        recognizer = null
        _partialSpeech.value = ""
        _mode.value = Mode.IDLE
        if (!text.isNullOrBlank()) sendText(text)
    }

    // ------------------------------------------------------------------ imagen
    fun attachImage(bmp: Bitmap?) {
        if (bmp == null) return
        val max = 1024
        val scale = minOf(1f, max.toFloat() / maxOf(bmp.width, bmp.height))
        _pendingImage.value = if (scale < 1f)
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        else bmp
    }

    fun clearImage() { _pendingImage.value = null }

    // ------------------------------------------------------------------ memoria y avisos
    fun addMemory(text: String) { if (text.isNotBlank()) { store.addMemory(text); resetConversation(true) } }
    fun deleteMemory(id: String) { store.deleteMemory(id); resetConversation(true) }

    fun addReminder(text: String, due: Long) {
        if (text.isBlank()) { toast("Escribe qué te recuerdo."); return }
        if (due <= System.currentTimeMillis()) { toast("Esa hora ya pasó."); return }
        val r = store.addReminder(text, due)
        ReminderScheduler.schedule(getApplication(), r.id, due)
        toast("Recordatorio creado.")
    }

    fun deleteReminder(id: String) {
        ReminderScheduler.cancel(getApplication(), id)
        store.deleteReminder(id)
    }

    fun markReminderDone(id: String) { store.updateReminder(id) { it.copy(done = true) } }

    // ------------------------------------------------------------------ control
    fun sendIr(action: IrBlaster.Action) {
        val err = ir.send(store.settings.value.tvBrand, action)
        if (err != null) toast(err)
    }

    fun deleteModel() = viewModelScope.launch {
        stopAll()
        brain.close()
        models.deleteModel()
    }

    // ------------------------------------------------------------------ manos libres ("Oye Irvin")
    fun setWakeWord(on: Boolean) {
        if (on && !wake.isReady()) {
            wake.start()
            viewModelScope.launch {
                wake.watch()
                if (wake.isReady()) setWakeWord(true)
            }
            toast("Descargando el oído de Irvin (40 MB)…")
            return
        }
        store.updateSettings { it.copy(wakeWord = on) }
        if (on) {
            try { com.irvin.dacrohx.device.WakeWordService.start(app); toast("Listo: di “Oye Irvin”.") }
            catch (e: Exception) { toast("No pude activar la escucha: ${e.message}") }
        } else com.irvin.dacrohx.device.WakeWordService.stop(app)
    }

    /** Espera a que el cerebro esté listo (lo carga si hace falta). */
    suspend fun ensureBrain(): Boolean {
        if (brain.ready) return true
        if (!models.isReady()) return false
        if (brain.state.value is BrainState.Off || brain.state.value is BrainState.Error) bootBrain()
        val s = brain.state.first { it is BrainState.Ready || it is BrainState.Error }
        if (s is BrainState.Ready) {
            // espera a que exista la conversación
            var tries = 0
            while (!conversationReady && tries++ < 50) kotlinx.coroutines.delay(100)
        }
        return brain.ready
    }

    /**
     * Turno completo manos libres: escucha el comando (se detiene sola con el silencio),
     * lo manda al cerebro y responde en voz alta. Devuelve cuando terminó de hablar.
     */
    suspend fun handsFreeTurn() {
        if (!ensureBrain()) {
            speaker.say("Todavía no tengo cerebro instalado. Abre la app primero.", flush = true)
            return
        }
        if (genJob?.isActive == true || _mode.value == Mode.LISTENING) return
        speaker.stop()
        micBusy = true
        val done = CompletableDeferred<ByteArray?>()
        recorder.onAutoStop = { done.complete(recorder.stop()) }
        if (!recorder.start(stopOnSilence = true)) {
            micBusy = false
            recorder.onAutoStop = { viewModelScope.launch { stopListeningAndSend() } }
            return
        }
        _mode.value = Mode.LISTENING
        val wav = done.await()
        recorder.onAutoStop = { viewModelScope.launch { stopListeningAndSend() } }
        _mode.value = Mode.IDLE
        if (wav == null) { micBusy = false; return }
        val reply = sendVoice(wav, forceSpeak = true)
        reply?.join()
        // espera a que termine de hablar
        kotlinx.coroutines.delay(300)
        speaker.speaking.first { !it }
        micBusy = false
        lastReplyText?.let { notifyReply(it) }
    }

    @Volatile private var lastReplyText: String? = null

    private fun notifyReply(text: String) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java)
            val open = android.app.PendingIntent.getActivity(
                app, 3, Intent(app, com.irvin.dacrohx.MainActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(
                7001,
                NotificationCompat.Builder(app, CH_REPLY)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setContentTitle("Irvin Dacrohx")
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setColor(0xFFFF2340.toInt())
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
            )
        } catch (_: Exception) {}
    }
}
