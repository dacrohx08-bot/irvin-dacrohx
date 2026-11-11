package com.irvin.dacrohx.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Memory(val id: String, val text: String, val t: Long)

data class Reminder(
    val id: String,
    val text: String,
    val due: Long,
    val fired: Boolean = false,
    val done: Boolean = false,
)

data class ChatMsg(
    val id: String = newId(),
    val role: String, // "user" | "model" | "sys"
    val text: String,
    val t: Long = System.currentTimeMillis(),
    val kind: String = "text", // "text" | "voice" | "image"
    val image: String? = null, // ruta local de la imagen adjunta
    val actions: List<String> = emptyList(),
    val pending: Boolean = false,
    val error: Boolean = false,
)

data class Settings(
    val name: String = "",
    val persona: String = PERSONAS[0].second,
    val autoSpeak: Boolean = true,
    val speechRate: Float = 1.05f,
    val tvBrand: String = "samsung",
    val wakeWord: Boolean = false,
    val voiceName: String = "",
    val pitch: Float = 1.0f,
    val fastVoice: Boolean = true,
    val turbo: Boolean = false,
    val useInternet: Boolean = true,
    val bubble: Boolean = false,
    val biometric: Boolean = false,
    val cloudKey: String = "",
    val cloudModel: String = "claude-sonnet-5-5",
)

val PERSONAS = listOf(
    "Mayordomo" to "Elegante, leal y con humor seco, como un mayordomo digital de película. Trata al usuario con respeto ('señor') pero con confianza.",
    "Compa mexicano" to "Relajado, como un compa de confianza de México. Usa expresiones mexicanas naturales, bromea, pero va al grano.",
    "Coach" to "Motivador y enérgico. Empuja al usuario a cumplir sus metas, celebra sus logros y no le deja poner excusas.",
    "Directo" to "Ultra conciso y técnico. Cero relleno, solo la respuesta.",
)

fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

/** Almacenamiento local (sin internet) en archivos JSON dentro de la app. */
class Store private constructor(private val ctx: Context) {

    private val dir = File(ctx.filesDir, "irvin").apply { mkdirs() }
    private val prefs: SharedPreferences = ctx.getSharedPreferences("irvin", Context.MODE_PRIVATE)
    private val lock = Any()

    private val _memories = MutableStateFlow(loadMemories())
    val memories: StateFlow<List<Memory>> = _memories.asStateFlow()

    private val _reminders = MutableStateFlow(loadReminders())
    val reminders: StateFlow<List<Reminder>> = _reminders.asStateFlow()

    private val _chat = MutableStateFlow(loadChat())
    val chat: StateFlow<List<ChatMsg>> = _chat.asStateFlow()

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val imagesDir: File get() = File(dir, "img").apply { mkdirs() }

    // ---------------- memorias ----------------
    fun addMemory(text: String): Memory {
        val m = Memory(newId(), text.trim().take(400), System.currentTimeMillis())
        synchronized(lock) {
            _memories.value = (_memories.value + m).takeLast(500)
            saveMemories()
        }
        return m
    }

    fun deleteMemory(id: String) = synchronized(lock) {
        _memories.value = _memories.value.filterNot { it.id == id }
        saveMemories()
    }

    /** Borra recuerdos que contengan la palabra clave. Devuelve cuántos borró. */
    fun forgetMatching(keyword: String): Int = synchronized(lock) {
        val k = keyword.trim().lowercase()
        if (k.isEmpty()) return 0
        val before = _memories.value.size
        _memories.value = _memories.value.filterNot { it.text.lowercase().contains(k) }
        saveMemories()
        before - _memories.value.size
    }

    // ---------------- recordatorios ----------------
    fun addReminder(text: String, due: Long): Reminder {
        val r = Reminder(newId(), text.trim().take(300), due)
        synchronized(lock) {
            _reminders.value = _reminders.value + r
            saveReminders()
        }
        return r
    }

    fun updateReminder(id: String, f: (Reminder) -> Reminder) = synchronized(lock) {
        _reminders.value = _reminders.value.map { if (it.id == id) f(it) else it }
        saveReminders()
    }

    fun deleteReminder(id: String) = synchronized(lock) {
        _reminders.value = _reminders.value.filterNot { it.id == id }
        saveReminders()
    }

    fun reminder(id: String): Reminder? = _reminders.value.firstOrNull { it.id == id }

    fun reloadReminders() = synchronized(lock) { _reminders.value = loadReminders() }

    // ---------------- chat ----------------
    fun addChat(m: ChatMsg) = synchronized(lock) {
        _chat.value = (_chat.value + m).takeLast(300)
        if (!m.pending) saveChat()
    }

    fun updateChat(id: String, persist: Boolean = false, f: (ChatMsg) -> ChatMsg) = synchronized(lock) {
        _chat.value = _chat.value.map { if (it.id == id) f(it) else it }
        if (persist) saveChat()
    }

    fun clearChat() = synchronized(lock) {
        _chat.value = emptyList()
        saveChat()
        imagesDir.listFiles()?.forEach { it.delete() }
    }

    // ---------------- ajustes ----------------
    fun updateSettings(f: (Settings) -> Settings) = synchronized(lock) {
        val s = f(_settings.value)
        _settings.value = s
        prefs.edit()
            .putString("name", s.name)
            .putString("persona", s.persona)
            .putBoolean("autoSpeak", s.autoSpeak)
            .putFloat("speechRate", s.speechRate)
            .putString("tvBrand", s.tvBrand)
            .putBoolean("wakeWord", s.wakeWord)
            .putString("voiceName", s.voiceName)
            .putFloat("pitch", s.pitch)
            .putBoolean("fastVoice", s.fastVoice)
            .putBoolean("turbo", s.turbo)
            .putBoolean("useInternet", s.useInternet)
            .putBoolean("bubble", s.bubble)
            .putBoolean("biometric", s.biometric)
            .putString("cloudKey", s.cloudKey)
            .putString("cloudModel", s.cloudModel)
            .apply()
    }

    private fun loadSettings() = Settings(
        name = prefs.getString("name", "") ?: "",
        persona = prefs.getString("persona", PERSONAS[0].second) ?: PERSONAS[0].second,
        autoSpeak = prefs.getBoolean("autoSpeak", true),
        speechRate = prefs.getFloat("speechRate", 1.05f),
        tvBrand = prefs.getString("tvBrand", "samsung") ?: "samsung",
        wakeWord = prefs.getBoolean("wakeWord", false),
        voiceName = prefs.getString("voiceName", "") ?: "",
        pitch = prefs.getFloat("pitch", 1.0f),
        fastVoice = prefs.getBoolean("fastVoice", true),
        turbo = prefs.getBoolean("turbo", false),
        useInternet = prefs.getBoolean("useInternet", true),
        bubble = prefs.getBoolean("bubble", false),
        biometric = prefs.getBoolean("biometric", false),
        cloudKey = prefs.getString("cloudKey", "") ?: "",
        cloudModel = prefs.getString("cloudModel", "claude-sonnet-5-5") ?: "claude-sonnet-5-5",
    )

    // ---------------- persistencia JSON ----------------
    private fun file(name: String) = File(dir, name)

    private fun writeAtomic(name: String, content: String) {
        val tmp = file("$name.tmp")
        tmp.writeText(content)
        tmp.renameTo(file(name))
    }

    private fun readArray(name: String): JSONArray = try {
        val f = file(name)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    private fun saveMemories() = writeAtomic("memories.json", JSONArray().apply {
        _memories.value.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("t", it.t)) }
    }.toString())

    private fun loadMemories(): List<Memory> {
        val a = readArray("memories.json")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Memory(o.getString("id"), o.getString("text"), o.optLong("t"))
        }
    }

    private fun saveReminders() = writeAtomic("reminders.json", JSONArray().apply {
        _reminders.value.forEach {
            put(
                JSONObject().put("id", it.id).put("text", it.text).put("due", it.due)
                    .put("fired", it.fired).put("done", it.done)
            )
        }
    }.toString())

    private fun loadReminders(): List<Reminder> {
        val a = readArray("reminders.json")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Reminder(
                o.getString("id"), o.getString("text"), o.getLong("due"),
                o.optBoolean("fired"), o.optBoolean("done")
            )
        }
    }

    private fun saveChat() = writeAtomic("chat.json", JSONArray().apply {
        _chat.value.filterNot { it.pending }.forEach { m ->
            put(
                JSONObject().put("id", m.id).put("role", m.role).put("text", m.text).put("t", m.t)
                    .put("kind", m.kind).put("image", m.image ?: "").put("error", m.error)
                    .put("actions", JSONArray(m.actions))
            )
        }
    }.toString())

    private fun loadChat(): List<ChatMsg> {
        val a = readArray("chat.json")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val acts = o.optJSONArray("actions")
            ChatMsg(
                id = o.getString("id"),
                role = o.getString("role"),
                text = o.optString("text"),
                t = o.optLong("t"),
                kind = o.optString("kind", "text"),
                image = o.optString("image").ifEmpty { null },
                actions = if (acts == null) emptyList() else (0 until acts.length()).map { i -> acts.getString(i) },
                error = o.optBoolean("error"),
            )
        }
    }

    companion object {
        @Volatile
        private var instance: Store? = null
        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext).also { instance = it }
        }
    }
}
