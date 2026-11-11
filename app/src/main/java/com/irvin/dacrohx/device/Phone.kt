package com.irvin.dacrohx.device

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Contactos, llamadas, SMS, WhatsApp, alarmas, temporizadores y calendario. */
class Phone(private val ctx: Context) {

    data class Contact(val name: String, val number: String)

    private val es = Locale("es", "MX")

    fun has(perm: String) = ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Busca contactos por nombre aproximado ("mamá", "juan perez"). */
    fun findContacts(query: String): List<Contact> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val all = ArrayList<Contact>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    all += Contact(n, num)
                }
            }
        } catch (_: Exception) {}
        val words = q.split(" ")
        return all.map { it to norm(it.name) }
            .mapNotNull { (c, n) ->
                val score = when {
                    n == q -> 100
                    n.startsWith(q) -> 60
                    n.split(" ").any { it == q } -> 50
                    words.all { w -> n.contains(w) } -> 30
                    else -> 0
                }
                if (score > 0) c to score else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.number.filter { ch -> ch.isDigit() }.takeLast(10) }
            .take(5)
    }

    /** Devuelve el número si el texto ya es un teléfono. */
    fun asNumber(s: String): String? {
        val d = s.filter { it.isDigit() || it == '+' }
        return if (d.count { it.isDigit() } >= 7) d else null
    }

    fun resolve(who: String): Pair<Contact?, String?> {
        asNumber(who)?.let { return Contact(who, it) to null }
        if (!has(Manifest.permission.READ_CONTACTS)) return null to "Falta el permiso de contactos (Ajustes → Permisos)."
        val hits = findContacts(who)
        return when {
            hits.isEmpty() -> null to "No encontré a '$who' en tus contactos."
            hits.size > 1 && norm(hits[0].name) != norm(who) && hits.map { norm(it.name) }.distinct().size > 1 ->
                null to "Encontré varios: ${hits.joinToString(", ") { it.name }}. Pregunta a cuál."
            else -> hits[0] to null
        }
    }

    fun call(number: String): String {
        if (!has(Manifest.permission.CALL_PHONE)) {
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Abrí el marcador con el número (falta el permiso de llamar directo)."
        }
        ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Llamando."
    }

    fun sms(number: String, text: String): String {
        if (!has(Manifest.permission.SEND_SMS)) return "Falta el permiso de SMS (Ajustes → Permisos)."
        return try {
            val sm = ctx.getSystemService(SmsManager::class.java)
            val parts = sm.divideMessage(text)
            sm.sendMultipartTextMessage(number, null, parts, null, null)
            "SMS enviado."
        } catch (e: Exception) {
            "No pude enviar el SMS: ${e.message}"
        }
    }

    /** Abre WhatsApp con el mensaje escrito; el usuario solo toca enviar. */
    fun whatsapp(number: String, text: String): String {
        var digits = number.filter { it.isDigit() }
        if (digits.length == 10) digits = "52$digits" // México por defecto
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=" + Uri.encode(text)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                ctx.packageManager.getPackageInfo(pkg, 0)
                ctx.startActivity(Intent(i).setPackage(pkg))
                return "Abrí WhatsApp con el mensaje listo; solo falta tocar enviar."
            } catch (_: Exception) {}
        }
        return "No tienes WhatsApp instalado."
    }

    // ---------------------------------------------------------------- reloj
    fun alarm(hour: Int, minute: Int, label: String): String = try {
        ctx.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Irvin" })
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        "Alarma puesta a las %d:%02d en tu reloj.".format(hour, minute)
    } catch (e: Exception) { "No pude poner la alarma: ${e.message}" }

    fun timer(seconds: Int, label: String): String = try {
        ctx.startActivity(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Irvin" })
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val m = seconds / 60; val s = seconds % 60
        "Temporizador de " + (if (m > 0) "$m min " else "") + (if (s > 0) "$s s" else "") + " iniciado."
    } catch (e: Exception) { "No pude iniciar el temporizador: ${e.message}" }

    // ---------------------------------------------------------------- calendario
    fun agenda(day: String): String {
        if (!has(Manifest.permission.READ_CALENDAR)) return "Falta el permiso de calendario (Ajustes → Permisos)."
        val start = dayStart(day) ?: return "No entendí el día '$day'. Usa hoy, mañana o AAAA-MM-DD."
        val end = start + 24 * 3600_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start)
            android.content.ContentUris.appendId(it, end)
        }.build()
        val out = ArrayList<String>()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION),
                null, null, CalendarContract.Instances.BEGIN + " ASC",
            )?.use { c ->
                val f = SimpleDateFormat("h:mm a", es)
                while (c.moveToNext() && out.size < 15) {
                    val t = c.getString(0) ?: "(sin título)"
                    val allDay = c.getInt(2) == 1
                    val loc = c.getString(3)?.takeIf { it.isNotBlank() }?.let { " en $it" } ?: ""
                    out += "- " + (if (allDay) "todo el día" else f.format(Date(c.getLong(1)))) + ": $t$loc"
                }
            }
        } catch (e: Exception) { return "No pude leer el calendario: ${e.message}" }
        val label = SimpleDateFormat("EEEE d 'de' MMMM", es).format(Date(start))
        return if (out.isEmpty()) "No tienes nada en el calendario el $label." else "Agenda del $label:\n" + out.joinToString("\n")
    }

    fun addEvent(title: String, whenText: String, minutes: Int): String {
        if (!has(Manifest.permission.WRITE_CALENDAR)) return "Falta el permiso de calendario (Ajustes → Permisos)."
        val start = parseDateTime(whenText) ?: return "No entendí la fecha '$whenText'. Usa AAAA-MM-DD HH:MM."
        val calId = primaryCalendar() ?: return "No encontré un calendario en el teléfono."
        return try {
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, start + minutes.coerceIn(5, 24 * 60) * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            val id = uri?.lastPathSegment?.toLongOrNull()
            if (id != null) {
                ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, 30)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
            "Agendado: '$title' el " + SimpleDateFormat("EEEE d MMM, h:mm a", es).format(Date(start)) + " (aviso 30 min antes)."
        } catch (e: Exception) { "No pude agendar: ${e.message}" }
    }

    private fun primaryCalendar(): Long? {
        try {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
                null, null, null,
            )?.use { c ->
                var fallback: Long? = null
                while (c.moveToNext()) {
                    if (c.getInt(2) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    if (c.getInt(1) == 1) return c.getLong(0)
                    if (fallback == null) fallback = c.getLong(0)
                }
                return fallback
            }
        } catch (_: Exception) {}
        return null
    }

    private fun dayStart(day: String): Long? {
        val d = norm(day)
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        when {
            d.isEmpty() || d == "hoy" -> {}
            d == "manana" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            d == "pasado manana" -> cal.add(Calendar.DAY_OF_YEAR, 2)
            d == "ayer" -> cal.add(Calendar.DAY_OF_YEAR, -1)
            else -> {
                return try {
                    SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day.trim().take(10))?.time
                } catch (_: Exception) { null }
            }
        }
        return cal.timeInMillis
    }

    private fun parseDateTime(s: String): Long? {
        val clean = s.trim().replace("T", " ").take(16)
        for (p in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd H:mm")) {
            try { return SimpleDateFormat(p, Locale.US).apply { isLenient = false }.parse(clean)?.time } catch (_: Exception) {}
        }
        return null
    }
}
