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
    val ctx: Context get() = app
    private val intents = Intents(this)
    private fun <T : Application> getApplication(): T {
        @Suppress("UNCHECKED_CAST")
        return app as T
    }

    /** Mientras dure una conversación por voz, "Oye Irvin" no debe tomar el micrófono (ni en los huecos). */
    @Volatile var holdMicUntil = 0L
    val micHeld: Boolean get() = micBusy || System.currentTimeMillis() < holdMicUntil
    private fun holdMic(ms: Long = 20_000) { holdMicUntil = System.currentTimeMillis() + ms }
    private fun releaseMic() { holdMicUntil = 0L }

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
    val music = com.irvin.dacrohx.device.MusicPlayer(app)
    val turbo = ModelManager(app, ModelManager.E2B)
    val online = com.irvin.dacrohx.device.Online(app)
    val updater = com.irvin.dacrohx.device.Updater(app)
    val phone = com.irvin.dacrohx.device.Phone(app)
    val sounds = com.irvin.dacrohx.device.Sounds(app)
    fun playWake() = sounds.wake(store.settings.value.wakeSound)
    private fun playEnd() = sounds.end(store.settings.value.wakeSound)
    val cloud = com.irvin.dacrohx.device.Cloud(store)

    // ---------------- acciones que requieren confirmación (enviar mensajes)
    data class PendingAction(val description: String, val run: () -> String)
    private val _pending = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = _pending.asStateFlow()
    fun stage(description: String, run: () -> String) { _pending.value = PendingAction(description, run) }
    fun confirmPending(): String {
        val p = _pending.value ?: return "No hay ninguna acción pendiente."
        _pending.value = null
        return try { p.run() } catch (e: Exception) { "Falló: ${e.message}" }
    }
    fun cancelPending() { _pending.value = null }
    fun confirmFromUi() { val r = confirmPending(); toast(r); store.addChat(ChatMsg(role = "sys", text = "✅ $r")) }

    // ---------------- elegir app de música
    data class MusicChoice(val query: String, val apps: List<com.irvin.dacrohx.device.MusicPlayer.App>, val includeIrvin: Boolean = true)
    private val _musicChoice = MutableStateFlow<MusicChoice?>(null)
    val musicChoice: StateFlow<MusicChoice?> = _musicChoice.asStateFlow()
    fun askMusicApp(query: String, apps: List<com.irvin.dacrohx.device.MusicPlayer.App>, includeIrvin: Boolean = true) {
        _musicChoice.value = MusicChoice(query, apps, includeIrvin)
    }

    /** Reproduce en la app elegida (o en el reproductor de Irvin si app es null). */
    fun playMusic(query: String, app: com.irvin.dacrohx.device.MusicPlayer.App?): String {
        _musicChoice.value = null
        if (app != null) {
            return if (music.playInApp(app, query)) "Abriendo '$query' en ${app.label}."
            else "No pude abrir ${app.label}."
        }
        val hits = music.search(query)
        if (hits.isEmpty()) return "No encontré '$query' entre las canciones del teléfono. NO digas que pusiste música."
        music.playList(hits)
        return "Reproduciendo '${hits.first().label}' en el reproductor de Irvin."
    }

    // ---------------- aviso de mensajes con palabra secreta
    @Volatile var uiVisible = false
    @Volatile private var lastAnnounced: com.irvin.dacrohx.device.NotifListener.Item? = null
    @Volatile private var lastAnnounceAt = 0L
    private var lastAnnounceKey = ""
    private var announceJob: Job? = null

    private fun nrm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9ñ ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** ¿La frase contiene la palabra secreta? (tolerante a acentos y mayúsculas) */
    fun secretOk(said: String): Boolean {
        val sec = nrm(store.settings.value.secretWord)
        return sec.isEmpty() || nrm(said).contains(sec)
    }
    val hasSecret: Boolean get() = store.settings.value.secretWord.isNotBlank()

    fun onIncomingMessage(item: com.irvin.dacrohx.device.NotifListener.Item) {
        val st = store.settings.value
        fun log(r: String) { com.irvin.dacrohx.device.NotifListener.lastEvent += " → $r" }
        if (!st.announceMsgs) return log("avisos apagados")
        if (st.secretWord.isBlank()) return log("falta palabra secreta")
        val am = app.getSystemService(android.media.AudioManager::class.java)
        if (am.mode == android.media.AudioManager.MODE_IN_CALL || am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) return log("en llamada, no se avisa")
        // Evita repetir el aviso si la misma persona manda varios mensajes seguidos.
        val key = item.pkg + "|" + item.title
        val now = System.currentTimeMillis()
        if (key == lastAnnounceKey && now - lastAnnounceAt < 45_000) { lastAnnounced = item; return log("misma persona hace poco, no repito") }
        if (genJob?.isActive == true || voiceLoop || _mode.value == Mode.LISTENING || announceJob?.isActive == true) { lastAnnounced = item; return log("estaba ocupado") }
        log("anunciado ✓")
        lastAnnounceKey = key; lastAnnounceAt = now; lastAnnounced = item
        val who = if (st.discreetAnnounce) "" else " de ${item.title}"
        val via = if (st.discreetAnnounce) "" else " por ${item.app}"
        announceJob = viewModelScope.launch {
            speaker.stop()
            speaker.say("Tienes un mensaje nuevo$who$via. ¿Quieres que lo lea?", flush = true)
            kotlinx.coroutines.delay(400)
            speaker.speaking.first { !it }
            val canListen = androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                (uiVisible || com.irvin.dacrohx.device.WakeWordService.running.value || com.irvin.dacrohx.device.BubbleService.running)
            if (!canListen) return@launch
            holdMic(40_000)
            val answer = try { hear() } finally { releaseMic() }
            if (answer.isNullOrBlank()) return@launch
            playEnd()
            answerAnnouncement(answer)
        }
    }

    /** Botón "Probar aviso" en Ajustes: simula que llegó un mensaje. */
    fun testAnnouncement() {
        lastAnnounceKey = ""
        onIncomingMessage(
            com.irvin.dacrohx.device.NotifListener.Item(
                "prueba", "irvin.prueba", "Prueba", "Irvin", "Este es un mensaje de prueba. Si lo escuchas, todo funciona.",
                System.currentTimeMillis(), null, true,
            )
        )
    }

    /** Respuesta a "¿Quieres que lo lea?". Con palabra secreta, solo la palabra correcta lo lee. */
    private fun answerAnnouncement(said: String): Boolean {
        val item = lastAnnounced ?: return false
        val x = nrm(said)
        val no = Regex("^(no|nel|nop|ahorita no|luego|despues|al rato|ahora no)\\b").containsMatchIn(x)
        if (no) { speaker.say("Ok.", flush = true); lastAnnounced = null; return true }
        val yes = Regex("\\b(si|claro|dale|va|ok|leelo|lee|leemelo|adelante|simon|sale|por favor)\\b").containsMatchIn(x)
        // Solo la palabra secreta lo lee. Cualquier otra respuesta: "De acuerdo", sin pistas.
        if (hasSecret && secretOk(said)) { readMessages(item); return true }
        if (yes) { speaker.say("De acuerdo.", flush = true); lastAnnounced = null; return true }
        return false
    }

    private fun readMessages(item: com.irvin.dacrohx.device.NotifListener.Item) {
        lastAnnounced = null
        val all = com.irvin.dacrohx.device.NotifListener.recent(item.title, 5).filter { it.pkg == item.pkg }.reversed()
        val texts = (if (all.isEmpty()) listOf(item) else all).joinToString(". ") { it.text.take(400) }
        speaker.say("${item.title} dice: $texts", flush = true)
        // Por privacidad no se guarda el contenido en el chat.
        store.addChat(ChatMsg(role = "sys", text = "📩 Mensaje leído"))
    }

    /** Pide música respetando los favoritos (1 = directo, 2 = pregunta entre ellos). */
    fun requestMusic(query: String, appName: String): String {
        val all = music.musicApps()
        fun appOf(id: String) = if (id == "irvin") null else all.firstOrNull { it.pkg == id }
        val q = query.trim().takeUnless { it.isEmpty() || it.equals("ninguno", true) || it.equals("ninguna", true) } ?: "lo que sea"
        val wanted = appName.trim().lowercase().takeUnless { it == "ninguno" || it == "ninguna" || it == "vacio" || it == "vacío" } ?: ""
        if (wanted.isNotEmpty()) {
            if ("irvin" in wanted || "reproductor" in wanted || "tuyo" in wanted) return playMusic(q, null)
            val t = music.findApp(if (wanted == "premium") "youtube" else wanted)
                ?: return "No encontré la app '$appName'. Tengo: " + all.joinToString { it.label } + "."
            return playMusic(q, t)
        }
        val favs = store.settings.value.let { st ->
            st.musicApp.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2)
        }.filter { it == "irvin" || all.any { a -> a.pkg == it } }
        return when (favs.size) {
            1 -> playMusic(q, appOf(favs[0]))
            2 -> {
                askMusicApp(q, favs.mapNotNull { appOf(it) }, includeIrvin = "irvin" in favs)
                val names = favs.map { appOf(it)?.label ?: "el reproductor de Irvin" }
                "PREGUNTA: ¿${names[0]} o ${names[1]}?"
            }
            else -> if (all.isEmpty()) playMusic(q, null) else {
                askMusicApp(q, all, includeIrvin = true); "PREGUNTA: ¿En qué app la pongo?"
            }
        }
    }

    /** Último pedido de música (para cumplirlo si el cerebro dice "reproduciendo" sin hacerlo). */
    @Volatile private var lastMusic: Triple<String, String, Long>? = null

    /** Detecta "pon música de X en Y" y devuelve (búsqueda, app) o null. */
    private fun parseMusic(x: String): Pair<String, String>? {
        val m = Regex("^(pon|ponme|ponle|reproduce|reproducir|toca|tocame|quiero (escuchar|oir)|escuchar|pones)\\s+(.*)$").find(x) ?: return null
        var rest = m.groupValues[3].trim()
        if (Regex("^(la |el )?(alarma|recordatorio|timer|temporizador|linterna|volumen)").containsMatchIn(rest)) return null
        val apps = "poweramp|power amp|youtube premium|youtube music|yt music|youtube|you tube|premium|spotify|reproductor de irvin|tu reproductor|irvin"
        var app = ""
        Regex("\\s+(en|con|desde|por)\\s+($apps)\\s*$").find(rest)?.let { app = it.groupValues[2]; rest = rest.substring(0, it.range.first) }
        if (app.isEmpty()) Regex("^($apps)\\s+(.*)$").find(rest)?.let { app = it.groupValues[1]; rest = it.groupValues[2] }
        // "música en YouTube Premium de Maná": la app puede venir en medio.
        if (app.isEmpty()) Regex("\\s*\\b(en|con|desde|por)\\s+($apps)\\b\\s*").find(rest)?.let {
            app = it.groupValues[2]; rest = (rest.substring(0, it.range.first) + " " + rest.substring(it.range.last + 1)).replace(Regex("\\s+"), " ").trim()
        }
        val stripped = rest.replace(Regex("^(algo de |un poco de |unas? |la |el |las |los )"), "")
            .replace(Regex("^(musica|canciones|cancion|rolas|rola|temas|tema|videos?)(\\s+(de|del|a|al))?\\s*"), "")
            .replace(Regex("^(la |el |los |las )?(grupo|banda|cantante|artista)\\s+"), "").trim()
        val isMusic = Regex("\\b(musica|cancion|canciones|rola|rolas|disco|album|tema)\\b").containsMatchIn(rest) || app.isNotEmpty()
        if (!isMusic) return null
        return stripped.ifEmpty { "lo que sea" } to app.replace("power amp", "poweramp").replace("you tube", "youtube")
    }

    fun chooseMusicApp(app: com.irvin.dacrohx.device.MusicPlayer.App?, remember: Boolean) {
        val c = _musicChoice.value ?: return
        if (remember) store.updateSettings { it.copy(musicApp = app?.pkg ?: "irvin") }
        speaker.stop()
        val r = playMusic(c.query, app)
        store.addChat(ChatMsg(role = "sys", text = "🎵 $r"))
    }
    fun dismissMusicChoice() { _musicChoice.value = null }

    // ---------------- documento adjunto
    private val _pendingDoc = MutableStateFlow<com.irvin.dacrohx.device.Docs.Doc?>(null)
    val pendingDoc: StateFlow<com.irvin.dacrohx.device.Docs.Doc?> = _pendingDoc.asStateFlow()
    fun attachDoc(uri: android.net.Uri) = viewModelScope.launch {
        try {
            val d = kotlinx.coroutines.withContext(Dispatchers.IO) { com.irvin.dacrohx.device.Docs.read(app, uri) }
            if (d.text.isBlank() && d.pages.isEmpty()) toast("No pude leer texto de ese documento.") else _pendingDoc.value = d
        } catch (e: Exception) { toast("No pude abrir el documento: ${e.message}") }
    }
    fun clearDoc() { _pendingDoc.value = null }

    // ---------------- bloqueo con huella
    private val _locked = MutableStateFlow(store.settings.value.biometric)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    fun lock() { if (store.settings.value.biometric) _locked.value = true }
    fun unlock() { _locked.value = false }

    fun checkUpdates(silent: Boolean = false) = viewModelScope.launch { updater.check(silent) }
    fun downloadUpdate(r: com.irvin.dacrohx.device.Updater.Release) = viewModelScope.launch {
        stopAll()
        updater.download(r)
    }

    /** El modelo que se usa: el turbo si está activado y descargado. */
    private fun activeModel(): ModelManager =
        if (store.settings.value.turbo && turbo.isReady()) turbo else models
    @Volatile var loadedLabel: String = ""
        private set
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

    init {
        viewModelScope.launch {
            speaker.speaking.collect { s ->
                if (s && _mode.value == Mode.IDLE) _mode.value = Mode.SPEAKING
                if (!s && _mode.value == Mode.SPEAKING) _mode.value = Mode.IDLE
            }
        }
        viewModelScope.launch { models.watchDownload() }
        viewModelScope.launch { wake.watch() }
        viewModelScope.launch { turbo.watchDownload() }
        viewModelScope.launch { kotlinx.coroutines.delay(4000); updater.check(silent = true) }
        viewModelScope.launch {
            store.settings.collect { st ->
                speaker.rate = st.speechRate
                speaker.pitch = st.pitch
                speaker.setVoice(st.voiceName)
            }
        }
        viewModelScope.launch {
            models.state.collect { if (it is ModelState.Ready && brain.state.value is BrainState.Off) bootBrain() }
        }
        ReminderScheduler.rescheduleAll(app)
        com.irvin.dacrohx.device.NotifListener.ensureConnected(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(CH_REPLY, "Respuestas de Irvin", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun toast(msg: String) { _toast.value = msg }
    fun clearToast() { _toast.value = null }

    // ------------------------------------------------------------------ cerebro
    private fun bootBrain() = viewModelScope.launch {
        val m = activeModel()
        loadedLabel = m.label
        brain.load(m.modelFile.absolutePath)
        if (brain.ready) resetConversation(keepHistory = true)
    }

    /** Activa/desactiva el modo turbo (descarga el modelo rápido la primera vez). */
    fun setTurbo(on: Boolean) {
        store.updateSettings { it.copy(turbo = on) }
        if (on && !turbo.isReady()) {
            turbo.startDownload()
            toast("Descargando cerebro turbo (2.6 GB)… sigues usando el actual mientras tanto.")
            viewModelScope.launch {
                turbo.watchDownload()
                if (turbo.isReady() && store.settings.value.turbo) { retryBrain(); toast("¡Modo turbo listo!") }
            }
            return
        }
        retryBrain()
    }

    fun retryBrain() = viewModelScope.launch {
        brain.close()
        bootBrain()
    }

    private fun tools() = IrvinTools(getApplication(), store, device, ir, music, online, phone, cloud, this) { label ->
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
Eres IRVIN DACROHX, asistente personal de $user en su teléfono RedMagic 11 Pro. Tu cerebro corre en el teléfono (funcionas sin internet) y cuando hay conexión puedes consultar internet con tus herramientas.
Personalidad: ${s.persona}
Reglas:
- Español de México, natural. Charla y órdenes rápidas: breve (1-3 frases). Pero si pide una receta, instrucciones, una explicación o "cómo se hace": responde COMPLETO y útil.
- RECETAS: siempre da (1) porciones y tiempo, (2) lista de ingredientes con cantidades exactas, (3) pasos numerados claros, (4) un tip. Sabes cocinar muy bien: nunca digas que necesitas internet para una receta.
- Audio = el usuario hablándote; responde a lo que dijo. Imagen = analízala.
- Sé PRECISO y DIRECTO. Nunca pidas que el usuario "sea más específico" en preguntas simples: responde con la interpretación más lógica. Solo pregunta si de verdad falta un dato indispensable (por ejemplo, la hora de un recordatorio).
- Para la hora o la fecha exactas usa SIEMPRE fecha_hora_actual; no la adivines.
- Si algo requiere una acción, usa la herramienta. NUNCA dejes un parámetro de texto vacío: si no hay valor usa "ninguno" (o "lo que sea" para la música).
- Si algo requiere una acción, usa la herramienta. NUNCA digas que hiciste algo (poner música, guardar, programar, abrir) si no usaste la herramienta y esta no confirmó. Si no tienes herramienta para algo, dilo.
- leer_notificaciones puede pedir una palabra clave: si responde CLAVE, pide al usuario "su palabra clave" (sin decir cuál es ni dar pistas) y vuelve a llamar con lo que diga. Nunca repitas la palabra clave.
- Mensajes: enviar_sms y responder_mensaje solo PREPARAN; lee el mensaje y pregunta "¿Lo envío?". Solo si el usuario dice que sí → confirmar_accion. Si dice que no → cancelar_accion. Nunca digas "enviado" antes de confirmar_accion.
- Si el usuario adjunta un DOCUMENTO, contesta usando su contenido.
- Datos personales que valga la pena recordar → guardar_recuerdo. "Recuérdame…" → recordatorio_en_minutos/recordatorio_en_fecha. Música → reproducir_musica. Cuentas → calcular.
- Conocimiento general (recetas, cómo hacer algo, explicaciones, traducciones, historia, consejos): lo sabes tú, responde directo SIN mencionar internet.
- Datos actuales (noticias, clima, precios, dólar, resultados, horarios, algo reciente): usa las herramientas de INTERNET. Si responden SIN_INTERNET, dilo y no inventes.
Fecha y hora al iniciar: $now. Si necesitas la hora exacta usa fecha_hora_actual. NUNCA escribas fechas ni horas entre corchetes al inicio de tus respuestas.
- "para/pausa/detén/quita la música" = controlar_musica. Para poner música usa reproducir_musica; si responde PREGUNTA, haz esa pregunta al usuario.
Sabes del usuario:
$mem
Recordatorios:
$rem
""".trim()
    }

    fun resetConversation(keepHistory: Boolean) = viewModelScope.launch {
        if (!brain.ready) return@launch
        val history = if (!keepHistory) emptyList() else store.chat.value
            .filter { !it.pending && !it.error && (it.role == "user" || it.role == "model") && it.text.isNotBlank() }
            .takeLast(8)
            .map { it.role to it.text.take(800) }
            .dropWhile { it.first != "user" }
        try {
            brain.newConversation(systemPrompt(), history, tools())
            conversationReady = true
        } catch (e: Throwable) {
            toast("No pude iniciar la conversación: ${e.message}")
        }
    }

    /** Ejecuta la herramienta que el modelo quiso llamar cuando el motor no pudo leerla. */
    private fun runToolFromError(err: String): String? {
        val m = Regex("call:([a-z_]+)\\{(.*?)\\}").find(err) ?: return null
        val name = m.groupValues[1]
        val raw = m.groupValues[2]
        val args = HashMap<String, String>()
        Regex("([a-z_]+):<\\|\"\\|>(.*?)<\\|\"\\|>").findAll(raw).forEach { args[it.groupValues[1]] = it.groupValues[2] }
        Regex("([a-z_]+):(-?[0-9.]+|true|false)(?=[,}]|$)").findAll(raw).forEach { args.putIfAbsent(it.groupValues[1], it.groupValues[2]) }
        com.irvin.dacrohx.device.VoiceLog.add("Herramienta rescatada: $name $args")
        val camel = name.split('_').mapIndexed { i, w -> if (i == 0) w else w.replaceFirstChar { it.uppercase() } }.joinToString("")
        val t = tools()
        val fn = t::class.members.firstOrNull { it.name == camel } as? kotlin.reflect.KFunction<*> ?: return null
        return try {
            val params = fn.parameters
            val call = HashMap<kotlin.reflect.KParameter, Any?>()
            params.forEach { p ->
                if (p.kind == kotlin.reflect.KParameter.Kind.INSTANCE) { call[p] = t; return@forEach }
                val key = p.name!!.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }
                val v = args[key] ?: args[p.name!!] ?: ""
                call[p] = when (p.type.classifier) {
                    Int::class -> v.toDoubleOrNull()?.toInt() ?: 0
                    Boolean::class -> v == "true"
                    Double::class -> v.toDoubleOrNull() ?: 0.0
                    else -> v
                }
            }
            val r = fn.callBy(call)?.toString() ?: return null
            r.removePrefix("PREGUNTA: ").substringBefore(" Haz esa pregunta").substringBefore(" NO digas").trim()
        } catch (e: Throwable) {
            com.irvin.dacrohx.device.VoiceLog.add("No pude rescatar la herramienta: ${e.message}")
            null
        }
    }

    /** Quita fechas/horas entre corchetes que el modelo a veces copia al inicio. */
    private fun cleanReply(t: String): String =
        t.replace(Regex("^\\s*\\[[^\\]]{0,48}\\]\\s*"), "").replace(Regex("\\n\\s*\\[(lun|mar|mi[eé]|jue|vie|s[aá]b|dom)[^\\]]{0,44}\\]\\s*"), "\n")

    /** Órdenes simples que no necesitan pensar: se ejecutan al instante. */
    private suspend fun quickCommand(t: String): String? {
        val x = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").replace(Regex("[¿¡?!.,;:]"), " ").replace(Regex("\\s+"), " ").trim()
        // Respuesta a "¿Quieres que lo lea?" (dentro de 2 minutos del aviso).
        if (lastAnnounced != null && System.currentTimeMillis() - lastAnnounceAt < 120_000) {
            if (answerAnnouncement(t)) return "\u200B"
        }
        // Respuesta a "¿Lo envío?" de un mensaje preparado: sí / no al instante.
        if (_pending.value != null) {
            val c0 = x.replace(Regex("^(oye irvin|irvin|oye)\\s+"), "")
            if (Regex("^(si|claro|dale|ok|okey|va|simon|sale|adelante|hazlo)?( si)?( (envialo|mandalo|envia|manda|por favor|adelante))*$").matches(c0) && c0.isNotBlank())
                return confirmPending()
            if (Regex("^(no|nel|cancela|cancelalo|mejor no|no lo (envies|mandes)|olvidalo|dejalo)( por favor)?$").matches(c0)) { cancelPending(); return "Ok, no lo envío." }
        }
        // Respuesta a "¿Poweramp o YouTube?": basta con decir el nombre.
        _musicChoice.value?.let { c ->
            val y = x.replace(Regex("^(en|con|el|la|pon(lo|la)? en|mejor en)\\s+"), "").trim()
            if ("irvin" in y || "tuyo" in y || y == "reproductor") { chooseMusicApp(null, false); return "Listo." }
            val pick = c.apps.firstOrNull { a ->
                val l = java.text.Normalizer.normalize(a.label.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                l == y || (y.length >= 4 && (l.contains(y) || y.contains(l))) || (y == "premium" && "premium" in l) || (y == "power" && "poweramp" in l)
            }
            if (pick != null) { chooseMusicApp(pick, false); return "Listo, en ${pick.label}." }
        }
        // Busca una orden de música en cualquier frase (aunque se cuele ruido o el reconocedor
        // escuche "por" / "pom" / "bon" en lugar de "pon").
        run {
            val raw = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            val sentences = (listOf(x) + raw.split(Regex("[.?!;¿¡]+"))).map { it.replace(Regex("[,:]"), " ").replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
            for (sen in sentences) {
                val fixed = sen.replace(Regex("^(oye irvin |irvin |oye )?"), "")
                    .replace(Regex("^(por|pom|bon|pong|ponga|pongan|pones|pone|ton|com|un|on|pan|pun|son|don)\\b"), "pon")
                parseMusic(fixed)?.let { (q, app) ->
                    lastMusic = Triple(q, app, System.currentTimeMillis())
                    val r = requestMusic(q, app)
                    return if (r.startsWith("PREGUNTA:")) r.removePrefix("PREGUNTA: ") else r
                }
            }
        }
        val musicWord = Regex("\\b(musica|cancion|rola|poweramp|spotify)\\b").containsMatchIn(x)
        val own = music.ownActive()
        fun key(k: Int) = music.mediaKey(k)
        val c = x.replace(Regex("^(oye irvin|irvin|oye)\\s+"), "")
        val simple = when {
            // "para", "cállate", "ya": deja de hablar (como Siri).
            Regex("^(para|detente|callate|silencio|basta|stop|ya no|olvidalo|no nada|nada mas)$").matches(c) -> { speaker.stop(); "\u200B" }
            (musicWord && Regex("^(para|pausa|pausar|deten|detener|quita|quitar|apaga|apagar|calla)\\b").containsMatchIn(c)) ||
                Regex("^(pausa|pausala|pausar|pon pausa|ponle pausa)$").matches(c) -> {
                if (own) music.pause() else key(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); "Música en pausa."
            }
            Regex("^(siguiente|la siguiente|siguiente cancion|otra cancion|cambia(le)?( de| la)? cancion|la que sigue|pasa(le)?( la)? cancion|adelanta(le)?|salta(la|te)?)$").matches(c) -> {
                if (own) music.next() else key(android.view.KeyEvent.KEYCODE_MEDIA_NEXT); "Siguiente canción."
            }
            Regex("^(anterior|la anterior|cancion anterior|regresa(le)?( la)? cancion|la de antes)$").matches(c) -> {
                if (own) music.previous() else key(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Canción anterior."
            }
            (musicWord && Regex("^(reanuda|continua|sigue con|play|quita la pausa)").containsMatchIn(c)) ||
                Regex("^(reanuda|reanudala|continua|play|dale play|quita(le)? la pausa)$").matches(c) -> {
                if (own) music.resume() else key(android.view.KeyEvent.KEYCODE_MEDIA_PLAY); "Reanudando."
            }
            Regex("^(que hora es|que horas son|dime la hora|me dices la hora|me das la hora|hora|la hora|que hora tienes|que hora seran)( ahorita| ahora)?$").matches(c) ->
                "Son las " + SimpleDateFormat("h:mm a", Locale("es", "MX")).format(Date()).replace("a. m.", "de la mañana").replace("p. m.", "de la tarde").replace("a.m.", "de la mañana").replace("p.m.", "de la tarde") + "."
            Regex("^(que (dia|fecha) es( hoy)?|a que (dia|fecha) estamos( hoy)?|que fecha|fecha de hoy|dime la fecha|en que (dia|fecha) estamos|que dia es)$").matches(c) ->
                "Hoy es " + SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "MX")).format(Date()) + "."
            Regex("^(cuanta (bateria|pila) (tengo|tiene|me queda|queda|traigo)|bateria|pila|nivel de bateria|como (esta|va|ando de) (la )?bateria|porcentaje de bateria)$").matches(c) -> device.battery()
            Regex("^(prende|enciende|activa|pon|prendeme) (la )?(linterna|lampara|luz del celular|flash)$").matches(c) || c == "linterna" -> device.torch(true)
            Regex("^(apaga|desactiva|quita|apagame) (la )?(linterna|lampara|luz del celular|flash)$").matches(c) -> device.torch(false)
            else -> null
        }
        if (simple != null) return simple
        // Alarmas, temporizadores, recordatorios, llamadas, apps, clima, dólar, cuentas, volumen.
        return try { intents.handle(c, t.lowercase()) } catch (e: Exception) {
            com.irvin.dacrohx.device.VoiceLog.add("Orden rápida falló: ${e.message}"); null
        }
    }

    /** Conversación nueva conservando solo los últimos mensajes. */
    private suspend fun compactConversation() {
        val history = store.chat.value
            .filter { !it.pending && !it.error && (it.role == "user" || it.role == "model") && it.text.isNotBlank() }
            .dropLast(2).takeLast(4)
            .map { it.role to it.text.take(600) }
            .dropWhile { it.first != "user" }
        try { brain.newConversation(systemPrompt(), history, tools()) } catch (_: Throwable) {}
    }

    fun newChat() {
        stopAll()
        store.clearChat()
        resetConversation(keepHistory = false)
    }

    // ------------------------------------------------------------------ enviar
    private fun stamp(): String =
        "[" + SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale("es", "MX")).format(Date()) + "]"

    fun sendText(text: String) { viewModelScope.launch { sendTextNow(text) } }

    private suspend fun sendTextNow(text: String) {
        val t = text.trim()
        val img = _pendingImage.value
        val doc = _pendingDoc.value
        if (img == null && doc == null && genJob?.isActive != true) {
            quickCommand(t)?.let { r ->
                if (r == "\u200B") return
                store.addChat(ChatMsg(role = "user", text = t))
                store.addChat(ChatMsg(role = "model", text = r, actions = listOf("⚡ instantáneo")))
                if (store.settings.value.autoSpeak || voiceTurn) speaker.say(r, flush = true)
                followUpInApp(r)
                return
            }
        }
        if (t.isEmpty() && img == null && doc == null) return
        if (!brain.ready) {
            if (!models.isReady()) { toast("Primero descarga el cerebro de Irvin."); return }
            toast("Dame un segundo, estoy despertando…")
            if (!ensureBrain()) { toast("No pude encender el cerebro."); return }
        }
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
        var shown = t
        if (doc != null) {
            if (doc.pages.isNotEmpty() && brain.visionEnabled) doc.pages.forEach { pg ->
                parts += Content.ImageBytes(ByteArrayOutputStream().also { pg.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
            }
            if (doc.text.isNotBlank()) parts += Content.Text("DOCUMENTO \"${doc.name}\":\n${doc.text}\n--- fin del documento ---")
            if (shown.isEmpty()) shown = "Resúmeme este documento."
            _pendingDoc.value = null
        }
        if (shown.isEmpty()) shown = "¿Qué ves en esta imagen?"
        parts += Content.Text(shown)
        val userMsg = ChatMsg(
            role = "user", text = if (doc != null) "📄 ${doc.name}\n$shown" else shown,
            kind = if (img != null) "image" else "text", image = imgPath,
        )
        run(userMsg, parts)
    }

    private fun sendVoice(wav: ByteArray, forceSpeak: Boolean = false): Job? {
        if (!brain.audioEnabled) { toast("El audio no está disponible en este modo."); return null }
        val secs = (wav.size - 44) / (VoiceRecorder.SAMPLE_RATE * 2)
        val parts = listOf(
            Content.AudioBytes(wav),
            Content.Text("(Mensaje de voz. Responde a lo que dijo.)"),
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
        val autoSpeak = forceSpeak || voiceTurn || store.settings.value.autoSpeak
        speaker.rate = store.settings.value.speechRate
        genJob = viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            var tFirst = 0L
            val sb = StringBuilder()
            var fed = 0
            var lastUi = 0L
            var failed: String? = null
            // Si la memoria de trabajo va muy llena, empieza una conversación fresca (recordando lo último).
            val usedBefore = brain.tokenCount()
            if (usedBefore > brain.maxTokens * 0.8) compactConversation()
            suspend fun attempt() {
                try {
                    brain.send(parts)
                        .catch { e -> failed = e.message ?: e.toString() }
                        .collect { delta ->
                            if (delta.isNotEmpty()) {
                                if (tFirst == 0L) tFirst = System.currentTimeMillis() - t0
                                sb.append(delta)
                                val clean = cleanReply(sb.toString())
                                // Si apenas empezó con "[", espera a ver si es una fecha para no leerla.
                                val holding = sb.startsWith("[") && !sb.contains("]") && sb.length < 50
                                if (!holding && autoSpeak && clean.length > fed) {
                                    speaker.feed(clean.substring(fed)); fed = clean.length
                                }
                                val now = System.currentTimeMillis()
                                if (!holding && now - lastUi > 90) {
                                    lastUi = now
                                    store.updateChat(reply.id) { it.copy(text = clean) }
                                }
                            }
                        }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed = e.message ?: e.toString()
                }
            }
            attempt()
            // El motor a veces no entiende la llamada a herramienta (p. ej. un texto vacío): la ejecutamos nosotros.
            if (failed?.contains("parse tool call", ignoreCase = true) == true || failed?.contains("FC tool calls") == true) {
                val r = runToolFromError(failed!!)
                if (r != null) {
                    failed = null
                    sb.setLength(0); sb.append(r)
                    if (autoSpeak) speaker.say(r, flush = true)
                }
            }
            // Si falló sin decir nada (p. ej. memoria llena), reinicia la conversación y reintenta una vez.
            if (failed != null && sb.isEmpty()) {
                failed = null
                fed = 0
                compactConversation()
                attempt()
            }
            var finalText = cleanReply(sb.toString()).trim()
            // El cerebro a veces dice "Reproduciendo…" sin usar la herramienta: lo hacemos de verdad.
            val usedTool = store.chat.value.firstOrNull { it.id == reply.id }?.actions?.isNotEmpty() == true
            if (failed == null && !usedTool && Regex("(?i)^(listo[,.]? )?(reproduciendo|poniendo|pongo|abriendo|te pongo|ya (puse|está sonando))").containsMatchIn(finalText)) {
                val lm = lastMusic?.takeIf { System.currentTimeMillis() - it.third < 5 * 60_000 }
                val fixed = if (lm != null) requestMusic(lm.first, lm.second).let { if (it.startsWith("PREGUNTA:")) it.removePrefix("PREGUNTA: ") else it }
                else "Perdón, no la puse. Dime por ejemplo: pon Quintana en YouTube."
                com.irvin.dacrohx.device.VoiceLog.add("El cerebro dijo que puso música sin hacerlo → $fixed")
                finalText = fixed
                if (autoSpeak) speaker.say(fixed, flush = true)
            }
            val total = System.currentTimeMillis() - t0
            val used = brain.tokenCount()
            val speed = "⚡ %.1f s · %.1f s · 🧠 %.1fk".format(tFirst / 1000.0, total / 1000.0, used / 1000.0)
            store.updateChat(reply.id, persist = true) { m0 ->
                val it = if (failed == null) m0.copy(actions = m0.actions + speed) else m0
                when {
                    failed != null && finalText.isEmpty() -> it.copy(text = "Algo falló en mis circuitos: $failed", pending = false, error = true)
                    finalText.isEmpty() -> it.copy(text = "Listo.", pending = false)
                    else -> it.copy(text = finalText, pending = false)
                }
            }
            if (autoSpeak && failed == null) speaker.finish()
            lastReplyText = if (failed == null) finalText else null
            if (!forceSpeak && failed == null) followUpInApp(finalText) else if (!forceSpeak) endVoiceTurn()
            currentReplyId = null
            _mode.value = if (speaker.speaking.value) Mode.SPEAKING else Mode.IDLE
        }
        return genJob
    }

    fun stopAll() {
        brain.stop()
        genJob?.cancel()
        voiceJob?.cancel()
        recDone?.complete(Unit)
        activeSr?.let { try { it.destroy() } catch (_: Exception) {} }
        activeSr = null
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
    private var voiceJob: Job? = null
    @Volatile private var activeSr: SpeechRecognizer? = null
    @Volatile private var recDone: CompletableDeferred<Unit>? = null

    /** Botón del micrófono en la app: escucha, entiende y responde (y sigue la plática si Irvin pregunta). */
    fun toggleListening() {
        if (_mode.value == Mode.LISTENING) { stopListeningAndSend(); return }
        if (_mode.value == Mode.THINKING || voiceJob?.isActive == true) return
        speaker.stop()
        voiceJob = viewModelScope.launch {
            holdMic(60_000)
            val t = hear()
            if (t.isNullOrBlank()) {
                if (voiceTurn && followMisses < 1) {
                    followMisses++
                    speaker.say("¿Me repites?", flush = true)
                    followUpInApp("?")
                } else { toast("No te escuché."); endVoiceTurn() }
                return@launch
            }
            playEnd()
            voiceTurn = true
            followMisses = 0
            holdMic(90_000)
            sendTextNow(t)
        }
    }

    /** Tocar el micrófono mientras escucha: termina ya y usa lo que llevas dicho. */
    fun stopListeningAndSend() {
        val sr = activeSr
        if (sr != null) { try { sr.stopListening() } catch (_: Exception) {}; return }
        recDone?.complete(Unit)
    }

    /** Cuánto esperar tras el tono de "te escucho" para no grabar el propio tono. */
    private fun toneLead(forRecorder: Boolean): Long {
        val len = sounds.wakeLengthMs(store.settings.value.wakeSound)
        return if (forRecorder) (len - 100).coerceIn(0, 1200) else (len - 450).coerceIn(0, 1000)
    }

    @Volatile private var earBrokenAt = 0L
    @Volatile private var onDeviceBroken = false
    @Volatile private var standardNeedsNet = false

    /** ¿Se puede usar el reconocedor de Android (rápido y preciso)? */
    private val androidEarOk: Boolean get() {
        if (!store.settings.value.fastVoice || !SpeechRecognizer.isRecognitionAvailable(app)) return false
        if (System.currentTimeMillis() - earBrokenAt < 10 * 60_000L) return false
        if ((onDeviceBroken || !SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) && standardNeedsNet && !online.connected()) return false
        return true
    }

    /**
     * EL oído de Irvin: escucha una frase y la devuelve como texto.
     * Usa el reconocedor del teléfono; si no sirve, el de Google; y si tampoco, el oído de Gemma.
     */
    suspend fun hear(tone: Boolean = true): String? {
        speaker.stop()
        micBusy = true
        _partialSpeech.value = ""
        _mode.value = Mode.LISTENING
        try {
            if (androidEarOk) {
                if (tone) { playWake(); kotlinx.coroutines.delay(toneLead(false)) }
                val t = listenOnce()
                if (!t.isNullOrBlank()) return t.trim()
                if (androidEarOk) return null // simplemente no dijiste nada
                com.irvin.dacrohx.device.VoiceLog.add("Cambio al oído de Gemma")
                return gemmaEar(tone = true)
            }
            return gemmaEar(tone)
        } finally {
            micBusy = false
            _partialSpeech.value = ""
            if (_mode.value == Mode.LISTENING || _mode.value == Mode.THINKING) _mode.value = Mode.IDLE
        }
    }

    private suspend fun gemmaEar(tone: Boolean): String? {
        if (!brain.ready) {
            if (!models.isReady()) { speaker.say("Primero necesito descargar mi cerebro. Abre la app.", flush = true); return null }
            _mode.value = Mode.THINKING
            if (!ensureBrain()) return null
        }
        if (!brain.audioEnabled) { toast("El reconocimiento de voz no está disponible en este modo."); return null }
        _mode.value = Mode.LISTENING
        if (tone) { playWake(); kotlinx.coroutines.delay(toneLead(true)) }
        com.irvin.dacrohx.device.VoiceLog.add("Oído de Gemma: escuchando")
        val wav = recordUntilSilence()
        if (wav == null) { com.irvin.dacrohx.device.VoiceLog.add("Oído de Gemma: no se escuchó voz"); return null }
        _mode.value = Mode.THINKING
        val t = try { brain.transcribe(wav) } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.irvin.dacrohx.device.VoiceLog.add("Transcripción falló: ${e.message}"); null
        }
        com.irvin.dacrohx.device.VoiceLog.add("Oído de Gemma entendió: \"" + (t ?: "") + "\"")
        return t
    }

    /** Graba hasta que dejas de hablar (o tocas el micrófono). */
    private suspend fun recordUntilSilence(): ByteArray? {
        val d = CompletableDeferred<Unit>()
        recDone = d
        recorder.onAutoStop = { d.complete(Unit) }
        if (!recorder.start(stopOnSilence = true)) {
            recorder.onAutoStop = null; recDone = null
            com.irvin.dacrohx.device.VoiceLog.add("No pude abrir el micrófono")
            return null
        }
        return try {
            kotlinx.coroutines.withTimeoutOrNull(12_000) { d.await() }
            recorder.stop()
        } catch (e: kotlinx.coroutines.CancellationException) {
            recorder.stop(); throw e
        } finally {
            recorder.onAutoStop = null; recDone = null
        }
    }

    /** Errores de "no pude abrir el micrófono / reconocedor ocupado": vale la pena reintentar. */
    private val retryable = setOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT, 10, 11, SpeechRecognizer.ERROR_AUDIO)
    private val netErrors = setOf(SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, 12, 13)
    @Volatile private var lastVoiceError = 0

    /** Escucha una frase con el reconocedor de Android, cambiando de motor o reintentando si hace falta. */
    private suspend fun listenOnce(): String? {
        var attempt = 0
        while (attempt < 3) {
            lastVoiceError = 0
            val onDevice = !onDeviceBroken && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
            val r = kotlinx.coroutines.withTimeoutOrNull(20_000) { listenRaw(onDevice) ?: "" }
            if (!r.isNullOrBlank()) return r
            val e = if (r == null) SpeechRecognizer.ERROR_CLIENT else lastVoiceError // null = se quedó colgado
            when {
                e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { toast("Falta el permiso del micrófono."); return null }
                onDevice && (e == 12 || e == 13) -> {
                    // El reconocedor del teléfono no tiene el español: pide descargarlo y usa el de Google ya mismo.
                    onDeviceBroken = true
                    com.irvin.dacrohx.device.VoiceLog.add("Reconocedor del teléfono sin español → uso el de Google")
                    requestOnDeviceSpanish()
                }
                !onDevice && e in netErrors -> {
                    standardNeedsNet = true
                    if (online.connected()) earBrokenAt = System.currentTimeMillis()
                    com.irvin.dacrohx.device.VoiceLog.add("Reconocedor de Google necesita internet → oído de Gemma")
                    return null
                }
                e in retryable -> { attempt++; kotlinx.coroutines.delay(300L + attempt * 300L) }
                else -> return null // no hablaste (silencio) o no hubo coincidencias
            }
        }
        earBrokenAt = System.currentTimeMillis()
        com.irvin.dacrohx.device.VoiceLog.add("Reconocedor de Android ocupado → oído de Gemma por 10 min")
        return null
    }

    private fun requestOnDeviceSpanish() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        try {
            val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            sr.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX"))
            viewModelScope.launch { kotlinx.coroutines.delay(3000); try { sr.destroy() } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private suspend fun listenRaw(onDevice: Boolean): String? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val sr = try {
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(app) else SpeechRecognizer.createSpeechRecognizer(app)
        } catch (e: Exception) {
            lastVoiceError = SpeechRecognizer.ERROR_CLIENT
            cont.resumeWith(Result.success(null)); return@suspendCancellableCoroutine
        }
        activeSr = sr
        var done = false
        var lastPartial = ""
        fun finish(t: String?) {
            if (done) return
            done = true
            if (activeSr === sr) activeSr = null
            try { sr.destroy() } catch (_: Exception) {}
            if (cont.isActive) cont.resumeWith(Result.success(t))
        }
        val tag = if (onDevice) "Voz (teléfono)" else "Voz (Google)"
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { com.irvin.dacrohx.device.VoiceLog.add("$tag: listo para escuchar") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                com.irvin.dacrohx.device.VoiceLog.add("$tag: error " + com.irvin.dacrohx.device.VoiceLog.errName(error) + (if (lastPartial.isNotBlank()) " (tenía: $lastPartial)" else ""))
                lastVoiceError = error
                finish(lastPartial.takeIf { it.isNotBlank() })
            }
            override fun onResults(results: Bundle?) {
                val r = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                com.irvin.dacrohx.device.VoiceLog.add("$tag: entendí \"" + (r ?: "") + "\"")
                finish(r?.takeIf { it.isNotBlank() } ?: lastPartial.takeIf { it.isNotBlank() })
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank()) { lastPartial = p; _partialSpeech.value = p }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        cont.invokeOnCancellation { viewModelScope.launch { finish(null) } }
        try { sr.startListening(recognizerIntent(onDevice)) } catch (e: Exception) {
            lastVoiceError = SpeechRecognizer.ERROR_CLIENT; finish(null)
        }
    }

    private fun recognizerIntent(onDevice: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice || !online.connected())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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

    /** Procesa una orden hablada. Devuelve true si Irvin quedó esperando respuesta (hizo una pregunta). */
    suspend fun handsFreeText(command: String): Boolean {
        // Órdenes simples (música, hora, alarmas, llamadas...) no necesitan al cerebro: al instante.
        if (genJob?.isActive != true) quickCommand(command)?.let { r ->
            if (r == "​") { waitSpeech(); return false }
            store.addChat(ChatMsg(role = "user", text = "🎙️ $command"))
            store.addChat(ChatMsg(role = "model", text = r, actions = listOf("⚡ instantáneo")))
            speaker.say(r, flush = true)
            waitSpeech()
            if (!uiVisible) { notifyReply(r); backgroundLaunchTip(r) }
            return expectsAnswer(r)
        }
        if (!brain.ready) {
            if (!models.isReady()) { speaker.say("Todavía no tengo cerebro instalado. Abre la app primero.", flush = true); return false }
            speaker.say("Dame un segundo.", flush = true)
            if (!ensureBrain()) { speaker.say("No pude encender mi cerebro.", flush = true); return false }
        }
        if (genJob?.isActive == true) return false
        micBusy = true
        try {
            val job = run(ChatMsg(role = "user", text = "🎙️ $command", kind = "voice"), listOf(Content.Text(command)), forceSpeak = true)
            job?.join()
            waitSpeech()
        } finally { micBusy = false }
        val reply = lastReplyText
        reply?.let { if (!uiVisible) notifyReply(it) }
        return expectsAnswer(reply ?: "")
    }

    private suspend fun waitSpeech() {
        kotlinx.coroutines.delay(150)
        kotlinx.coroutines.withTimeoutOrNull(90_000) { speaker.speaking.first { !it } }
    }

    /** ¿Irvin quedó esperando una respuesta? (terminó con pregunta o hay algo por elegir/confirmar) */
    private fun expectsAnswer(reply: String): Boolean {
        val end = reply.trimEnd().trimEnd { !it.isLetterOrDigit() && it != '?' && it != '.' && it != '!' }
        val r = end.endsWith("?") || _musicChoice.value != null || _pending.value != null
        com.irvin.dacrohx.device.VoiceLog.add(if (r) "Irvin preguntó → vuelvo a escuchar" else "Fin de la conversación")
        return r
    }

    @Volatile private var voiceLoop = false

    /**
     * Turno manos libres ("Oye Irvin", burbuja, widget): escucha, responde y, si Irvin
     * pregunta algo, vuelve a escuchar solo (modo conversación).
     */
    suspend fun quickVoiceTurn() {
        if (voiceLoop) return
        voiceLoop = true
        var misses = 0
        var turns = 0
        try {
            while (turns++ < 6) {
                holdMic(90_000)
                val text = hear()
                if (text.isNullOrBlank()) {
                    if (++misses >= 2) break
                    speaker.say("¿Me repites?", flush = true)
                    waitSpeech()
                    continue
                }
                playEnd()
                misses = 0
                holdMic(120_000) // mientras piensa y responde
                if (!handsFreeText(text)) break
                kotlinx.coroutines.delay(250) // deja que se libere el audio antes de volver a escuchar
            }
        } finally {
            voiceLoop = false
            releaseMic()
        }
    }

    /** Modo conversación en la app: tras una respuesta con pregunta, vuelve a abrir el micrófono. */
    private fun followUpInApp(reply: String) {
        if (!voiceTurn || !expectsAnswer(reply)) { voiceTurn = false; followMisses = 0; releaseMic(); return }
        holdMic()
        viewModelScope.launch {
            voiceJob?.join()
            waitSpeech()
            kotlinx.coroutines.delay(250)
            if (voiceTurn && genJob?.isActive != true && _mode.value != Mode.LISTENING) {
                _mode.value = Mode.IDLE
                toggleListening()
            }
        }
    }
    @Volatile private var voiceTurn = false
    private var followMisses = 0
    fun endVoiceTurn() { voiceTurn = false; followMisses = 0; releaseMic() }

    fun setBubble(on: Boolean) {
        val ctx = getApplication<Application>()
        if (on && !com.irvin.dacrohx.device.BubbleService.canDraw(ctx)) {
            com.irvin.dacrohx.device.BubbleService.askPermission(ctx)
            toast("Permite “Mostrar sobre otras apps” y vuelve a activar la burbuja.")
            return
        }
        store.updateSettings { it.copy(bubble = on) }
        if (on) com.irvin.dacrohx.device.BubbleService.start(ctx) else com.irvin.dacrohx.device.BubbleService.stop(ctx)
    }

    @Volatile private var lastReplyText: String? = null

    /**
     * Android no deja abrir apps desde segundo plano (YouTube, llamadas, alarmas) salvo que Irvin
     * tenga "Mostrar sobre otras apps". Si falta, lo explica una sola vez.
     */
    private fun backgroundLaunchTip(reply: String) {
        if (!Regex("^(Abriendo|Llamando|Listo, alarma|Temporizador|Reproduciendo|Listo, en)").containsMatchIn(reply)) return
        if (com.irvin.dacrohx.device.BubbleService.canDraw(app)) return
        val prefs = app.getSharedPreferences("irvin_tips", Context.MODE_PRIVATE)
        if (prefs.getBoolean("bg_launch", false)) return
        prefs.edit().putBoolean("bg_launch", true).apply()
        speaker.say("Por cierto: para que pueda abrir apps con la pantalla en otra cosa, activa la burbuja de Irvin en Ajustes. Así Android me deja abrirlas.")
    }

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
