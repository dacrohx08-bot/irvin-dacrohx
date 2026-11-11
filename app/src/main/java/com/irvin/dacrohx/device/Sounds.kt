package com.irvin.dacrohx.device

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.irvin.dacrohx.R

/** Sonidos propios de Irvin (sintetizados). Algunos traen su pareja de "listo" al terminar de escuchar. */
class Sounds(ctx: Context) {

    data class Option(
        val id: String, val label: String, val res: Int,
        val endRes: Int = R.raw.listen_end, val gain: Float = 1f, val group: String = "Gamer",
    )

    val options = listOf(
        // Estilo asistente: cortos, suaves y limpios
        Option("aura", "Aura", R.raw.wake_aura, R.raw.end_aura, 0.8f, "Asistente"),
        Option("cristal", "Cristal", R.raw.wake_cristal, R.raw.end_cristal, 0.75f, "Asistente"),
        Option("burbuja", "Burbuja", R.raw.wake_burbuja, R.raw.end_burbuja, 0.8f, "Asistente"),
        Option("destello", "Destello", R.raw.wake_destello, R.raw.end_destello, 0.8f, "Asistente"),
        Option("eco", "Eco", R.raw.wake_eco, R.raw.end_eco, 0.9f, "Asistente"),
        Option("brisa", "Brisa", R.raw.wake_brisa, R.raw.end_brisa, 0.85f, "Asistente"),
        // Estilo gamer: potentes
        Option("titan", "Titán 🔥", R.raw.wake_titan),
        Option("dominio", "Dominio", R.raw.wake_dominio),
        Option("nucleo", "Núcleo", R.raw.wake_nucleo),
        Option("cyber", "Cyber", R.raw.wake_cyber),
        Option("arranque", "Arranque Pro", R.raw.wake_arranque),
        Option("hiperespacio", "Hiperespacio", R.raw.wake_hiperespacio),
        Option("holograma", "Holograma", R.raw.wake_holograma),
        Option("turbo", "Turbo", R.raw.wake_turbo),
        Option("escudo", "Escudo", R.raw.wake_escudo),
        Option("neon", "Neón", R.raw.wake_neon),
        Option("glitch", "Glitch", R.raw.wake_glitch),
        Option("pulso", "Pulso", R.raw.wake_pulso),
        Option("ninguno", "Sin sonido", 0, 0, 0f, "Otro"),
    )

    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()

    private val loaded = HashMap<Int, Int>()

    init {
        options.flatMap { listOf(it.res, it.endRes) }.filter { it != 0 }.distinct()
            .forEach { loaded[it] = pool.load(ctx, it, 1) }
    }

    private fun opt(id: String) = options.firstOrNull { it.id == id } ?: options.first { it.id == "nucleo" }

    /** Sonido de "te escucho". */
    fun wake(id: String) {
        val o = opt(id.ifEmpty { "nucleo" })
        val s = loaded[o.res] ?: return
        pool.play(s, o.gain, o.gain, 1, 0, 1f)
    }

    /** Sonido de "listo, ya te escuché" (la pareja del sonido elegido). */
    fun end(id: String) {
        val o = opt(id.ifEmpty { "nucleo" })
        val s = loaded[o.endRes] ?: return
        val g = if (o.group == "Asistente") o.gain else 0.7f
        pool.play(s, g, g, 1, 0, 1f)
    }
}
