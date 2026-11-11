package com.irvin.dacrohx.device

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.irvin.dacrohx.R

/** Sonidos propios de Irvin (sintetizados, estilo gamer). */
class Sounds(ctx: Context) {

    data class Option(val id: String, val label: String, val res: Int)

    val options = listOf(
        Option("nucleo", "Núcleo", R.raw.wake_nucleo),
        Option("cyber", "Cyber", R.raw.wake_cyber),
        Option("arranque", "Arranque", R.raw.wake_arranque),
        Option("pulso", "Pulso", R.raw.wake_pulso),
        Option("ninguno", "Sin sonido", 0),
    )

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        ).build()

    private val ids = HashMap<String, Int>()
    private val endId: Int

    init {
        options.filter { it.res != 0 }.forEach { ids[it.id] = pool.load(ctx, it.res, 1) }
        endId = pool.load(ctx, R.raw.listen_end, 1)
    }

    /** Sonido de "te escucho". */
    fun wake(id: String) {
        val s = ids[id.ifEmpty { "nucleo" }] ?: return
        pool.play(s, 1f, 1f, 1, 0, 1f)
    }

    /** Sonido corto de "listo, ya te escuché". */
    fun end(enabled: Boolean) { if (enabled) pool.play(endId, 0.7f, 0.7f, 1, 0, 1f) }
}
