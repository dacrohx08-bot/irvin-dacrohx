package com.irvin.dacrohx.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

sealed class BrainState {
    object Off : BrainState()
    data class Loading(val step: String) : BrainState()
    data class Ready(val backend: String, val audio: Boolean, val vision: Boolean) : BrainState()
    data class Error(val message: String) : BrainState()
}

/** El cerebro local de Irvin: Gemma 4 corriendo con LiteRT-LM en la GPU del teléfono. */
class Brain(private val ctx: Context) {

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "irvin-brain").apply { priority = Thread.MAX_PRIORITY }
    }.asCoroutineDispatcher()

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    private val _state = MutableStateFlow<BrainState>(BrainState.Off)
    val state: StateFlow<BrainState> = _state.asStateFlow()

    val ready: Boolean get() = _state.value is BrainState.Ready
    val audioEnabled: Boolean get() = (_state.value as? BrainState.Ready)?.audio == true
    val visionEnabled: Boolean get() = (_state.value as? BrainState.Ready)?.vision == true

    private data class Attempt(
        val label: String,
        val backend: () -> Backend,
        val multimodal: Boolean,
        val mtp: Boolean,
    )

    @OptIn(ExperimentalApi::class)
    suspend fun load(modelPath: String) = withContext(dispatcher) {
        if (engine != null) return@withContext
        val attempts = listOf(
            Attempt("GPU + MTP", { Backend.GPU() }, multimodal = true, mtp = true),
            Attempt("GPU", { Backend.GPU() }, multimodal = true, mtp = false),
            Attempt("GPU (solo texto)", { Backend.GPU() }, multimodal = false, mtp = false),
            Attempt("CPU", { Backend.CPU() }, multimodal = true, mtp = false),
            Attempt("CPU (solo texto)", { Backend.CPU() }, multimodal = false, mtp = false),
        )
        var lastError: Throwable? = null
        for (a in attempts) {
            _state.value = BrainState.Loading("Encendiendo cerebro (${a.label})…")
            try {
                ExperimentalFlags.enableSpeculativeDecoding = a.mtp
                val cfg = EngineConfig(
                    modelPath = modelPath,
                    backend = a.backend(),
                    visionBackend = if (a.multimodal) Backend.GPU() else null,
                    audioBackend = if (a.multimodal) Backend.CPU() else null,
                    maxNumTokens = 16384,
                    cacheDir = ctx.cacheDir.absolutePath,
                )
                val e = Engine(cfg)
                try {
                    e.initialize()
                } catch (t: Throwable) {
                    try { e.close() } catch (_: Throwable) {}
                    throw t
                }
                engine = e
                _state.value = BrainState.Ready(a.label, audio = a.multimodal, vision = a.multimodal)
                Log.i("Irvin", "Cerebro listo con ${a.label}")
                return@withContext
            } catch (t: Throwable) {
                Log.w("Irvin", "Falló ${a.label}", t)
                lastError = t
            } finally {
                ExperimentalFlags.enableSpeculativeDecoding = false
            }
        }
        _state.value = BrainState.Error("No pude cargar el modelo: ${lastError?.message ?: "error desconocido"}")
    }

    /** Empieza una conversación nueva con la personalidad, memoria e historial reciente. */
    suspend fun newConversation(
        system: String,
        history: List<Pair<String, String>>,
        tools: ToolSet,
    ) = withContext(dispatcher) {
        val e = engine ?: return@withContext
        try { conversation?.close() } catch (_: Throwable) {}
        val initial = history.map { (role, text) ->
            if (role == "user") Message.user(text) else Message.model(text)
        }
        conversation = e.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                initialMessages = initial,
                tools = listOf(tool(tools)),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.55),
                // Procesa las instrucciones al crear la conversación: el primer mensaje responde más rápido.
                prefillPrefaceOnInit = true,
                // Sin "modo pensar": responde directo (mucho más rápido).
                thinkingConfig = com.google.ai.edge.litertlm.ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                extraContext = mapOf("enable_thinking" to false),
            )
        )
    }

    /** Envía un mensaje y devuelve el texto de respuesta en pedacitos (streaming). */
    fun send(parts: List<Content>): Flow<String> {
        val c = conversation ?: throw IllegalStateException("El cerebro no está listo.")
        return c.sendMessageAsync(Contents.of(parts), mapOf("enable_thinking" to false))
            .map { it.toString() }
            .flowOn(dispatcher)
    }

    /** Cuántos tokens lleva la conversación (para reiniciarla antes de que se llene). */
    suspend fun tokenCount(): Int = withContext(dispatcher) {
        try { conversation?.getTokenCount() ?: 0 } catch (_: Throwable) { 0 }
    }

    val maxTokens = 16384

    fun stop() {
        try { conversation?.cancelProcess() } catch (_: Throwable) {}
    }

    suspend fun close() = withContext(dispatcher) {
        try { conversation?.close() } catch (_: Throwable) {}
        try { engine?.close() } catch (_: Throwable) {}
        conversation = null
        engine = null
        _state.value = BrainState.Off
    }
}
