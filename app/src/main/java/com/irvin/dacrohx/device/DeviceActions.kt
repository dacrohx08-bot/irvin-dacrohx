package com.irvin.dacrohx.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import java.text.Normalizer
import java.util.Locale

/** Acciones del teléfono que Irvin puede hacer sin internet. */
class DeviceActions(private val ctx: Context) {

    private var torchOn = false

    fun battery(): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return "Batería al $pct%" + if (charging) ", cargando." else ", sin cargar."
    }

    fun torch(on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No encontré flash en este teléfono."
            cm.setTorchMode(id, on)
            torchOn = on
            if (on) "Linterna encendida." else "Linterna apagada."
        } catch (e: Exception) {
            "No pude usar la linterna: ${e.message}"
        }
    }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").trim()

    /** Abre una app instalada por su nombre visible. */
    fun openApp(name: String): String {
        val pm = ctx.packageManager
        val q = norm(name)
        if (q.isEmpty()) return "¿Qué app abro?"
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.MATCH_ALL
        )
        val scored = launchers.mapNotNull {
            val label = norm(it.loadLabel(pm).toString())
            val score = when {
                label == q -> 3
                label.startsWith(q) -> 2
                label.contains(q) || q.contains(label) -> 1
                else -> 0
            }
            if (score > 0) Triple(it, label, score) else null
        }.sortedByDescending { it.third }
        val best = scored.firstOrNull() ?: return "No encontré ninguna app llamada \"$name\"."
        val intent = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName)
            ?: return "No pude abrir \"$name\"."
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            "Abriendo ${best.first.loadLabel(pm)}."
        } catch (e: Exception) {
            "Android no me dejó abrir \"$name\"."
        }
    }

    /** Calculadora exacta para que Irvin no se equivoque en cuentas. */
    fun calculate(expr: String): String = try {
        val v = Calc(expr.replace("×", "*").replace("÷", "/").replace(",", "")).parse()
        val s = if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) v.toLong().toString()
        else String.format(Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
        "$expr = $s"
    } catch (e: Exception) {
        "No pude calcular \"$expr\": ${e.message}"
    }

    private class Calc(val s: String) {
        var i = 0
        fun parse(): Double {
            val v = expr()
            skip()
            if (i < s.length) throw IllegalArgumentException("símbolo inesperado '${s[i]}'")
            return v
        }
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun expr(): Double {
            var v = term()
            while (true) {
                skip()
                if (i >= s.length) return v
                when (s[i]) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }
        fun term(): Double {
            var v = factor()
            while (true) {
                skip()
                if (i >= s.length) return v
                when (s[i]) {
                    '*' -> { i++; v *= factor() }
                    '/' -> { i++; v /= factor() }
                    '%' -> { i++; v = v * factor() / 100.0 }
                    else -> return v
                }
            }
        }
        fun factor(): Double {
            val b = unary()
            skip()
            if (i < s.length && s[i] == '^') { i++; return Math.pow(b, factor()) }
            return b
        }
        fun unary(): Double {
            skip()
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return atom()
        }
        fun atom(): Double {
            skip()
            if (s.startsWith("sqrt", i) || s.startsWith("raiz", i)) {
                i += 4; return Math.sqrt(atom())
            }
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                skip()
                if (i >= s.length || s[i] != ')') throw IllegalArgumentException("falta ')'")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("se esperaba un número")
            return s.substring(start, i).toDouble()
        }
    }
}
