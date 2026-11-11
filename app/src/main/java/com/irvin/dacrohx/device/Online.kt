package com.irvin.dacrohx.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Herramientas de internet de Irvin. Solo funcionan si hay conexión;
 * sin internet responden que no hay red y Irvin sigue offline.
 * No usan claves ni cuentas: fuentes públicas y gratuitas.
 */
class Online(private val ctx: Context) {

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 16) IrvinDacrohx/1.1"
        const val NO_NET = "SIN_INTERNET: el teléfono no tiene conexión ahora. Díselo al usuario y responde con lo que sepas sin inventar datos actuales."
    }

    fun connected(): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (e: Exception) {
        true // si no se puede saber, intentamos de todos modos
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, timeout: Int = 9000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "es-MX,es;q=0.9")
        try {
            if (c.responseCode >= 400) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { r ->
                val sb = StringBuilder()
                val buf = CharArray(8192)
                while (sb.length < 600_000) {
                    val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n)
                }
                sb.toString()
            }
        } finally { c.disconnect() }
    }

    private fun text(html: String) =
        Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+"), " ").trim()

    private inline fun guard(block: () -> String): String {
        if (!connected()) return NO_NET
        return try { block() } catch (e: Exception) { "Error de internet (${e.message}). Dilo al usuario; no inventes la respuesta." }
    }

    /** Búsqueda web (DuckDuckGo, sin cuenta). */
    fun search(q: String): String = guard {
        val html = get("https://html.duckduckgo.com/html/?q=${enc(q)}&kl=mx-es")
        val blocks = html.split("class=\"result__body").drop(1).take(5)
        val out = blocks.mapIndexedNotNull { i, b ->
            val title = Regex("class=\"result__a\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let(::text)
            val snip = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let(::text)
            var href = Regex("class=\"result__a\" href=\"([^\"]+)\"").find(b)?.groupValues?.get(1) ?: ""
            Regex("uddg=([^&]+)").find(href)?.let { href = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (title == null) null else "${i + 1}. $title — ${snip ?: ""} [$href]"
        }
        if (out.isEmpty()) wikipedia(q) else "Resultados de internet:\n" + out.joinToString("\n").take(1800)
    }

    /** Lee el texto principal de una página web. */
    fun readPage(url: String): String = guard {
        val u = if (url.startsWith("http")) url else "https://$url"
        var html = get(u)
        html = html.replace(Regex("(?is)<(script|style|nav|header|footer|noscript)[^>]*>.*?</\\1>"), " ")
        val body = Regex("(?is)<(article|main)[^>]*>(.*?)</\\1>").find(html)?.groupValues?.get(2) ?: html
        "Contenido de $u:\n" + text(body).take(2200)
    }

    /** Resumen de Wikipedia en español. */
    fun wikipedia(q: String): String = guard {
        val s = JSONObject(get("https://es.wikipedia.org/w/api.php?action=opensearch&format=json&limit=1&search=${enc(q)}").let { "{\"r\":$it}" })
            .getJSONArray("r")
        val titles = s.getJSONArray(1)
        if (titles.length() == 0) return@guard "No encontré '$q' en Wikipedia."
        val t = titles.getString(0)
        val j = JSONObject(get("https://es.wikipedia.org/api/rest_v1/page/summary/${enc(t.replace(' ', '_'))}"))
        "Wikipedia — $t: " + j.optString("extract").take(1500)
    }

    /** Clima actual y de los próximos días (Open-Meteo, sin cuenta). */
    fun weather(city: String): String = guard {
        val g = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=${enc(city)}&count=1&language=es"))
        val r = g.optJSONArray("results")?.optJSONObject(0) ?: return@guard "No encontré la ciudad '$city'."
        val lat = r.getDouble("latitude"); val lon = r.getDouble("longitude")
        val place = listOf(r.optString("name"), r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
        val w = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=3"
            )
        )
        val c = w.getJSONObject("current")
        val d = w.getJSONObject("daily")
        val days = (0 until 3).joinToString("; ") { i ->
            val label = when (i) { 0 -> "hoy"; 1 -> "mañana"; else -> "pasado mañana" }
            "$label ${code(d.getJSONArray("weather_code").getInt(i))}, " +
                "${d.getJSONArray("temperature_2m_min").getDouble(i).toInt()}–${d.getJSONArray("temperature_2m_max").getDouble(i).toInt()}°C, " +
                "lluvia ${d.getJSONArray("precipitation_probability_max").optInt(i)}%"
        }
        "Clima en $place ahora: ${c.getDouble("temperature_2m").toInt()}°C (sensación ${c.getDouble("apparent_temperature").toInt()}°C), " +
            "${code(c.getInt("weather_code"))}, humedad ${c.getInt("relative_humidity_2m")}%, viento ${c.getDouble("wind_speed_10m").toInt()} km/h. Pronóstico: $days."
    }

    private fun code(c: Int) = when (c) {
        0 -> "despejado"; 1, 2 -> "parcialmente nublado"; 3 -> "nublado"; 45, 48 -> "neblina"
        in 51..57 -> "llovizna"; in 61..67 -> "lluvia"; in 71..77 -> "nieve"; in 80..82 -> "chubascos"
        in 95..99 -> "tormenta eléctrica"; else -> "variable"
    }

    /** Titulares recientes (Google Noticias RSS, México). */
    fun news(topic: String): String = guard {
        val url = if (topic.isBlank() || topic.lowercase() in setOf("hoy", "general", "noticias", "principales"))
            "https://news.google.com/rss?hl=es-419&gl=MX&ceid=MX:es-419"
        else "https://news.google.com/rss/search?q=${enc(topic)}&hl=es-419&gl=MX&ceid=MX:es-419"
        val xml = get(url)
        val items = Regex("(?s)<item>(.*?)</item>").findAll(xml).take(6).map { m ->
            val it = m.groupValues[1]
            val t = Regex("(?s)<title>(.*?)</title>").find(it)?.groupValues?.get(1)?.let(::text) ?: ""
            val d = Regex("(?s)<pubDate>(.*?)</pubDate>").find(it)?.groupValues?.get(1)?.take(16) ?: ""
            "- $t ($d)"
        }.toList()
        if (items.isEmpty()) "No encontré noticias de '$topic'." else "Titulares (Google Noticias):\n" + items.joinToString("\n")
    }

    /** Tipo de cambio actual. */
    fun exchange(from: String, to: String): String = guard {
        val f = from.uppercase().take(3); val t = to.uppercase().take(3)
        val j = JSONObject(get("https://open.er-api.com/v6/latest/$f"))
        val rate = j.getJSONObject("rates").optDouble(t, Double.NaN)
        if (rate.isNaN()) "No tengo el tipo de cambio $f→$t."
        else "1 $f = ${"%.4f".format(rate)} $t (actualizado ${j.optString("time_last_update_utc").take(16)})."
    }
}
