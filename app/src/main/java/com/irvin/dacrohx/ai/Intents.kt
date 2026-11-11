package com.irvin.dacrohx.ai

import com.irvin.dacrohx.device.Phone
import com.irvin.dacrohx.device.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Órdenes "tipo Siri" que se resuelven al instante, sin pensar con Gemma:
 * alarmas, temporizadores, recordatorios, llamadas, abrir apps, clima, cuentas, dólar y volumen.
 * Si una frase no encaja con certeza, devuelve null y la responde el cerebro.
 */
class Intents(private val a: Assistant) {

    private val es = Locale("es", "MX")

    /** Contactos entre los que hay que elegir después de "llama a Juan" (varios Juanes). */
    private var pick: List<Phone.Contact> = emptyList()
    private var pickAt = 0L

    // ------------------------------------------------------------------ números
    private val words = mapOf(
        "un" to 1, "uno" to 1, "una" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
        "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13,
        "catorce" to 14, "quince" to 15, "dieciseis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19,
        "veinte" to 20, "veinticinco" to 25, "treinta" to 30, "cuarenta" to 40, "cuarenta y cinco" to 45,
        "cincuenta" to 50, "sesenta" to 60, "noventa" to 90, "cien" to 100,
    )
    private val numRx = "(\\d+|" + words.keys.sortedByDescending { it.length }.joinToString("|") + ")"
    private fun num(s: String?): Int? = s?.trim()?.let { it.toIntOrNull() ?: words[it] }

    /** "5 minutos", "1 hora y 20 minutos", "media hora", "30 segundos" → segundos. */
    private fun duration(s: String): Int? {
        var t = s.trim()
        if (Regex("^(una )?media hora$").matches(t)) return 1800
        if (Regex("^(un )?cuarto de hora$").matches(t)) return 900
        var total = 0
        var found = false
        val rx = Regex("\\b$numRx\\s*(horas?|hrs?|h|minutos?|mins?|segundos?|segs?|s)\\b( y media)?")
        rx.findAll(t).forEach { m ->
            val n = num(m.groupValues[1]) ?: return@forEach
            val unit = m.groupValues[2]
            found = true
            total += when {
                unit.startsWith("h") -> n * 3600 + (if (m.groupValues[3].isNotEmpty()) 1800 else 0)
                unit.startsWith("m") -> n * 60 + (if (m.groupValues[3].isNotEmpty()) 30 else 0)
                else -> n
            }
            t = t.replace(m.value, " ")
        }
        if (Regex("\\by media\\b").containsMatchIn(t) && found && total % 3600 == 0) total += 1800
        if (!found) return null
        // Que no quede texto extraño ("5 minutos de pasta" no es solo una duración).
        val rest = t.replace(Regex("\\b(y|de|con)\\b"), " ").trim()
        return if (rest.isEmpty()) total.takeIf { it > 0 } else null
    }

    private fun durationText(sec: Int): String {
        val h = sec / 3600; val m = sec % 3600 / 60; val s = sec % 60
        return listOfNotNull(
            if (h > 0) "$h " + (if (h == 1) "hora" else "horas") else null,
            if (m > 0) "$m " + (if (m == 1) "minuto" else "minutos") else null,
            if (s > 0) "$s " + (if (s == 1) "segundo" else "segundos") else null,
        ).joinToString(" y ")
    }

    data class Clock(val h: Int, val m: Int, val explicit: Boolean)

    /** "7", "7 30", "7 y media", "7 de la tarde", "19 00", "siete y cuarto pm"... */
    private val timeRx = Regex(
        "^(?:las |la )?$numRx(?:(?: |:)(\\d{2}))?( y media| y cuarto| menos cuarto| en punto)?" +
            "( de la manana| de la madrugada| de la tarde| de la noche| del mediodia| am| pm| a m| p m)?$"
    )

    private fun clock(s: String): Clock? {
        val m = timeRx.find(s.trim()) ?: return null
        var h = num(m.groupValues[1]) ?: return null
        var min = m.groupValues[2].toIntOrNull() ?: 0
        when (m.groupValues[3].trim()) {
            "y media" -> min = 30
            "y cuarto" -> min = 15
            "menos cuarto" -> { min = 45; h -= 1 }
        }
        val mer = m.groupValues[4].trim()
        if (h !in 0..23 || min !in 0..59) return null
        val explicit = mer.isNotEmpty() || h >= 13 || h == 0
        when {
            mer in setOf("de la tarde", "de la noche", "pm", "p m") && h < 12 -> h += 12
            mer in setOf("de la manana", "de la madrugada", "am", "a m") && h == 12 -> h = 0
            mer == "del mediodia" && h < 12 && h != 12 -> h += 12
        }
        return Clock(h % 24, min, explicit)
    }

    /** Próxima vez que el reloj marque esa hora (si no dijo mañana/tarde, la más cercana). */
    private fun nextTime(c: Clock, tomorrow: Boolean = false, preferMorning: Boolean = false): Long {
        val now = Calendar.getInstance()
        fun at(h: Int): Calendar = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, c.m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (tomorrow) add(Calendar.DAY_OF_MONTH, 1)
        }
        val candidates = if (c.explicit || c.h >= 12 || preferMorning) listOf(at(c.h)) else listOf(at(c.h), at(c.h + 12))
        val future = candidates.map { cal -> if (!tomorrow && cal.timeInMillis <= now.timeInMillis) cal.apply { add(Calendar.DAY_OF_MONTH, 1) } else cal }
        return future.minOf { it.timeInMillis }
    }

    private fun say(t: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val day = when (cal.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)) {
            0 -> "hoy"; 1, -364, -365 -> "mañana"
            else -> SimpleDateFormat("EEEE d", es).format(Date(t))
        }
        val h = cal.get(Calendar.HOUR_OF_DAY); val m = cal.get(Calendar.MINUTE)
        val h12 = if (h % 12 == 0) 12 else h % 12
        val part = when (h) { in 0..5 -> "de la madrugada"; in 6..11 -> "de la mañana"; in 12..18 -> "de la tarde"; else -> "de la noche" }
        return "$day a las $h12:%02d $part".format(m)
    }

    // ------------------------------------------------------------------ entrada
    /**
     * [x]: frase normalizada (minúsculas, sin acentos ni signos). [y]: minúsculas con acentos (para guardar textos).
     */
    suspend fun handle(x: String, y: String): String? {
        val s = x.replace(Regex("^(oye irvin|irvin|oye|por favor)\\s+"), "").replace(Regex("\\s+por favor$"), "")
        val sy = y.replace(Regex("^(oye irvin|irvin|oye|por favor)[,\\s]+"), "")
        return pickContact(s)
            ?: alarm(s) ?: timer(s) ?: reminder(s, sy) ?: call(s) ?: openApp(s)
            ?: volume(s) ?: weather(s) ?: dollar(s) ?: math(s)
    }

    // ------------------------------------------------------------------ alarma
    private fun alarm(s: String): String? {
        var tomorrow = false
        var morning = false
        val t = Regex("^(?:pon(?:me|le)?|crea(?:me)?|programa(?:me)?|ajusta|activa|configura) (?:una |la |mi )?alarma (?:para |a )?(manana )?(?:a )?(.+)$").find(s)?.let {
            tomorrow = it.groupValues[1].isNotEmpty(); it.groupValues[2]
        } ?: Regex("^alarma (?:para |a )?(manana )?(?:a )?(.+)$").find(s)?.let {
            tomorrow = it.groupValues[1].isNotEmpty(); it.groupValues[2]
        } ?: Regex("^(?:despiertame|levantame) (?:manana )?(?:a )?(.+)$").find(s)?.let {
            morning = true; tomorrow = "manana" in s; it.groupValues[1]
        } ?: return null
        val c = clock(t.removePrefix("las ").let { "las $it" }) ?: clock(t) ?: return null
        val due = nextTime(c, tomorrow, morning)
        val cal = Calendar.getInstance().apply { timeInMillis = due }
        val r = a.phone.alarm(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), "Irvin")
        return if (r.startsWith("No pude")) r else "Listo, alarma para ${say(due)}."
    }

    // ------------------------------------------------------------------ temporizador
    private fun timer(s: String): String? {
        val m = Regex("^(?:pon(?:me|le)?|inicia|programa|crea|activa)? ?(?:un |el )?(?:temporizador|timer|cronometro|cuenta regresiva)(?: de| por| para| a)? (.+)$").find(s)
            ?: Regex("^(.+?) de (?:temporizador|timer)$").find(s)
            ?: return null
        val sec = duration(m.groupValues[1]) ?: return null
        if (sec > 24 * 3600) return null
        val r = a.phone.timer(sec, "Irvin")
        return if (r.startsWith("No pude")) r else "Temporizador de ${durationText(sec)}, empezando ya."
    }

    // ------------------------------------------------------------------ recordatorio
    private fun reminder(s: String, y: String): String? {
        if (!Regex("^(recuerdame|avisame|recordarme)\\b").containsMatchIn(s)) return null
        // Texto con acentos para guardarlo bonito.
        val yy = y.replace(Regex("[¿¡?!.,;]"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(recu[eé]rdame|av[ií]same|recordarme)\\s+", RegexOption.IGNORE_CASE), "")
        fun clean(t: String) = t.trim().replace(Regex("^(que|de|para)\\s+"), "").replaceFirstChar { it.uppercase() }
        fun make(what: String, due: Long): String {
            val r = a.store.addReminder(clean(what).ifBlank { "Tu aviso de Irvin" }, due)
            ReminderScheduler.schedule(a.ctx, r.id, due)
            return "Listo, te lo recuerdo ${say(due)}."
        }
        val w = yy.split(" ")
        // "recuérdame en 20 minutos sacar la ropa"
        if (w.firstOrNull() == "en") {
            for (cut in minOf(6, w.size) downTo 2) {
                val sec = duration(norm(w.subList(1, cut).joinToString(" "))) ?: continue
                return make(w.drop(cut).joinToString(" "), System.currentTimeMillis() + sec * 1000L)
            }
        }
        // "recuérdame sacar la ropa en 20 minutos"
        Regex("^(.+) en (.+)$").find(yy)?.let { m ->
            val sec = duration(norm(m.groupValues[2])) ?: return@let
            return make(m.groupValues[1], System.currentTimeMillis() + sec * 1000L)
        }
        // "recuérdame llamar al doctor mañana a las 8"
        Regex("^(.+?) (mañana |hoy )?(?:a las|a la) (.+)$").find(yy)?.let { m ->
            val c = clock(norm(m.groupValues[3])) ?: return@let
            return make(m.groupValues[1], nextTime(c, tomorrow = m.groupValues[2].trim() == "mañana"))
        }
        // "recuérdame mañana a las 8 de la noche sacar la basura"
        Regex("^(mañana |hoy )?(?:a las|a la) (.+)$").find(yy)?.let { m ->
            val ws = m.groupValues[2].split(" ")
            for (cut in minOf(5, ws.size - 1) downTo 1) {
                val c = clock(norm(ws.take(cut).joinToString(" "))) ?: continue
                return make(ws.drop(cut).joinToString(" "), nextTime(c, tomorrow = m.groupValues[1].trim() == "mañana"))
            }
        }
        return null
    }

    // ------------------------------------------------------------------ llamar
    private fun call(s: String): String? {
        val m = Regex("^(?:llama(?:le)?|marca(?:le)?|haz una llamada|hazle una llamada|marcar|llamar) (?:a |al |a la )?(.+)$").find(s) ?: return null
        val who = m.groupValues[1].replace(Regex("^(mi |el |la )"), "").replace(Regex("\\s+(por favor|ahorita|ahora)$"), "").trim()
        if (who.isEmpty() || who.length > 40) return null
        a.phone.asNumber(who)?.let { return a.phone.call(it).let { r -> if (r == "Llamando.") "Llamando al $who." else r } }
        val hits = a.phone.findContacts(who)
        if (hits.isEmpty()) return if (!a.phone.has(android.Manifest.permission.READ_CONTACTS)) "Necesito permiso de contactos: actívalo en Ajustes, Permisos." else "No encontré a $who en tus contactos."
        val exact = hits.firstOrNull { norm(it.name) == who }
        val distinct = hits.distinctBy { norm(it.name) }
        if (exact == null && distinct.size > 1) {
            pick = distinct.take(4); pickAt = System.currentTimeMillis()
            return "Tengo a " + pick.joinToString(", ") { it.name }.replace(Regex(", ([^,]+)$"), " y $1") + ". ¿A cuál llamo?"
        }
        val c = exact ?: hits[0]
        val r = a.phone.call(c.number)
        return if (r == "Llamando.") "Llamando a ${c.name}." else r
    }

    private fun pickContact(s: String): String? {
        if (pick.isEmpty()) return null
        if (System.currentTimeMillis() - pickAt > 90_000) { pick = emptyList(); return null }
        val t = s.replace(Regex("^(a |al |a la |llama a |con |el |la )"), "").trim()
        if (Regex("^(ninguno|ninguna|cancela|no|olvidalo|dejalo)").containsMatchIn(t)) { pick = emptyList(); return "Ok, no llamo." }
        val ordinal = mapOf("primero" to 0, "primera" to 0, "segundo" to 1, "segunda" to 1, "tercero" to 2, "tercera" to 2, "cuarto" to 3, "cuarta" to 3)
        val c = ordinal.entries.firstOrNull { t.contains(it.key) }?.let { pick.getOrNull(it.value) }
            ?: pick.maxByOrNull { cand -> norm(cand.name).split(" ").count { w -> w.length > 2 && t.contains(w) } }
                ?.takeIf { cand -> norm(cand.name).split(" ").any { w -> w.length > 2 && t.contains(w) } }
            ?: return null
        pick = emptyList()
        val r = a.phone.call(c.number)
        return if (r == "Llamando.") "Llamando a ${c.name}." else r
    }

    // ------------------------------------------------------------------ abrir apps
    private fun openApp(s: String): String? {
        val m = Regex("^(?:abre(?:me)?|abrir|inicia|lanza|entra a|ve a|pon(?:me)? la app(?: de)?) (?:la app(?: de)? |la aplicacion(?: de)? |el |la |mi )?(.+)$").find(s) ?: return null
        val name = m.groupValues[1].trim()
        if (name.isEmpty() || name.split(" ").size > 3 || " y " in " $name ") return null
        if (Regex("^(puerta|ventana|cortina|musica|cancion|mensaje|mensajes|notificaciones|correo)").containsMatchIn(name)) return null
        val r = a.device.openApp(name)
        return r.takeIf { it.startsWith("Abriendo") } // si no existe, que lo piense el cerebro
    }

    // ------------------------------------------------------------------ volumen
    private fun volume(s: String): String? {
        Regex("^(?:pon(?:me|le)? |sube(?:le)? |baja(?:le)? |ajusta )?(?:el )?volumen (?:al|a|en) $numRx(?: por ciento| %)?$").find(s)?.let {
            val v = num(it.groupValues[1]) ?: return null
            return "Volumen al ${a.music.setVolume(v.coerceIn(0, 100))}%."
        }
        if (Regex("^(?:pon(?:le)? |sube(?:le)? )?(?:el )?volumen (?:al )?(?:maximo|a todo|full|tope)$").matches(s)) return "Volumen al ${a.music.setVolume(100)}%."
        if (Regex("^(silencia(?:lo)?|silencio total|quita(?:le)? el volumen|sin volumen|volumen (?:a |en |al )?(?:cero|0))$").matches(s)) { a.music.setVolume(0); return "Silenciado." }
        if (Regex("^(sube(?:le)? (?:el |al )?volumen|mas volumen|mas fuerte|subele)( un poco| mas)?$").matches(s)) return "Volumen al ${a.music.setVolume(a.music.volume() + 15)}%."
        if (Regex("^(baja(?:le)? (?:el |al )?volumen|menos volumen|mas bajito|mas bajo|bajale)( un poco| mas)?$").matches(s)) return "Volumen al ${a.music.setVolume(a.music.volume() - 15)}%."
        return null
    }

    // ------------------------------------------------------------------ clima
    private suspend fun weather(s: String): String? {
        val m = Regex("^(?:(?:como|que tal) (?:esta|estara|va a estar) |dime |cual es |que )?(?:el )?(?:clima|tiempo|temperatura)(?: hace)?(?: hoy| de hoy| ahorita| ahora| para hoy)?(?: (?:en|de) (.+))?$").find(s)
            ?: Regex("^(?:va a llover|llovera|hace frio|hace calor|que temperatura hace|cuantos grados hace|a cuantos grados estamos)(?: hoy| ahorita| manana)?(?: en (.+))?$").find(s)
            ?: return null
        if (!a.store.settings.value.useInternet) return null
        val city = m.groupValues.last().trim()
        return withContext(Dispatchers.IO) {
            if (!a.online.connected()) return@withContext "No tengo internet ahorita para ver el clima."
            val c = city.ifEmpty { a.online.myCity() ?: "" }
            if (c.isEmpty()) return@withContext null
            a.online.weatherBrief(c).takeIf { !it.startsWith("Error") && !it.startsWith("SIN_INTERNET") && !it.startsWith("No encontré") }
        }
    }

    // ------------------------------------------------------------------ dólar
    private suspend fun dollar(s: String): String? {
        val eur = Regex("\\beuro").containsMatchIn(s)
        if (!Regex("^(?:a como|a cuanto|como|cuanto|en cuanto|cual es el precio del|precio del|tipo de cambio del?|cotizacion del)( esta| anda| se cotiza| cuesta| vale)? ?(?:el )?(dolar|euro)( hoy| ahorita)?$").matches(s) &&
            !Regex("^(?:tipo de cambio|precio del (dolar|euro)|el (dolar|euro) hoy)$").matches(s)) return null
        if (!a.store.settings.value.useInternet) return null
        return withContext(Dispatchers.IO) {
            val r = a.online.exchange(if (eur) "EUR" else "USD", "MXN")
            val v = Regex("= ([0-9.]+) MXN").find(r)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@withContext null
            "El ${if (eur) "euro" else "dólar"} está en ${"%.2f".format(v)} pesos."
        }
    }

    // ------------------------------------------------------------------ cuentas
    private fun math(s: String): String? {
        val m = Regex("^(?:cuanto (?:es|son|da)|calcula|cuanto seria|cuanto sale) (.+)$").find(s) ?: return null
        var e = " " + m.groupValues[1] + " "
        e = e.replace(Regex(" (\\d+(?:\\.\\d+)?) (?:por ciento|%) de (\\d+(?:\\.\\d+)?) "), " ($1/100*$2) ")
            .replace(Regex("(\\d) por ciento"), "$1/100")
            .replace(" multiplicado por ", " * ").replace(" por ", " * ").replace(" x ", " * ").replace(" veces ", " * ")
            .replace(" dividido entre ", " / ").replace(" dividido por ", " / ").replace(" entre ", " / ")
            .replace(" mas ", " + ").replace(" menos ", " - ")
            .replace(" al cuadrado ", " ^2 ").replace(" al cubo ", " ^3 ")
            .replace(Regex(" la raiz (?:cuadrada )?de (\\d+(?:\\.\\d+)?) "), " sqrt($1) ")
        words.forEach { (w, n) -> e = e.replace(Regex("\\b$w\\b"), n.toString()) }
        e = e.trim()
        if (!Regex("^[0-9+\\-*/^(). sqrt]+$").matches(e) || !e.any { it.isDigit() } || !Regex("[+\\-*/^]|sqrt").containsMatchIn(e)) return null
        val r = a.device.calculate(e)
        val v = Regex("= *(-?[0-9.,]+)").find(r)?.groupValues?.get(1) ?: r.takeIf { Regex("^-?[0-9.,]+$").matches(it.trim()) } ?: return null
        return "Son $v."
    }

    private fun norm(t: String) = java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
}
