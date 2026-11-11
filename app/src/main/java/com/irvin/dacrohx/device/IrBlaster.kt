package com.irvin.dacrohx.device

import android.content.Context
import android.hardware.ConsumerIrManager

/**
 * Control remoto infrarrojo usando el emisor IR del RedMagic 11 Pro.
 * Implementa los protocolos NEC (LG y muchas marcas), Samsung32 y Sony SIRC.
 */
class IrBlaster(ctx: Context) {

    private val ir: ConsumerIrManager? = ctx.getSystemService(ConsumerIrManager::class.java)

    val available: Boolean get() = ir?.hasIrEmitter() == true

    enum class Action(val label: String) {
        POWER("Encender / apagar"),
        VOL_UP("Subir volumen"),
        VOL_DOWN("Bajar volumen"),
        MUTE("Silencio"),
        CH_UP("Canal +"),
        CH_DOWN("Canal −"),
    }

    data class Brand(val id: String, val label: String)

    val brands = listOf(
        Brand("samsung", "Samsung"),
        Brand("lg", "LG"),
        Brand("sony", "Sony"),
    )

    private val samsung = mapOf(
        Action.POWER to 0xE0E040BFL, Action.VOL_UP to 0xE0E0E01FL, Action.VOL_DOWN to 0xE0E0D02FL,
        Action.MUTE to 0xE0E0F00FL, Action.CH_UP to 0xE0E048B7L, Action.CH_DOWN to 0xE0E008F7L,
    )
    private val lg = mapOf(
        Action.POWER to 0x20DF10EFL, Action.VOL_UP to 0x20DF40BFL, Action.VOL_DOWN to 0x20DFC03FL,
        Action.MUTE to 0x20DF906FL, Action.CH_UP to 0x20DF00FFL, Action.CH_DOWN to 0x20DF807FL,
    )
    private val sony = mapOf(
        Action.POWER to 0xA90, Action.VOL_UP to 0x490, Action.VOL_DOWN to 0xC90,
        Action.MUTE to 0x290, Action.CH_UP to 0x090, Action.CH_DOWN to 0x890,
    )

    /** Devuelve null si salió bien, o un mensaje de error. */
    fun send(brand: String, action: Action): String? {
        val m = ir ?: return "Este teléfono no tiene emisor infrarrojo."
        if (!m.hasIrEmitter()) return "Este teléfono no tiene emisor infrarrojo."
        return try {
            when (brand) {
                "lg" -> m.transmit(38000, pulseDistance(lg.getValue(action), 32, 9000, 4500))
                "sony" -> m.transmit(40000, sirc(sony.getValue(action), 12))
                else -> m.transmit(38000, pulseDistance(samsung.getValue(action), 32, 4500, 4500))
            }
            null
        } catch (e: Exception) {
            "No pude transmitir: ${e.message}"
        }
    }

    /** NEC / Samsung32: bits MSB primero, 562µs de pulso, espacio 562 (0) o 1687 (1). */
    private fun pulseDistance(code: Long, bits: Int, headOn: Int, headOff: Int): IntArray {
        val p = ArrayList<Int>(bits * 2 + 4)
        p += headOn; p += headOff
        for (i in bits - 1 downTo 0) {
            val one = ((code shr i) and 1L) == 1L
            p += 562
            p += if (one) 1687 else 562
        }
        p += 562
        p += 40000
        return p.toIntArray()
    }

    /** Sony SIRC 12 bits, repetido 3 veces como el control original. */
    private fun sirc(code: Int, bits: Int): IntArray {
        val frame = ArrayList<Int>()
        frame += 2400; frame += 600
        for (i in bits - 1 downTo 0) {
            frame += if (((code shr i) and 1) == 1) 1200 else 600
            frame += 600
        }
        val used = frame.sum()
        frame[frame.size - 1] = 600 + (45000 - used).coerceAtLeast(10000)
        return (frame + frame + frame).toIntArray()
    }

    fun parseAction(s: String): Action? {
        val t = s.lowercase().replace("_", " ")
        return when {
            "apag" in t || "encend" in t || "prend" in t || "power" in t -> Action.POWER
            "silenc" in t || "mute" in t || "calla" in t -> Action.MUTE
            "sub" in t && "vol" in t -> Action.VOL_UP
            "baj" in t && "vol" in t -> Action.VOL_DOWN
            "canal" in t && ("sig" in t || "sub" in t || "+" in t || "arriba" in t) -> Action.CH_UP
            "canal" in t && ("ant" in t || "baj" in t || "-" in t || "abajo" in t) -> Action.CH_DOWN
            "vol" in t && ("+" in t || "mas" in t || "más" in t) -> Action.VOL_UP
            "vol" in t && ("-" in t || "menos" in t) -> Action.VOL_DOWN
            else -> null
        }
    }
}
