package com.irvin.dacrohx.ai

import android.content.Context
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.irvin.dacrohx.data.Store
import com.irvin.dacrohx.data.musicFavs
import com.irvin.dacrohx.device.DeviceActions
import com.irvin.dacrohx.device.IrBlaster
import com.irvin.dacrohx.device.MusicPlayer
import android.view.KeyEvent
import com.irvin.dacrohx.device.ReminderScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Las "manos" de Irvin: funciones del teléfono que el modelo puede usar por sí solo.
 * Todo corre en el dispositivo, sin internet.
 */
class IrvinTools(
    private val ctx: Context,
    private val store: Store,
    private val device: DeviceActions,
    private val ir: IrBlaster,
    private val music: MusicPlayer,
    private val online: com.irvin.dacrohx.device.Online,
    private val phone: com.irvin.dacrohx.device.Phone,
    private val cloud: com.irvin.dacrohx.device.Cloud,
    private val host: Assistant,
    private val onAction: (String) -> Unit,
) : ToolSet {

    private val es = Locale("es", "MX")
    private fun fmt(t: Long) = SimpleDateFormat("EEE d MMM, h:mm a", es).format(Date(t))

    @Tool(description = "Guarda en la memoria a largo plazo un dato importante del usuario (nombre, gustos, metas, personas, fechas, datos personales). Úsala sin preguntar cuando el usuario comparta algo que valga la pena recordar.")
    fun guardarRecuerdo(
        @ToolParam(description = "El dato, conciso y en tercera persona. Ej: 'Su comida favorita son los tacos al pastor'.") dato: String,
    ): String {
        store.addMemory(dato)
        onAction("🧠 Recordado")
        return "Guardado en memoria."
    }

    @Tool(description = "Olvida (borra) los recuerdos que contengan una palabra clave. Úsala si el usuario pide olvidar algo o si un dato cambió.")
    fun olvidarRecuerdo(
        @ToolParam(description = "Palabra clave del recuerdo a borrar.") palabraClave: String,
    ): String {
        val n = store.forgetMatching(palabraClave)
        if (n > 0) onAction("🗑️ Olvidado")
        return if (n > 0) "Borré $n recuerdo(s)." else "No encontré recuerdos con '$palabraClave'."
    }

    @Tool(description = "Crea un recordatorio que sonará dentro de cierta cantidad de minutos. Úsala para frases como 'recuérdame en 20 minutos' o 'en 2 horas' (2 horas = 120 minutos).")
    fun recordatorioEnMinutos(
        @ToolParam(description = "Qué recordar, en segunda persona. Ej: 'Sacar la ropa de la lavadora'.") texto: String,
        @ToolParam(description = "Minutos desde ahora.") minutos: Int,
    ): String {
        if (minutos < 1) return "Los minutos deben ser 1 o más."
        val due = System.currentTimeMillis() + minutos * 60_000L
        val r = store.addReminder(texto, due)
        ReminderScheduler.schedule(ctx, r.id, due)
        onAction("⏰ " + fmt(due))
        return "Recordatorio creado para ${fmt(due)}."
    }

    @Tool(description = "Crea un recordatorio para una fecha y hora exactas. Úsala para 'mañana a las 8', 'el viernes a las 6 pm', etc. Calcula la fecha a partir de la fecha actual.")
    fun recordatorioEnFecha(
        @ToolParam(description = "Qué recordar, en segunda persona.") texto: String,
        @ToolParam(description = "Fecha y hora local en formato AAAA-MM-DD HH:MM con reloj de 24 horas. Ej: 2026-11-12 08:00") fechaHora: String,
    ): String {
        val due = parseDate(fechaHora) ?: return "No entendí la fecha '$fechaHora'. Usa el formato AAAA-MM-DD HH:MM."
        if (due < System.currentTimeMillis() - 60_000) return "Esa hora ya pasó (${fmt(due)}). Pide la fecha correcta."
        val r = store.addReminder(texto, due)
        ReminderScheduler.schedule(ctx, r.id, due)
        onAction("⏰ " + fmt(due))
        return "Recordatorio creado para ${fmt(due)}."
    }

    @Tool(description = "Cancela recordatorios pendientes que contengan una palabra clave.")
    fun cancelarRecordatorio(
        @ToolParam(description = "Palabra clave del recordatorio.") palabraClave: String,
    ): String {
        val k = palabraClave.lowercase()
        val hits = store.reminders.value.filter { !it.done && it.text.lowercase().contains(k) }
        hits.forEach { ReminderScheduler.cancel(ctx, it.id); store.deleteReminder(it.id) }
        if (hits.isNotEmpty()) onAction("❌ Cancelado")
        return if (hits.isEmpty()) "No hay recordatorios con '$palabraClave'." else "Cancelé ${hits.size} recordatorio(s)."
    }

    @Tool(description = "Lista los recordatorios pendientes del usuario.")
    fun verRecordatorios(): String {
        val p = store.reminders.value.filter { !it.done && !it.fired }.sortedBy { it.due }
        return if (p.isEmpty()) "No hay recordatorios pendientes."
        else p.joinToString("\n") { "- ${it.text} → ${fmt(it.due)}" }
    }

    @Tool(description = "Devuelve la fecha y hora actuales exactas.")
    fun fechaHoraActual(): String =
        SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm (h:mm a)", es).format(Date())

    @Tool(description = "Consulta el nivel de batería del teléfono.")
    fun nivelBateria(): String {
        onAction("🔋 Batería")
        return device.battery()
    }

    @Tool(description = "Enciende o apaga la linterna del teléfono.")
    fun linterna(
        @ToolParam(description = "true para encender, false para apagar.") encender: Boolean,
    ): String {
        onAction(if (encender) "🔦 Linterna ON" else "🔦 Linterna OFF")
        return device.torch(encender)
    }

    @Tool(description = "Abre una aplicación instalada en el teléfono por su nombre (WhatsApp, Spotify, Cámara, YouTube, etc).")
    fun abrirApp(
        @ToolParam(description = "Nombre de la app.") nombre: String,
    ): String {
        onAction("📱 $nombre")
        return device.openApp(nombre)
    }

    @Tool(description = "Calcula una operación matemática exacta. Úsala SIEMPRE para cuentas, porcentajes, propinas o conversiones numéricas. Soporta + - * / ^ % paréntesis y sqrt().")
    fun calcular(
        @ToolParam(description = "La expresión, ej: (1250*0.15)+80") expresion: String,
    ): String {
        onAction("🧮 Cálculo")
        return device.calculate(expresion)
    }

    @Tool(description = "Controla la televisión con el emisor infrarrojo del teléfono. Acciones: encender, apagar, subir volumen, bajar volumen, silencio, canal siguiente, canal anterior.")
    fun controlarTele(
        @ToolParam(description = "La acción, ej: 'subir volumen'.") accion: String,
        @ToolParam(description = "Cuántas veces repetirla (1 si no se indica).") veces: Int,
    ): String {
        val a = ir.parseAction(accion) ?: return "No conozco la acción '$accion'."
        val n = veces.coerceIn(1, 15)
        val brand = store.settings.value.tvBrand
        var err: String? = null
        repeat(n) {
            if (err == null) {
                err = ir.send(brand, a)
                Thread.sleep(180)
            }
        }
        onAction("📡 ${a.label}" + if (n > 1) " ×$n" else "")
        return err ?: "Listo: ${a.label}" + if (n > 1) " ($n veces)." else "."
    }

    @Tool(description = "Pone música (canción, artista, álbum o 'lo que sea'). Si el usuario no dijo en qué app, deja app vacío: la herramienta te dirá qué preguntar.")
    fun reproducirMusica(
        @ToolParam(description = "Canción, artista o álbum. 'lo que sea' si no especifican.") busqueda: String,
        @ToolParam(description = "App donde reproducir (ej. 'Poweramp', 'YouTube', 'Irvin'), o vacío si no lo dijo.") app: String,
    ): String {
        val all = music.musicApps()
        fun appOf(id: String) = if (id == "irvin") null else all.firstOrNull { it.pkg == id }
        val favs = store.settings.value.musicFavs()
            .filter { it == "irvin" || all.any { a -> a.pkg == it } }
        val wanted = app.trim()
        if (wanted.isNotEmpty()) {
            val target = if (wanted.lowercase().let { "irvin" in it || "tuyo" in it || "reproductor" in it }) null else music.findApp(wanted)
            if (target == null && !wanted.lowercase().contains("irvin") && !wanted.lowercase().contains("reproductor") && !wanted.lowercase().contains("tuyo"))
                return "No encontré la app '$wanted'. Opciones: " + all.joinToString { it.label } + "."
            return host.playMusic(busqueda, target).also { onAction("🎵 " + (target?.label ?: "Irvin")) }
        }
        when (favs.size) {
            1 -> { val t = appOf(favs[0]); return host.playMusic(busqueda, t).also { onAction("🎵 " + (t?.label ?: "Irvin")) } }
            2 -> {
                val names = favs.map { appOf(it)?.label ?: "el reproductor de Irvin" }
                host.askMusicApp(busqueda, favs.mapNotNull { appOf(it) }, includeIrvin = "irvin" in favs)
                return "PREGUNTA: pregunta al usuario si quiere '$busqueda' en ${names[0]} o en ${names[1]}. Cuando responda, vuelve a usar reproducir_musica con esa app."
            }
        }
        if (all.isEmpty()) return host.playMusic(busqueda, null)
        host.askMusicApp(busqueda, all, includeIrvin = true)
        return "PREGUNTA: pregunta al usuario en qué app quiere escuchar '$busqueda': ${all.joinToString { it.label }} o el reproductor de Irvin. Cuando responda, vuelve a usar reproducir_musica con esa app."
    }

    @Tool(description = "Agrega o quita reproductores favoritos de música (máximo 2: Poweramp, YouTube, YouTube Music, Spotify, Irvin). Usa 'quitar' para borrarlos.")
    fun reproductorPredeterminado(
        @ToolParam(description = "Nombre de la app, 'Irvin' o 'quitar'.") app: String,
    ): String {
        val a = app.lowercase()
        if ("quit" in a || "borr" in a || "elimin" in a || "ningun" in a || "pregunt" in a) {
            store.updateSettings { it.copy(musicApp = "") }
            onAction("🎵 Sin favoritos")
            return "Listo, borré tus reproductores favoritos; preguntaré cada vez."
        }
        val id = if ("irvin" in a) "irvin" else music.findApp(app)?.pkg
            ?: return "No encontré '$app' instalado. Opciones: " + music.musicApps().joinToString { it.label }
        val favs = (store.settings.value.musicFavs().filter { it != id } + id).takeLast(2)
        store.updateSettings { it.copy(musicApp = favs.joinToString(",")) }
        val names = favs.map { f -> if (f == "irvin") "Irvin" else music.musicApps().firstOrNull { it.pkg == f }?.label ?: f }
        onAction("🎵 " + names.joinToString(" · "))
        return "Listo. Favoritos: " + names.joinToString(" y ") + "."
    }

    @Tool(description = "Controla la música que está sonando (de Irvin o de cualquier app). Acciones: pausar, reanudar, siguiente, anterior, detener.")
    fun controlarMusica(
        @ToolParam(description = "pausar, reanudar, siguiente, anterior o detener") accion: String,
    ): String {
        val a = accion.lowercase()
        val own = music.ownActive()
        val r = when {
            "paus" in a || "para" in a && "dete" !in a -> { if (own) music.pause() else music.mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); "Pausado." }
            "reanud" in a || "sigue" in a || "play" in a || "continu" in a -> { if (own) music.resume() else music.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); "Reanudado." }
            "sig" in a || "next" in a || "otra" in a || "cambia" in a -> { if (own) music.next() else music.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); "Siguiente canción." }
            "ant" in a || "prev" in a || "regres" in a -> { if (own) music.previous() else music.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Canción anterior." }
            "deten" in a || "stop" in a || "quita" in a || "apaga" in a -> { if (own) music.stop() else music.mediaKey(KeyEvent.KEYCODE_MEDIA_STOP); "Música detenida." }
            else -> return "No conozco la acción '$accion'."
        }
        onAction("🎵 $r")
        return r
    }

    @Tool(description = "Cambia el volumen multimedia del teléfono (música, videos, voz de Irvin).")
    fun volumenTelefono(
        @ToolParam(description = "Porcentaje de 0 a 100.") porcentaje: Int,
    ): String {
        val v = music.setVolume(porcentaje)
        onAction("🔊 $v%")
        return "Volumen al $v%."
    }

    private fun net(label: String, block: () -> String): String {
        if (!store.settings.value.useInternet) return "El usuario desactivó el uso de internet en Ajustes. Responde sin internet."
        onAction("🌐 $label")
        return block()
    }

    @Tool(description = "INTERNET: busca en la web información actual (precios, horarios, resultados, lugares, cualquier dato reciente). Solo funciona con conexión.")
    fun buscarEnInternet(
        @ToolParam(description = "Qué buscar.") consulta: String,
    ): String = net("Búsqueda") { online.search(consulta) }

    @Tool(description = "INTERNET: lee el texto de una página web (por ejemplo un resultado de búsqueda) para dar detalles.")
    fun leerPagina(
        @ToolParam(description = "La dirección web.") url: String,
    ): String = net("Leyendo página") { online.readPage(url) }

    @Tool(description = "INTERNET: clima actual y pronóstico de 3 días de una ciudad.")
    fun clima(
        @ToolParam(description = "Ciudad, ej: Ciudad de México.") ciudad: String,
    ): String = net("Clima") { online.weather(ciudad) }

    @Tool(description = "INTERNET: titulares de noticias recientes de México o de un tema.")
    fun noticias(
        @ToolParam(description = "Tema, o 'principales' para las noticias del día.") tema: String,
    ): String = net("Noticias") { online.news(tema) }

    @Tool(description = "INTERNET: resumen de Wikipedia sobre una persona, lugar o tema.")
    fun wikipedia(
        @ToolParam(description = "El tema.") tema: String,
    ): String = net("Wikipedia") { online.wikipedia(tema) }

    @Tool(description = "INTERNET: tipo de cambio actual entre monedas (USD, MXN, EUR...).")
    fun tipoDeCambio(
        @ToolParam(description = "Moneda origen, ej: USD") de: String,
        @ToolParam(description = "Moneda destino, ej: MXN") a: String,
    ): String = net("Tipo de cambio") { online.exchange(de, a) }

    // ------------------------------------------------------------ teléfono
    @Tool(description = "Busca un contacto y su número.")
    fun buscarContacto(@ToolParam(description = "Nombre.") nombre: String): String {
        val hits = phone.findContacts(nombre)
        return if (hits.isEmpty()) "No encontré '$nombre' (¿falta permiso de contactos?)." else hits.joinToString("\n") { "${it.name}: ${it.number}" }
    }

    @Tool(description = "Llama por teléfono a un contacto o número.")
    fun llamar(@ToolParam(description = "Nombre del contacto o número.") contacto: String): String {
        val (c, err) = phone.resolve(contacto)
        if (c == null) return err ?: "No encontré el contacto."
        onAction("📞 ${c.name}")
        return phone.call(c.number) + " (${c.name})"
    }

    @Tool(description = "Prepara un SMS. NO se envía hasta que el usuario confirme: lee el mensaje y pregunta '¿Lo envío?'.")
    fun enviarSms(
        @ToolParam(description = "Nombre del contacto o número.") contacto: String,
        @ToolParam(description = "Texto del mensaje.") mensaje: String,
    ): String {
        val (c, err) = phone.resolve(contacto)
        if (c == null) return err ?: "No encontré el contacto."
        host.stage("Enviar SMS a ${c.name}: “$mensaje”") { phone.sms(c.number, mensaje) }
        onAction("✉️ Por confirmar")
        return "PENDIENTE de confirmación: pregunta al usuario si envía a ${c.name} el SMS: \"$mensaje\". Si dice que sí, usa confirmar_accion."
    }

    @Tool(description = "Abre WhatsApp con un mensaje ya escrito para un contacto (el usuario toca enviar).")
    fun whatsapp(
        @ToolParam(description = "Nombre del contacto o número.") contacto: String,
        @ToolParam(description = "Texto del mensaje.") mensaje: String,
    ): String {
        val (c, err) = phone.resolve(contacto)
        if (c == null) return err ?: "No encontré el contacto."
        onAction("💬 ${c.name}")
        return phone.whatsapp(c.number, mensaje)
    }

    @Tool(description = "Lee las notificaciones y mensajes recientes (WhatsApp, Telegram, SMS, correo...).")
    fun leerNotificaciones(@ToolParam(description = "App o persona para filtrar, o 'todas'.") filtro: String): String {
        if (!com.irvin.dacrohx.device.NotifListener.enabled(ctx)) return "No tengo acceso a las notificaciones. Dile que lo active en Ajustes → Permisos → Notificaciones."
        onAction("🔔 Notificaciones")
        return com.irvin.dacrohx.device.NotifListener.format(com.irvin.dacrohx.device.NotifListener.recent(filtro))
    }

    @Tool(description = "Prepara una respuesta a un mensaje recibido (WhatsApp, Telegram...). NO se envía hasta que el usuario confirme.")
    fun responderMensaje(
        @ToolParam(description = "Persona o chat al que se responde.") contacto: String,
        @ToolParam(description = "Texto de la respuesta.") mensaje: String,
    ): String {
        val item = com.irvin.dacrohx.device.NotifListener.findReplyable(contacto)
            ?: return "No hay una notificación reciente de '$contacto' que se pueda responder."
        host.stage("Responder a ${item.title} (${item.app}): “$mensaje”") { com.irvin.dacrohx.device.NotifListener.reply(ctx, item, mensaje) }
        onAction("💬 Por confirmar")
        return "PENDIENTE de confirmación: pregunta si responde a ${item.title} por ${item.app}: \"$mensaje\". Si dice que sí, usa confirmar_accion."
    }

    @Tool(description = "Ejecuta la acción pendiente (enviar mensaje) SOLO después de que el usuario dijo que sí.")
    fun confirmarAccion(): String = host.confirmPending().also { onAction("✅ $it".take(40)) }

    @Tool(description = "Cancela la acción pendiente.")
    fun cancelarAccion(): String { host.cancelPending(); return "Cancelado." }

    // ------------------------------------------------------------ reloj y calendario
    @Tool(description = "Pone una alarma en el reloj del teléfono.")
    fun ponerAlarma(
        @ToolParam(description = "Hora en formato 24 h (0-23).") hora: Int,
        @ToolParam(description = "Minutos (0-59).") minuto: Int,
        @ToolParam(description = "Etiqueta corta.") etiqueta: String,
    ): String { onAction("⏰ %d:%02d".format(hora, minuto)); return phone.alarm(hora, minuto, etiqueta) }

    @Tool(description = "Inicia un temporizador (cuenta regresiva) en el reloj del teléfono.")
    fun temporizador(
        @ToolParam(description = "Minutos.") minutos: Int,
        @ToolParam(description = "Segundos extra (0 si no).") segundos: Int,
        @ToolParam(description = "Etiqueta corta.") etiqueta: String,
    ): String { onAction("⏲️ ${minutos} min"); return phone.timer(minutos * 60 + segundos, etiqueta) }

    @Tool(description = "Consulta los eventos del calendario de un día.")
    fun verAgenda(@ToolParam(description = "'hoy', 'mañana' o AAAA-MM-DD.") dia: String): String {
        onAction("📅 Agenda"); return phone.agenda(dia)
    }

    @Tool(description = "Agenda un evento en el calendario.")
    fun agendarEvento(
        @ToolParam(description = "Título.") titulo: String,
        @ToolParam(description = "Inicio AAAA-MM-DD HH:MM (24 h).") fechaHora: String,
        @ToolParam(description = "Duración en minutos (60 si no se indica).") duracionMinutos: Int,
    ): String { onAction("📅 $titulo"); return phone.addEvent(titulo, fechaHora, duracionMinutos) }

    // ------------------------------------------------------------ nube
    @Tool(description = "INTERNET: consulta un cerebro más potente en la nube para preguntas MUY difíciles (código, análisis, temas complejos). Úsala solo si no puedes responder bien tú.")
    fun consultarNube(@ToolParam(description = "La pregunta completa con contexto.") pregunta: String): String {
        if (!online.connected()) return com.irvin.dacrohx.device.Online.NO_NET
        onAction("☁️ Nube")
        return cloud.ask(pregunta)
    }

    @Tool(description = "Guarda cómo quiere el usuario que lo llames (su nombre o apodo).")
    fun guardarNombre(
        @ToolParam(description = "Nombre o apodo.") nombre: String,
    ): String {
        store.updateSettings { it.copy(name = nombre.trim().take(40)) }
        onAction("👤 ${nombre.trim()}")
        return "Nombre guardado."
    }

    private fun parseDate(s: String): Long? {
        val clean = s.trim().replace("T", " ").take(16)
        for (p in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd H:mm", "yyyy/MM/dd HH:mm", "dd/MM/yyyy HH:mm")) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply { isLenient = false }
                return f.parse(clean)?.time
            } catch (_: Exception) {}
        }
        return null
    }
}
