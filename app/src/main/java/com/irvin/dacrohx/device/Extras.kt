package com.irvin.dacrohx.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.irvin.dacrohx.data.Store
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ======================================================================== RESPALDO
/** Exporta e importa todo lo que Irvin sabe de ti en un archivo .json. */
object Backup {
    fun export(ctx: Context, uri: Uri): String = try {
        val dir = File(ctx.filesDir, "irvin")
        val root = JSONObject()
            .put("app", "Irvin Dacrohx").put("version", 1).put("fecha", System.currentTimeMillis())
        for (name in listOf("memories", "reminders", "chat")) {
            val f = File(dir, "$name.json")
            root.put(name, if (f.exists()) JSONArray(f.readText()) else JSONArray())
        }
        val prefs = ctx.getSharedPreferences("irvin", Context.MODE_PRIVATE).all
        val p = JSONObject()
        prefs.forEach { (k, v) -> if (k != "cloudKey") p.put(k, v) } // la clave de la nube no se exporta
        root.put("settings", p)
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(root.toString(2).toByteArray()) }
        val m = root.getJSONArray("memories").length()
        "Respaldo guardado ($m recuerdos)."
    } catch (e: Exception) { "No pude guardar el respaldo: ${e.message}" }

    fun import(ctx: Context, uri: Uri): String = try {
        val txt = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(txt)
        if (root.optString("app") != "Irvin Dacrohx") throw IllegalArgumentException("ese archivo no es un respaldo de Irvin")
        val dir = File(ctx.filesDir, "irvin").apply { mkdirs() }
        for (name in listOf("memories", "reminders", "chat")) {
            root.optJSONArray(name)?.let { File(dir, "$name.json").writeText(it.toString()) }
        }
        root.optJSONObject("settings")?.let { s ->
            val e = ctx.getSharedPreferences("irvin", Context.MODE_PRIVATE).edit()
            s.keys().forEach { k ->
                when (val v = s.get(k)) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> if (k == "speechRate" || k == "pitch") e.putFloat(k, v.toFloat()) else e.putInt(k, v)
                    is Double -> e.putFloat(k, v.toFloat())
                    is String -> e.putString(k, v)
                }
            }
            e.apply()
        }
        "Respaldo restaurado. Cierra y vuelve a abrir Irvin para verlo todo."
    } catch (e: Exception) { "No pude restaurar: ${e.message}" }
}

// ======================================================================== DOCUMENTOS
/** Lee PDFs y archivos de texto para que Irvin los resuma o conteste preguntas. */
object Docs {
    data class Doc(val name: String, val text: String, val pages: List<Bitmap>)

    fun name(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "documento"
    } catch (_: Exception) { "documento" }

    fun read(ctx: Context, uri: Uri): Doc {
        val n = name(ctx, uri)
        val mime = ctx.contentResolver.getType(uri) ?: ""
        if (mime.contains("pdf") || n.lowercase().endsWith(".pdf")) return readPdf(ctx, uri, n)
        val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        return Doc(n, text.take(6000), emptyList())
    }

    private fun readPdf(ctx: Context, uri: Uri, n: String): Doc {
        val tmp = File(ctx.cacheDir, "doc.pdf")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        val pfd = android.os.ParcelFileDescriptor.open(tmp, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { r ->
            val sb = StringBuilder()
            val images = ArrayList<Bitmap>()
            for (i in 0 until minOf(r.pageCount, 40)) {
                r.openPage(i).use { page ->
                    var gotText = false
                    if (Build.VERSION.SDK_INT >= 35) {
                        try {
                            val t = page.textContents.joinToString("\n") { it.text }
                            if (t.isNotBlank()) { sb.append("\n[Página ${i + 1}]\n").append(t); gotText = true }
                        } catch (_: Throwable) {}
                    }
                    // Si la página es una imagen (escaneo), se la mostramos a la visión de Gemma.
                    if (!gotText && images.size < 2) {
                        val scale = 1024f / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        images += bmp
                    }
                }
                if (sb.length > 6000) break
            }
            return Doc("$n (${r.pageCount} págs.)", sb.toString().take(6000), images)
        }
    }
}

// ======================================================================== NUBE
/** Cerebro extra opcional: consulta un modelo más potente en la nube (necesita clave e internet). */
class Cloud(private val store: Store) {
    fun ask(question: String): String {
        val key = store.settings.value.cloudKey.trim()
        if (key.isEmpty()) return "El cerebro en la nube no está configurado (Ajustes → Cerebro en la nube). Responde tú."
        return try {
            val body = JSONObject()
                .put("model", store.settings.value.cloudModel.ifBlank { "claude-sonnet-5-5" })
                .put("max_tokens", 900)
                .put("system", "Eres el cerebro de apoyo de un asistente de voz. Responde en español de México, claro y conciso (máx. 8 frases), sin tablas.")
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", question)))
            val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val resp = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
            c.disconnect()
            if (code >= 400) {
                val msg = try { JSONObject(resp).getJSONObject("error").optString("message") } catch (_: Exception) { resp.take(200) }
                return "La nube respondió con error ($code): $msg"
            }
            val arr = JSONObject(resp).getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until arr.length()) arr.getJSONObject(i).optString("text").let { sb.append(it) }
            "Respuesta del cerebro en la nube:\n" + sb.toString().take(3000)
        } catch (e: Exception) {
            "No pude consultar la nube (${e.message}). ¿Hay internet?"
        }
    }
}
