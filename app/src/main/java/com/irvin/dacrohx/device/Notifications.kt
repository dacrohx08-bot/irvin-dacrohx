package com.irvin.dacrohx.device

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lee las notificaciones que llegan (WhatsApp, Telegram, SMS...) para que Irvin te las cuente. */
class NotifListener : NotificationListenerService() {

    data class Item(
        val key: String, val pkg: String, val app: String, val title: String, val text: String,
        val time: Long, val reply: Notification.Action?, val isMessage: Boolean = false,
    )

    companion object {
        private val items = ArrayDeque<Item>()
        val MESSAGING = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram",
            "com.facebook.orca", "com.facebook.mlite", "com.instagram.android", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.android.mms", "com.discord", "com.snapchat.android",
            "com.microsoft.teams", "com.Slack", "com.viber.voip", "jp.naver.line.android", "com.signal",
            "org.thoughtcrime.securesms",
        )
        private val lock = Any()
        private val _connected = kotlinx.coroutines.flow.MutableStateFlow(false)
        /** Se actualiza en vivo para que Ajustes muestre el estado real. */
        val connectedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _connected
        val connected: Boolean get() = _connected.value

        fun enabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotifListener::class.java).flattenToString()
            return flat.split(":").any { it == me }
        }

        /** Último mensaje detectado y qué hizo Irvin (para diagnóstico en Ajustes). */
        @Volatile var lastEvent: String = "Todavía no ha llegado ningún mensaje desde que se abrió Irvin."

        /**
         * Vuelve a conectar el lector. Al actualizar Irvin, Android lo desconecta y en algunos
         * teléfonos (RedMagic/Nubia) no lo reconecta solo: si [force], apaga y prende el
         * componente, lo que obliga a Android a conectarlo de nuevo.
         */
        fun rebind(ctx: Context, force: Boolean = false) {
            if (!enabled(ctx) || connected) return
            val cn = ComponentName(ctx, NotifListener::class.java)
            try { requestRebind(cn) } catch (_: Exception) {}
            if (!force) return
            try {
                val pm = ctx.packageManager
                pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                requestRebind(cn)
            } catch (_: Exception) {}
        }

        /** Reconexión automática: primero suave y, si a los 4 s sigue desconectado, forzada. */
        fun ensureConnected(ctx: Context) {
            if (!enabled(ctx) || connected) return
            rebind(ctx)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!connected) rebind(ctx, force = true) }, 4000)
        }

        fun openSettings(ctx: Context) {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        private fun norm(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

        fun recent(appFilter: String, max: Int = 12): List<Item> = synchronized(lock) {
            val f = norm(appFilter.trim())
            items.filter { f.isEmpty() || f in setOf("todas", "todo", "todos") || norm(it.app).contains(f) || norm(it.title).contains(f) }
                .sortedByDescending { it.time }.take(max)
        }

        fun findReplyable(who: String): Item? = synchronized(lock) {
            val w = norm(who)
            items.filter { it.reply != null && (norm(it.title).contains(w) || w.isEmpty()) }.maxByOrNull { it.time }
        }

        /** Responde usando el botón "Responder" de la notificación (WhatsApp, Telegram, etc). */
        fun reply(ctx: Context, item: Item, text: String): String {
            val action = item.reply ?: return "Esa notificación no permite responder."
            val inputs = action.remoteInputs ?: return "Esa notificación no permite responder."
            return try {
                val intent = Intent()
                val bundle = Bundle()
                inputs.forEach { bundle.putCharSequence(it.resultKey, text) }
                RemoteInput.addResultsToIntent(inputs, intent, bundle)
                action.actionIntent.send(ctx, 0, intent)
                "Respondí a ${item.title} por ${item.app}."
            } catch (e: Exception) {
                "No pude responder: ${e.message}"
            }
        }

        fun format(list: List<Item>): String {
            if (list.isEmpty()) return "No hay notificaciones recientes."
            val f = SimpleDateFormat("h:mm a", Locale("es", "MX"))
            return list.joinToString("\n") { "- [${it.app}] ${it.title}: ${it.text.take(200)} (${f.format(Date(it.time))})" }
        }
    }

    private var connectedAt = 0L

    override fun onListenerConnected() {
        _connected.value = true
        connectedAt = System.currentTimeMillis()
        try { activeNotifications?.forEach { add(it) } } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        // Android lo soltó (ahorro de batería o actualización): pide volver a conectarlo.
        try { requestRebind(ComponentName(this, NotifListener::class.java)) } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) { add(sbn) }

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.category == Notification.CATEGORY_SERVICE || n.category == Notification.CATEGORY_PROGRESS) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: return
        if (text.isBlank()) return
        val app = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) { sbn.packageName }
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        val isMsg = n.category == Notification.CATEGORY_MESSAGE || sbn.packageName in MESSAGING
        val item = Item(sbn.key, sbn.packageName, app, title, text, sbn.postTime, reply, isMsg)
        var isNew = false
        synchronized(lock) {
            isNew = items.none { it.key == sbn.key && it.text == text }
            items.removeAll { it.key == sbn.key && it.text == text }
            items.addLast(item)
            while (items.size > 80) items.removeFirst()
        }
        // Aviso por voz de mensajes nuevos (solo los que llegan en este momento).
        val hora = java.text.SimpleDateFormat("h:mm a", java.util.Locale("es", "MX")).format(java.util.Date())
        if (isMsg) lastEvent = "$hora · $app · " + when {
            !isNew -> "repetido, ignorado"
            System.currentTimeMillis() - sbn.postTime >= 60_000 -> "viejo, ignorado"
            System.currentTimeMillis() - connectedAt <= 3000 -> "al conectar, ignorado"
            else -> "detectado"
        }
        if (isNew && isMsg && System.currentTimeMillis() - sbn.postTime < 60_000 && connectedAt > 0 &&
            System.currentTimeMillis() - connectedAt > 3000
        ) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try { com.irvin.dacrohx.ai.Assistant.get(this).onIncomingMessage(item) } catch (_: Exception) {}
            }
        }
    }
}
