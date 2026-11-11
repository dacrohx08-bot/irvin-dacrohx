package com.irvin.dacrohx.device

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** Graba la voz a 16 kHz mono para que Gemma la entienda directamente (sin internet). */
class VoiceRecorder {
    companion object {
        const val SAMPLE_RATE = 16000
        const val MAX_SECONDS = 30
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var running = false

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    var onAutoStop: (() -> Unit)? = null

    val isRecording: Boolean get() = running

    @Volatile private var vad = false
    @Volatile private var heardSpeech = false

    /**
     * Empieza a grabar. Con [stopOnSilence] se detiene sola cuando terminas de hablar
     * (o a los 6 s si no dijiste nada), ideal para el modo manos libres.
     */
    @SuppressLint("MissingPermission")
    fun start(stopOnSilence: Boolean = false): Boolean {
        if (running) return true
        vad = stopOnSilence
        heardSpeech = false
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096) * 2
            )
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        record = rec
        synchronized(pcm) { pcm.reset() }
        running = true
        rec.startRecording()
        thread = Thread {
            val buf = ByteArray(3200) // 100 ms
            val maxBytes = SAMPLE_RATE * 2 * (if (vad) 15 else MAX_SECONDS)
            var silenceMs = 0
            var totalMs = 0
            var noise = -1f
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(pcm) { pcm.write(buf, 0, n) }
                    var sum = 0.0
                    var i = 0
                    while (i + 1 < n) {
                        val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
                        sum += s * s
                        i += 2
                    }
                    val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
                    val lv = (rms / 6000.0).toFloat().coerceIn(0f, 1f)
                    _level.value = lv
                    val chunkMs = n * 1000 / (SAMPLE_RATE * 2)
                    totalMs += chunkMs
                    var autoStop = pcm.size() >= maxBytes
                    if (vad) {
                        // Mide el ruido de fondo (ventilador, ambiente) al inicio y se adapta.
                        if (totalMs <= 400) noise = if (noise < 0f) lv else (noise * 0.7f + lv * 0.3f)
                        val base = noise.coerceAtLeast(0.01f)
                        val talk = maxOf(0.045f, base * 2.6f)
                        val quiet = maxOf(0.03f, base * 1.7f)
                        if (lv > talk) { heardSpeech = true; silenceMs = 0 }
                        else if (lv < quiet || !heardSpeech) silenceMs += chunkMs
                        else silenceMs += chunkMs / 2
                        if (heardSpeech && silenceMs >= 1100) autoStop = true
                        if (!heardSpeech && totalMs >= 6000) autoStop = true
                        if (totalMs >= 12000) autoStop = true
                    }
                    if (autoStop) {
                        running = false
                        onAutoStop?.invoke()
                    }
                }
            }
        }.apply { name = "irvin-mic"; start() }
        return true
    }

    /** Detiene y devuelve el audio como WAV (o null si fue demasiado corto). */
    fun stop(): ByteArray? {
        running = false
        if (Thread.currentThread() !== thread) {
            try { thread?.join(500) } catch (_: Exception) {}
        }
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
        _level.value = 0f
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.size < SAMPLE_RATE * 2 / 2) return null // menos de medio segundo
        if (vad && !heardSpeech) return null
        return wav(data)
    }

    fun cancel() { stop() }

    private fun wav(pcmData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcmData.size + 44)
        fun i32(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun i16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + pcmData.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(SAMPLE_RATE); i32(SAMPLE_RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(pcmData.size); out.write(pcmData)
        return out.toByteArray()
    }
}

/** Voz de Irvin con el motor de Android (funciona offline con la voz en español descargada). */
class Speaker(ctx: Context) {
    private var ready = false
    private val pending = AtomicInteger(0)
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private var buffer = StringBuilder()
    var rate = 1.05f
    var pitch = 1.0f

    data class VoiceOption(val name: String, val label: String, val locale: String)
    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()
    private var wantedVoice: String = ""

    /** Voces en español instaladas que funcionan sin internet. */
    private fun loadVoices() {
        try {
            val list = tts.voices.orEmpty()
                .filter { it.locale.language == "es" && !it.isNetworkConnectionRequired && !it.features.contains("notInstalled") }
                .sortedWith(compareBy({ regionOrder(it.locale.country) }, { it.name }))
            var n = 0
            _voices.value = list.map { v ->
                n++
                val region = when (v.locale.country) {
                    "MX" -> "México"; "US" -> "Latino (EE.UU.)"; "ES" -> "España"; "AR" -> "Argentina"
                    "CO" -> "Colombia"; "" -> "Español"; else -> v.locale.country
                }
                VoiceOption(v.name, "Voz $n · $region", v.locale.toLanguageTag())
            }
        } catch (_: Exception) {}
    }

    private fun regionOrder(c: String) = when (c) { "MX" -> 0; "US" -> 1; "ES" -> 3; else -> 2 }

    fun setVoice(name: String) {
        wantedVoice = name
        if (!ready || name.isEmpty()) return
        try { tts.voices.orEmpty().firstOrNull { it.name == name }?.let { tts.voice = it } } catch (_: Exception) {}
    }

    fun refreshVoices() { if (ready) loadVoices() }

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                val mx = tts.setLanguage(Locale("es", "MX"))
                if (mx == TextToSpeech.LANG_MISSING_DATA || mx == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale("es"))
                }
                loadVoices()
                if (wantedVoice.isNotEmpty()) setVoice(wantedVoice)
                // Dice lo que quedó pendiente mientras la voz arrancaba.
                val queued = synchronized(early) { early.toList().also { early.clear() } }
                queued.forEach { say(it) }
            }
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) {
                if (pending.decrementAndGet() <= 0) { pending.set(0); _speaking.value = false }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onDone(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                pending.set(0); _speaking.value = false
            }
        })
    }

    private fun clean(s: String) = s
        .replace(Regex("[*_`#>|]"), "")
        .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
        .let { str ->
            val sb = StringBuilder()
            var i = 0
            while (i < str.length) {
                val cp = str.codePointAt(i)
                val type = Character.getType(cp)
                if (cp < 0x2600 || (type != Character.OTHER_SYMBOL.toInt() && cp < 0x1F000)) sb.appendCodePoint(cp)
                i += Character.charCount(cp)
            }
            sb.toString()
        }
        .trim()

    private val early = ArrayList<String>()

    fun say(text: String, flush: Boolean = false) {
        if (!ready) {
            synchronized(early) { if (flush) early.clear(); early += text }
            _speaking.value = true // para que quien espere sepa que hay algo por decir
            return
        }
        val t = clean(text)
        if (t.isEmpty()) return
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        pending.incrementAndGet()
        tts.speak(t, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    /** Habla por frases mientras el modelo sigue escribiendo (respuesta más rápida). */
    fun feed(delta: String) {
        buffer.append(delta)
        while (true) {
            val idx = buffer.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '\n' || it == ':' || it == ';' }
            if (idx < 0) break
            val sentence = buffer.substring(0, idx + 1)
            buffer.delete(0, idx + 1)
            if (sentence.isNotBlank()) say(sentence)
        }
        if (buffer.length > 220) { say(buffer.toString()); buffer.clear() }
    }

    fun finish() {
        if (buffer.isNotBlank()) say(buffer.toString())
        buffer.clear()
    }

    fun stop() {
        buffer.clear()
        pending.set(0)
        if (ready) tts.stop()
        _speaking.value = false
    }

    fun shutdown() { tts.shutdown() }
}
