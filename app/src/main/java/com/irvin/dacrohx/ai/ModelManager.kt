package com.irvin.dacrohx.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed class ModelState {
    object Missing : ModelState()
    data class Downloading(val done: Long, val total: Long) : ModelState()
    data class Importing(val done: Long, val total: Long) : ModelState()
    object Ready : ModelState()
    data class Failed(val reason: String) : ModelState()
}

/**
 * Descarga (una sola vez) el cerebro de Irvin: Gemma 4 E4B en formato LiteRT-LM.
 * Después de esto todo funciona sin internet.
 */
data class ModelSpec(val fileName: String, val size: Long, val url: String, val label: String, val prefKey: String)

class ModelManager(private val ctx: Context, private val spec: ModelSpec = E4B) {

    companion object {
        val E4B = ModelSpec(
            "gemma-4-E4B-it.litertlm", 3_659_530_240L,
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm?download=true",
            "Gemma 4 E4B", "download_id",
        )
        /** Modo turbo: modelo más pequeño, casi el doble de rápido. */
        val E2B = ModelSpec(
            "gemma-4-E2B-it.litertlm", 2_588_147_712L,
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/6e5c4f1e395deb959c494953478fa5cec4b8008f/gemma-4-E2B-it.litertlm?download=true",
            "Gemma 4 E2B (turbo)", "download_id_e2b",
        )
    }

    private val FILE_NAME = spec.fileName
    private val EXPECTED_SIZE = spec.size
    private val MIN_VALID_SIZE = spec.size * 9 / 10
    private val URL = spec.url
    private val PREF_DL_ID = spec.prefKey
    val label: String get() = spec.label

    private val prefs = ctx.getSharedPreferences("irvin_model", Context.MODE_PRIVATE)
    private val dm = ctx.getSystemService(DownloadManager::class.java)

    val modelFile: File get() = File(ctx.getExternalFilesDir(null), FILE_NAME)
    private val partFile: File get() = File(ctx.getExternalFilesDir(null), "$FILE_NAME.part")

    private val _state = MutableStateFlow<ModelState>(initialState())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private fun initialState(): ModelState = when {
        modelFile.exists() && modelFile.length() >= MIN_VALID_SIZE -> ModelState.Ready
        prefs.getLong(PREF_DL_ID, -1L) != -1L -> ModelState.Downloading(0, EXPECTED_SIZE)
        else -> ModelState.Missing
    }

    fun isReady() = modelFile.exists() && modelFile.length() >= MIN_VALID_SIZE

    fun startDownload() {
        if (isReady()) { _state.value = ModelState.Ready; return }
        partFile.delete()
        val req = DownloadManager.Request(Uri.parse(URL))
            .setTitle("Cerebro de Irvin Dacrohx")
            .setDescription("${spec.label} · solo se descarga una vez")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(ctx, null, "$FILE_NAME.part")
        val id = dm.enqueue(req)
        prefs.edit().putLong(PREF_DL_ID, id).apply()
        _state.value = ModelState.Downloading(0, EXPECTED_SIZE)
    }

    fun cancelDownload() {
        val id = prefs.getLong(PREF_DL_ID, -1L)
        if (id != -1L) dm.remove(id)
        prefs.edit().remove(PREF_DL_ID).apply()
        partFile.delete()
        _state.value = ModelState.Missing
    }

    /** Vigila la descarga hasta que termine. Llamar desde una corrutina. */
    suspend fun watchDownload() {
        while (true) {
            val id = prefs.getLong(PREF_DL_ID, -1L)
            if (id == -1L) {
                _state.value = if (isReady()) ModelState.Ready else (_state.value.takeIf { it is ModelState.Failed } ?: ModelState.Missing)
                return
            }
            val c = dm.query(DownloadManager.Query().setFilterById(id))
            if (c == null || !c.moveToFirst()) {
                c?.close()
                prefs.edit().remove(PREF_DL_ID).apply()
                _state.value = ModelState.Failed("La descarga se canceló.")
                return
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            var total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            c.close()
            if (total <= 0) total = EXPECTED_SIZE
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    prefs.edit().remove(PREF_DL_ID).apply()
                    if (partFile.exists() && partFile.length() >= MIN_VALID_SIZE) {
                        modelFile.delete()
                        partFile.renameTo(modelFile)
                        _state.value = ModelState.Ready
                    } else {
                        _state.value = ModelState.Failed("El archivo descargado está incompleto. Intenta de nuevo.")
                    }
                    return
                }
                DownloadManager.STATUS_FAILED -> {
                    prefs.edit().remove(PREF_DL_ID).apply()
                    partFile.delete()
                    _state.value = ModelState.Failed("Falló la descarga (código $reason). Revisa tu conexión y espacio libre.")
                    return
                }
                else -> _state.value = ModelState.Downloading(done, total)
            }
            delay(700)
        }
    }

    /** Importa un archivo .litertlm que el usuario ya tenga en el teléfono. */
    suspend fun importFrom(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val total = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            _state.value = ModelState.Importing(0, if (total > 0) total else EXPECTED_SIZE)
            val tmp = File(ctx.getExternalFilesDir(null), "$FILE_NAME.import")
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - last > 32L shl 20) {
                            last = copied
                            _state.value = ModelState.Importing(copied, if (total > 0) total else EXPECTED_SIZE)
                        }
                    }
                }
            }
            if (tmp.length() < 2_000_000_000L) {
                tmp.delete()
                _state.value = ModelState.Failed("Ese archivo no parece ser el modelo (es muy pequeño).")
                false
            } else {
                modelFile.delete()
                tmp.renameTo(modelFile)
                _state.value = ModelState.Ready
                true
            }
        } catch (e: Exception) {
            _state.value = ModelState.Failed("No pude importar el archivo: ${e.message}")
            false
        }
    }

    fun deleteModel() {
        modelFile.delete()
        _state.value = ModelState.Missing
    }
}
