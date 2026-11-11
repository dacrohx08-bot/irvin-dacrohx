package com.irvin.dacrohx

import android.app.Application
import com.irvin.dacrohx.device.ReminderScheduler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class IrvinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Guarda cualquier cierre inesperado para mostrarlo la próxima vez.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText(
                    "Irvin Dacrohx v${BuildConfig.VERSION_NAME} · " +
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                        " · hilo ${t.name}\n" + sw.toString().take(6000)
                )
            } catch (_: Exception) {}
            previous?.uncaughtException(t, e)
        }
        ReminderScheduler.createChannel(this)
    }

    companion object {
        fun takeLastCrash(app: Application): String? {
            val f = File(app.filesDir, "last_crash.txt")
            if (!f.exists()) return null
            val s = f.readText()
            f.delete()
            return s
        }
    }
}
