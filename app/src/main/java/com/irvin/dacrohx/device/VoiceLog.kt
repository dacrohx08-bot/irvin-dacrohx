package com.irvin.dacrohx.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro de diagnóstico de voz: cada paso de escuchar/responder, para encontrar fallas. */
object VoiceLog {
    private val f = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun add(msg: String) {
        val l = f.format(Date()) + "  " + msg
        synchronized(this) { _lines.value = (_lines.value + l).takeLast(60) }
        android.util.Log.i("IrvinVoz", msg)
    }

    fun clear() { _lines.value = emptyList() }

    fun errName(code: Int) = when (code) {
        1 -> "1 red (timeout)"; 2 -> "2 red"; 3 -> "3 audio"; 4 -> "4 servidor"; 5 -> "5 cliente"
        6 -> "6 no hablaste"; 7 -> "7 no entendí"; 8 -> "8 ocupado"; 9 -> "9 sin permiso"
        10 -> "10 demasiadas solicitudes"; 11 -> "11 desconectado"; 12 -> "12 idioma no soportado"
        13 -> "13 idioma no descargado"; 14 -> "14 no disponible"; else -> "$code"
    }
}
