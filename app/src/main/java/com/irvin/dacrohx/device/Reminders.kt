package com.irvin.dacrohx.device

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.irvin.dacrohx.MainActivity
import com.irvin.dacrohx.R
import com.irvin.dacrohx.data.ChatMsg
import com.irvin.dacrohx.data.Store

object ReminderScheduler {
    const val CHANNEL = "recordatorios_v1"
    const val EXTRA_ID = "rid"
    const val ACTION_FIRE = "com.irvin.dacrohx.FIRE"
    const val ACTION_DONE = "com.irvin.dacrohx.DONE"
    const val ACTION_SNOOZE = "com.irvin.dacrohx.SNOOZE"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "Recordatorios", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Avisos de Irvin Dacrohx"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
        }
        nm.createNotificationChannel(ch)
    }

    private fun pi(ctx: Context, id: String, action: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, (id + action).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context, id: String, due: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val fire = pi(ctx, id, ACTION_FIRE)
        val show = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (am.canScheduleExactAlarms()) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(due, show), fire)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, fire)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, fire)
        }
    }

    fun cancel(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, id, ACTION_FIRE))
        NotificationManagerCompat.from(ctx).cancel(id.hashCode())
    }

    fun rescheduleAll(ctx: Context) {
        val store = Store.get(ctx)
        store.reloadReminders()
        val now = System.currentTimeMillis()
        store.reminders.value.filter { !it.done && !it.fired }.forEach {
            if (it.due <= now) fire(ctx, it.id) else schedule(ctx, it.id, it.due)
        }
    }

    fun fire(ctx: Context, id: String) {
        val store = Store.get(ctx)
        val r = store.reminder(id) ?: return
        if (r.done) return
        store.updateReminder(id) { it.copy(fired = true) }
        store.addChat(ChatMsg(role = "sys", text = "⏰ ${r.text}"))

        val open = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Irvin Dacrohx · Recordatorio")
            .setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(0xFFFF2340.toInt())
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Hecho", pi(ctx, id, ACTION_DONE))
            .addAction(0, "+10 min", pi(ctx, id, ACTION_SNOOZE))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id.hashCode(), n)
        } catch (e: SecurityException) {
            // sin permiso de notificaciones: el aviso queda en el chat
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(ReminderScheduler.EXTRA_ID) ?: return
        val store = Store.get(ctx)
        when (intent.action) {
            ReminderScheduler.ACTION_FIRE -> {
                store.reloadReminders()
                ReminderScheduler.fire(ctx, id)
            }
            ReminderScheduler.ACTION_DONE -> {
                store.updateReminder(id) { it.copy(done = true) }
                NotificationManagerCompat.from(ctx).cancel(id.hashCode())
            }
            ReminderScheduler.ACTION_SNOOZE -> {
                val due = System.currentTimeMillis() + 10 * 60_000L
                store.updateReminder(id) { it.copy(due = due, fired = false) }
                ReminderScheduler.schedule(ctx, id, due)
                NotificationManagerCompat.from(ctx).cancel(id.hashCode())
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ReminderScheduler.createChannel(ctx)
        ReminderScheduler.rescheduleAll(ctx)
    }
}
