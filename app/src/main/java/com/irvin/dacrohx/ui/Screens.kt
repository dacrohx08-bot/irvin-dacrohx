package com.irvin.dacrohx.ui

import com.irvin.dacrohx.ai.Assistant
import com.irvin.dacrohx.ai.Mode

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvin.dacrohx.ai.BrainState
import com.irvin.dacrohx.ai.ModelManager
import com.irvin.dacrohx.ai.ModelState
import com.irvin.dacrohx.data.ChatMsg
import com.irvin.dacrohx.data.PERSONAS
import com.irvin.dacrohx.device.IrBlaster
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val ES = Locale("es", "MX")

enum class Tab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Filled.Chat),
    MEM("Memoria", Icons.Filled.Memory),
    REM("Avisos", Icons.Filled.Alarm),
    CTRL("Control", Icons.Filled.SettingsRemote),
    SET("Ajustes", Icons.Filled.Settings),
}

// =====================================================================================
@Composable
fun IrvinRoot(vm: Assistant) {
    val modelState by vm.models.state.collectAsState()
    val toast by vm.toast.collectAsState()

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        if (modelState !is ModelState.Ready) SetupScreen(vm, modelState) else MainShell(vm)

        // toast propio
        toast?.let { msg ->
            LaunchedEffect(msg) { delay(2600); vm.clearToast() }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 150.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color(0xFF1D1D26))
                    .border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp)
            ) { Text(msg, color = C.Txt, fontSize = 15.sp, textAlign = TextAlign.Center) }
        }
    }
}

// =====================================================================================
@Composable
fun SetupScreen(vm: Assistant, state: ModelState) {
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { vm.models.importFrom(uri) }
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Orb(if (state is ModelState.Downloading || state is ModelState.Importing) Mode.THINKING else Mode.IDLE, 0f, Modifier.size(190.dp))
        Spacer(Modifier.height(24.dp))
        Text("IRVIN DACROHX", style = Hud.copy(fontSize = 22.sp, color = C.Txt, letterSpacing = 5.sp))
        Text("Asistente personal · 100% offline", color = C.Mut, fontSize = 15.sp)
        Spacer(Modifier.height(28.dp))
        Panel {
            Text("INSTALAR CEREBRO", style = Hud)
            Spacer(Modifier.height(8.dp))
            Text(
                "Irvin piensa con Gemma 4 E4B, un modelo de IA que vive dentro de tu teléfono. " +
                    "Se descarga UNA sola vez (3.7 GB). Después funciona sin internet para siempre.",
                color = C.Txt, fontSize = 15.sp, lineHeight = 20.sp,
            )
            Spacer(Modifier.height(16.dp))
            when (state) {
                is ModelState.Downloading -> {
                    Progress(state.done, state.total, "Descargando")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { vm.models.cancelDownload() }, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
                    Text("Puedes salir de la app; la descarga sigue en segundo plano.", color = C.Mut, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
                is ModelState.Importing -> Progress(state.done, state.total, "Importando")
                else -> {
                    if (state is ModelState.Failed) {
                        Text(state.reason, color = C.Warn, fontSize = 14.sp)
                        Spacer(Modifier.height(10.dp))
                    }
                    RedButton("Descargar cerebro (3.7 GB)") {
                        vm.models.startDownload()
                        scope.launch { vm.models.watchDownload() }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Ya lo tengo: importar archivo .litertlm") }
                    Text("Recomendado: conéctate a Wi-Fi y deja el teléfono cargando.", color = C.Mut, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun Progress(done: Long, total: Long, label: String) {
    val p = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), color = C.Red, trackColor = C.Line)
    Spacer(Modifier.height(8.dp))
    Text(
        "$label… ${(p * 100).toInt()}%  ·  ${"%.2f".format(done / 1e9)} / ${"%.2f".format(total / 1e9)} GB",
        color = C.Txt, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
    )
}

// =====================================================================================
@Composable
fun MainShell(vm: Assistant) {
    var tab by remember { mutableStateOf(Tab.CHAT) }
    val mode by vm.mode.collectAsState()
    val brain by vm.brain.state.collectAsState()
    val level by vm.micLevel.collectAsState()
    val reminders by vm.store.reminders.collectAsState()
    val memories by vm.store.memories.collectAsState()

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Header(mode, brain, level, onOrb = { vm.stopAll() })
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.CHAT -> ChatScreen(vm, mode, brain)
                Tab.MEM -> MemoryScreen(vm)
                Tab.REM -> RemindersScreen(vm)
                Tab.CTRL -> ControlScreen(vm)
                Tab.SET -> SettingsScreen(vm, brain)
            }
        }
        NavigationBar(containerColor = Color(0xFF08080B), tonalElevation = 0.dp) {
            Tab.entries.forEach { t ->
                val badge = when (t) {
                    Tab.MEM -> memories.size
                    Tab.REM -> reminders.count { !it.done && !it.fired }
                    else -> 0
                }
                NavigationBarItem(
                    selected = tab == t,
                    onClick = { tab = t },
                    icon = {
                        Box {
                            Icon(t.icon, t.label)
                            if (badge > 0) Box(
                                Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(16.dp).clip(CircleShape).background(C.Red),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (badge > 99) "99" else "$badge", fontSize = 9.sp, color = Color.White) }
                        }
                    },
                    label = { Text(t.label, fontSize = 11.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = C.Txt, selectedTextColor = C.Txt, indicatorColor = C.RedDim,
                        unselectedIconColor = C.Mut, unselectedTextColor = C.Mut,
                    ),
                )
            }
        }
    }
}

@Composable
private fun Header(mode: Mode, brain: BrainState, level: Float, onOrb: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val (status, color) = when {
        brain is BrainState.Loading -> brain.step to C.Warn
        brain is BrainState.Error -> "Error del cerebro" to C.Warn
        brain !is BrainState.Ready -> "Apagado" to C.Mut
        mode == Mode.LISTENING -> "Escuchando…" to C.Red
        mode == Mode.THINKING -> "Pensando…" to C.Warn
        mode == Mode.SPEAKING -> "Hablando…" to C.Red
        else -> "En línea · offline · ${brain.backend}" to C.Ok
    }
    Row(
        Modifier.fillMaxWidth().background(C.Bg).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Orb(mode, level, Modifier.size(48.dp).clickable { onOrb() })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("IRVIN DACROHX", style = Hud.copy(color = C.Txt, fontSize = 16.sp, letterSpacing = 2.sp), maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text(status.uppercase(), color = C.Mut, fontSize = 11.sp, letterSpacing = 1.sp, maxLines = 1)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(SimpleDateFormat("HH:mm", ES).format(Date(now)), color = C.Txt, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(SimpleDateFormat("EEE d", ES).format(Date(now)).uppercase(), color = C.Mut, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
}

// =====================================================================================
private val SUGGESTIONS = listOf(
    "¿Qué sabes de mí?",
    "Recuérdame en 10 minutos tomar agua",
    "Pon música de mi teléfono",
    "Prende la linterna",
    "¿Cuánta batería tengo?",
    "Cuánto es el 15% de 1,280",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(vm: Assistant, mode: Mode, brain: BrainState) {
    val chat by vm.store.chat.collectAsState()
    val settings by vm.store.settings.collectAsState()
    val image by vm.pendingImage.collectAsState()
    val partial by vm.partialSpeech.collectAsState()
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var showAttach by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val m = maxOf(info.size.width, info.size.height)
                    if (m > 1600) d.setTargetSampleSize(m / 1024)
                }
                vm.attachImage(bmp)
            } catch (e: Exception) { vm.toast("No pude abrir la imagen.") }
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp -> vm.attachImage(bmp) }

    val lastText = chat.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(chat.size, lastText) { if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1) }

    Column(Modifier.fillMaxSize()) {
        if (brain is BrainState.Error) {
            Panel(Modifier.padding(12.dp)) {
                Text(brain.message, color = C.Warn, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                RedButton("Reintentar") { vm.retryBrain() }
            }
        }
        if (chat.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(20.dp))
                Orb(mode, 0f, Modifier.size(170.dp))
                Spacer(Modifier.height(22.dp))
                Text(
                    if (settings.name.isBlank()) "A sus órdenes." else "A sus órdenes, ${settings.name}.",
                    color = C.Txt, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (brain is BrainState.Loading) "Encendiendo mi cerebro… la primera vez tarda unos segundos."
                    else "Háblame, escríbeme o enséñame una foto. Todo se queda en tu teléfono.",
                    color = C.Mut, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SUGGESTIONS.forEach { s -> Chip(s) { vm.sendText(s) } }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(chat, key = { it.id }) { m -> Bubble(m, onSpeak = { vm.speak(m.text) }) }
            }
        }

        // ---------- barra de entrada ----------
        AnimatedVisibility(image != null) {
            image?.let { bmp ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(bmp.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(10.dp))
                    Text("Imagen lista. Pregúntame algo sobre ella.", color = C.Mut, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.clearImage() }) { Icon(Icons.Filled.Close, "Quitar", tint = C.Mut) }
                }
            }
        }
        if (partial.isNotEmpty()) Text(partial, color = C.Mut, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp))
        Row(
            Modifier.fillMaxWidth().background(Color(0xF20A0A0E)).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                RoundIcon(Icons.Filled.Add, "Adjuntar", bg = C.Panel2) { showAttach = true }
                if (showAttach) AlertDialog(
                    onDismissRequest = { showAttach = false },
                    containerColor = C.Panel,
                    title = { Text("Enséñale algo a Irvin") },
                    text = {
                        Column {
                            AttachRow(Icons.Filled.PhotoCamera, "Tomar foto") { showAttach = false; cameraLauncher.launch(null) }
                            AttachRow(Icons.Filled.Image, "Elegir de la galería") {
                                showAttach = false
                                galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showAttach = false }) { Text("Cerrar") } },
                )
            }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Habla con Irvin…", color = C.Mut) },
                modifier = Modifier.weight(1f).heightIn(min = 50.dp, max = 150.dp),
                shape = RoundedCornerShape(25.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = C.Red.copy(alpha = 0.7f), unfocusedBorderColor = C.Line,
                    focusedContainerColor = C.Panel, unfocusedContainerColor = C.Panel, cursorColor = C.Red,
                ),
            )
            Spacer(Modifier.width(6.dp))
            when {
                mode == Mode.THINKING || mode == Mode.SPEAKING ->
                    RoundIcon(Icons.Filled.Stop, "Detener", bg = C.Panel2) { vm.stopAll() }
                input.isNotBlank() || image != null ->
                    RoundIcon(Icons.AutoMirrored.Filled.Send, "Enviar", bg = C.Red) { vm.sendText(input); input = "" }
                else ->
                    RoundIcon(Icons.Filled.Mic, "Hablar", bg = if (mode == Mode.LISTENING) C.Red else C.Panel2, big = true) { vm.toggleListening() }
            }
        }
        if (mode == Mode.LISTENING) Text(
            "Escuchando… toca el micrófono para enviar",
            color = C.Red2, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AttachRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.Red)
        Spacer(Modifier.width(12.dp))
        Text(label, color = C.Txt, fontSize = 16.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bubble(m: ChatMsg, onSpeak: () -> Unit) {
    when (m.role) {
        "sys" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                m.text, color = C.Txt, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(C.RedDim)
                    .border(1.dp, C.Red.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(C.Red, Color(0xFFC4122A))))
                    .padding(12.dp)
            ) {
                m.image?.let { path ->
                    val bmp = remember(path) { try { BitmapFactory.decodeFile(path) } catch (e: Exception) { null } }
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Text(m.text, color = Color.White, fontSize = 16.sp, lineHeight = 21.sp)
            }
        }
        else -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(
                Modifier.widthIn(max = 310.dp).clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)).background(C.Panel)
                    .border(1.dp, if (m.error) C.Warn.copy(alpha = 0.5f) else C.Line, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                    .padding(12.dp)
            ) {
                Text("IRVIN", style = Hud.copy(fontSize = 9.sp))
                Spacer(Modifier.height(3.dp))
                if (m.pending && m.text.isEmpty()) TypingDots()
                else Text(md(m.text), color = if (m.error) C.Warn else C.Txt, fontSize = 16.sp, lineHeight = 21.sp)
                if (m.actions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.actions.forEach { a ->
                            Text(
                                a, color = C.Red2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(CircleShape).background(C.RedDim).padding(horizontal = 9.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
            if (!m.pending && !m.error) IconButton(onClick = onSpeak, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Filled.VolumeUp, "Escuchar", tint = C.Mut, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Markdown mínimo: **negritas** y viñetas. */
private fun md(s: String): androidx.compose.ui.text.AnnotatedString {
    val text = s.replace(Regex("(?m)^\\s*[-*]\\s+"), "• ").replace(Regex("(?m)^#{1,6}\\s*"), "")
    val b = androidx.compose.ui.text.AnnotatedString.Builder()
    val r = Regex("\\*\\*(.+?)\\*\\*")
    var last = 0
    for (m in r.findAll(text)) {
        b.append(text.substring(last, m.range.first))
        b.pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold))
        b.append(m.groupValues[1])
        b.pop()
        last = m.range.last + 1
    }
    b.append(text.substring(last))
    return b.toAnnotatedString()
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.2f, 1f,
                infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse),
                label = "d$i",
            )
            Box(Modifier.size(8.dp).clip(CircleShape).background(C.Red.copy(alpha = a)))
        }
    }
}

// =====================================================================================
@Composable
fun MemoryScreen(vm: Assistant) {
    val memories by vm.store.memories.collectAsState()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("BANCO DE MEMORIA", style = Hud)
        Text("Lo que Irvin sabe de ti. Se llena solo cuando le cuentas cosas.", color = C.Mut, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(input, { input = it }, "Ej. Mi color favorito es el rojo", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            RoundIcon(Icons.Filled.Add, "Agregar", bg = C.Red) { vm.addMemory(input); input = "" }
        }
        Spacer(Modifier.height(12.dp))
        if (memories.isEmpty()) Empty("Aún no sé nada de ti. Cuéntame algo en el chat.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(memories.reversed(), key = { it.id }) { m ->
                ItemRow(m.text, SimpleDateFormat("d MMM yyyy", ES).format(Date(m.t))) { vm.deleteMemory(m.id) }
            }
        }
    }
}

// =====================================================================================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemindersScreen(vm: Assistant) {
    val reminders by vm.store.reminders.collectAsState()
    var text by remember { mutableStateOf("") }
    var due by remember { mutableLongStateOf(System.currentTimeMillis() + 3_600_000L) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var pickedDay by remember { mutableLongStateOf(0L) }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("RECORDATORIOS", style = Hud)
        Text("Pídeselos a Irvin en el chat o créalos aquí. Suenan aunque la app esté cerrada.", color = C.Mut, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        Panel {
            Field(text, { text = it }, "¿Qué te recuerdo?", Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("+15 min") { due = System.currentTimeMillis() + 15 * 60_000L }
                Chip("+1 hora") { due = System.currentTimeMillis() + 3_600_000L }
                Chip("Mañana 8:00") {
                    due = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                    }.timeInMillis
                }
                Chip("📅 Elegir…") { showDate = true }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sonará: " + SimpleDateFormat("EEE d MMM, h:mm a", ES).format(Date(due)), color = C.Txt, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Button(onClick = { vm.addReminder(text, due); if (text.isNotBlank() && due > System.currentTimeMillis()) text = "" }, colors = ButtonDefaults.buttonColors(containerColor = C.Red)) { Text("Crear") }
            }
        }
        Spacer(Modifier.height(12.dp))
        val sorted = reminders.sortedWith(compareBy({ it.done }, { it.due }))
        if (sorted.isEmpty()) Empty("Sin recordatorios.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorted, key = { it.id }) { r ->
                val tag = when { r.done -> "hecho"; r.fired -> "sonó"; else -> "pendiente" }
                ItemRow(
                    r.text, SimpleDateFormat("EEE d MMM, h:mm a", ES).format(Date(r.due)) + "  ·  " + tag,
                    dim = r.done,
                    onClick = if (r.fired && !r.done) ({ vm.markReminderDone(r.id) }) else null,
                ) { vm.deleteReminder(r.id) }
            }
        }
    }

    if (showDate) {
        val ds = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = { pickedDay = ds.selectedDateMillis ?: System.currentTimeMillis(); showDate = false; showTime = true }) { Text("Siguiente") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancelar") } },
        ) { DatePicker(ds) }
    }
    if (showTime) {
        val ts = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTime = false },
            containerColor = C.Panel,
            title = { Text("¿A qué hora?") },
            text = { TimePicker(ts) },
            confirmButton = {
                TextButton(onClick = {
                    // el DatePicker entrega medianoche UTC del día elegido
                    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = pickedDay }
                    due = Calendar.getInstance().apply {
                        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), ts.hour, ts.minute, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    showTime = false
                }) { Text("Listo") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancelar") } },
        )
    }
}

// =====================================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlScreen(vm: Assistant) {
    val settings by vm.store.settings.collectAsState()
    var torch by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(vm.device.battery()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { battery = vm.device.battery() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("CONTROL DEL MUNDO REAL", style = Hud)
        Text("También puedes pedírselo a Irvin: “sube el volumen de la tele 3 veces”.", color = C.Mut, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.SettingsRemote, null, tint = C.Red)
                Spacer(Modifier.width(8.dp))
                Text("Control de TV infrarrojo", color = C.Txt, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(if (vm.ir.available) "IR ✓" else "Sin IR", color = if (vm.ir.available) C.Ok else C.Warn, fontSize = 13.sp)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.ir.brands.forEach { b ->
                    Chip(b.label, selected = settings.tvBrand == b.id) { vm.store.updateSettings { it.copy(tvBrand = b.id) } }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                RemoteBtn("⏻", big = true, red = true) { vm.sendIr(IrBlaster.Action.POWER) }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RemoteBtn("VOL +") { vm.sendIr(IrBlaster.Action.VOL_UP) }
                    Spacer(Modifier.height(8.dp))
                    RemoteBtn("VOL −") { vm.sendIr(IrBlaster.Action.VOL_DOWN) }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(26.dp))
                    RemoteBtn("🔇") { vm.sendIr(IrBlaster.Action.MUTE) }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RemoteBtn("CH +") { vm.sendIr(IrBlaster.Action.CH_UP) }
                    Spacer(Modifier.height(8.dp))
                    RemoteBtn("CH −") { vm.sendIr(IrBlaster.Action.CH_DOWN) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        MusicPanel(vm)
        Spacer(Modifier.height(12.dp))
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FlashlightOn, null, tint = C.Red)
                Spacer(Modifier.width(8.dp))
                Text("Linterna", color = C.Txt, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = torch,
                    onCheckedChange = { torch = it; vm.toast(vm.device.torch(it)) },
                    colors = SwitchDefaults.colors(checkedTrackColor = C.Red),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Panel(Modifier.clickable { tick++ }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BatteryFull, null, tint = C.Red)
                Spacer(Modifier.width(8.dp))
                Text(battery, color = C.Txt, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun MusicPanel(vm: Assistant) {
    val now by vm.music.now.collectAsState()
    var query by remember { mutableStateOf("") }
    var vol by remember { mutableStateOf(vm.music.volume().toFloat()) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MusicNote, null, tint = C.Red)
            Spacer(Modifier.width(8.dp))
            Text(now.track?.label ?: "Música del teléfono", color = C.Txt, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(query, { query = it }, "Canción o artista (vacío = aleatorio)", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            RoundIcon(Icons.Filled.PlayArrow, "Reproducir", bg = C.Red) {
                val hits = vm.music.search(query)
                if (hits.isEmpty()) vm.toast("No encontré esa música en el teléfono.") else vm.music.playList(hits)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            RoundIcon(Icons.Filled.SkipPrevious, "Anterior", bg = C.Panel2) { if (vm.music.ownActive()) vm.music.previous() else vm.music.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            RoundIcon(if (now.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Pausa", bg = C.Panel2) {
                if (vm.music.ownActive()) { if (now.playing) vm.music.pause() else vm.music.resume() }
                else vm.music.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            }
            RoundIcon(Icons.Filled.SkipNext, "Siguiente", bg = C.Panel2) { if (vm.music.ownActive()) vm.music.next() else vm.music.mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT) }
            RoundIcon(Icons.Filled.Stop, "Detener", bg = C.Panel2) { vm.music.stop() }
        }
        Spacer(Modifier.height(6.dp))
        Text("Volumen ${vol.toInt()}%", color = C.Mut, fontSize = 13.sp)
        Slider(value = vol, onValueChange = { vol = it; vm.music.setVolume(it.toInt()) }, valueRange = 0f..100f)
    }
}

@Composable
private fun RemoteBtn(label: String, big: Boolean = false, red: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (big) 76.dp else 64.dp).clip(CircleShape)
            .background(if (red) C.Red else C.Panel2).border(1.dp, if (red) C.Red else C.Line, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (big) 26.sp else 14.sp) }
}

// =====================================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: Assistant, brain: BrainState) {
    val s by vm.store.settings.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmModel by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("CONFIGURACIÓN", style = Hud)
        Label("¿Cómo te llamo?")
        Field(s.name, { v -> vm.store.updateSettings { it.copy(name = v.take(40)) } }, "Tu nombre o apodo", Modifier.fillMaxWidth())

        Label("Personalidad")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PERSONAS.forEach { (k, v) -> Chip(k, selected = s.persona == v) { vm.store.updateSettings { it.copy(persona = v) } } }
        }
        Spacer(Modifier.height(8.dp))
        Field(s.persona, { v -> vm.store.updateSettings { it.copy(persona = v.take(600)) } }, "Describe cómo quieres que te hable", Modifier.fillMaxWidth(), single = false)
        TextButton(onClick = { vm.resetConversation(true); vm.toast("Personalidad aplicada.") }) { Text("Aplicar personalidad", color = C.Red2) }

        Label("Voz")
        VoicePanel(vm, s)

        Label("Velocidad")
        SpeedPanel(vm, s)

        Label("Manos libres · “Oye Irvin”")
        WakePanel(vm, s.wakeWord)

        Label("Cerebro")
        Panel {
            val info = when (brain) {
                is BrainState.Ready -> "${vm.loadedLabel} · ${brain.backend}\nVoz directa: ${if (brain.audio) "sí" else "no"} · Visión: ${if (brain.vision) "sí" else "no"}"
                is BrainState.Loading -> brain.step
                is BrainState.Error -> brain.message
                else -> "Apagado"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Psychology, null, tint = C.Red)
                Spacer(Modifier.width(8.dp))
                Text(info, color = C.Txt, fontSize = 14.sp)
            }
            Text("Tamaño: ${"%.2f".format(vm.models.modelFile.length() / 1e9)} GB · 100% offline", color = C.Mut, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { vm.retryBrain() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PowerSettingsNew, null); Spacer(Modifier.width(6.dp)); Text("Reiniciar cerebro")
            }
        }

        Label("Zona de peligro")
        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text("Nueva conversación (borrar chat)", color = C.Red2) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { confirmModel = true }, modifier = Modifier.fillMaxWidth()) { Text("Borrar el cerebro descargado", color = C.Red2) }
        Spacer(Modifier.height(20.dp))
        Text("IRVIN DACROHX v1.0 · hecho para RedMagic 11 Pro", color = C.Mut, fontSize = 12.sp)
        Text("Tip: en Ajustes de RedMagic asigna la Magic Key para abrir Irvin, o mantén presionado su ícono → “Hablar con Irvin”.", color = C.Mut, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
    }

    if (confirmClear) Confirm("¿Borrar toda la conversación? Tus recuerdos y recordatorios se quedan.", { confirmClear = false }) { vm.newChat() }
    if (confirmModel) Confirm("¿Borrar el modelo (3.7 GB)? Tendrás que descargarlo otra vez.", { confirmModel = false }) {
        vm.deleteModel()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoicePanel(vm: Assistant, s: com.irvin.dacrohx.data.Settings) {
    val ctx = LocalContext.current
    val voices by vm.speaker.voices.collectAsState()
    LaunchedEffect(Unit) { vm.speaker.refreshVoices() }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Responder en voz alta", color = C.Txt, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(s.autoSpeak, { v -> vm.store.updateSettings { it.copy(autoSpeak = v) }; if (!v) vm.stopAll() }, colors = SwitchDefaults.colors(checkedTrackColor = C.Red))
        }
        Spacer(Modifier.height(8.dp))
        Text("Elige la voz (toca para escucharla):", color = C.Mut, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        if (voices.isEmpty()) Text("No encontré voces en español instaladas.", color = C.Warn, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Predeterminada", selected = s.voiceName.isEmpty()) {
                vm.store.updateSettings { it.copy(voiceName = "") }
                vm.speak("Hola, soy Irvin Dacrohx.")
            }
            voices.forEach { v ->
                Chip(v.label, selected = s.voiceName == v.name) {
                    vm.store.updateSettings { it.copy(voiceName = v.name) }
                    vm.speaker.setVoice(v.name)
                    vm.speak("Hola, soy Irvin Dacrohx. ¿Te gusta esta voz?")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Tono: ${if (s.pitch < 0.9f) "grave" else if (s.pitch > 1.15f) "agudo" else "normal"}", color = C.Mut, fontSize = 14.sp)
        Slider(
            value = s.pitch, onValueChange = { v -> vm.store.updateSettings { it.copy(pitch = v) } },
            valueRange = 0.6f..1.6f, onValueChangeFinished = { vm.speak("Así sueno ahora.") },
        )
        Text("Velocidad: ${"%.2f".format(s.speechRate)}×", color = C.Mut, fontSize = 14.sp)
        Slider(
            value = s.speechRate, onValueChange = { v -> vm.store.updateSettings { it.copy(speechRate = v) } },
            valueRange = 0.7f..1.8f, onValueChangeFinished = { vm.speak("Así de rápido hablo.") },
        )
        OutlinedButton(
            onClick = {
                try { ctx.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { vm.toast("Abre Ajustes → Accesibilidad → Salida de texto a voz.") }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Descargar más voces (hombre / mujer)") }
        Text(
            "En “Motor de Google” → Instalar datos de voz → Español (México / EE.UU.) puedes bajar voces de hombre y de mujer. Luego regresa aquí y elige.",
            color = C.Mut, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SpeedPanel(vm: Assistant, s: com.irvin.dacrohx.data.Settings) {
    val turboState by vm.turbo.state.collectAsState()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Modo turbo ⚡", color = C.Txt, fontSize = 16.sp)
                Text(
                    when (val t = turboState) {
                        is ModelState.Downloading -> "Descargando… ${if (t.total > 0) t.done * 100 / t.total else 0}%"
                        is ModelState.Failed -> t.reason
                        is ModelState.Ready -> if (s.turbo) "Activo: cerebro E2B, casi el doble de rápido" else "Descargado (apagado)"
                        else -> "Cerebro más ligero (2.6 GB): mucho más rápido, un poco menos listo"
                    },
                    color = if (s.turbo && turboState is ModelState.Ready) C.Ok else C.Mut, fontSize = 13.sp,
                )
            }
            Switch(s.turbo, { vm.setTurbo(it) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Red))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voz rápida", color = C.Txt, fontSize = 16.sp)
                Text(
                    "Convierte tu voz en texto en el teléfono antes de pensar (respuesta más rápida). Apágala si quieres que Irvin escuche el audio directo.",
                    color = C.Mut, fontSize = 13.sp,
                )
            }
            Switch(s.fastVoice, { v -> vm.store.updateSettings { it.copy(fastVoice = v) } }, colors = SwitchDefaults.colors(checkedTrackColor = C.Red))
        }
        Text("Debajo de cada respuesta verás ⚡ con el tiempo: primera palabra · total.", color = C.Mut, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun WakePanel(vm: Assistant, enabled: Boolean) {
    val ctx = LocalContext.current
    val wakeState by vm.wake.state.collectAsState()
    val running by com.irvin.dacrohx.device.WakeWordService.running.collectAsState()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Escuchar “Oye Irvin”", color = C.Txt, fontSize = 16.sp)
                Text(
                    when {
                        wakeState is com.irvin.dacrohx.device.WakeModel.State.Downloading ->
                            "Descargando oído… ${(wakeState as com.irvin.dacrohx.device.WakeModel.State.Downloading).pct}%"
                        wakeState is com.irvin.dacrohx.device.WakeModel.State.Unpacking -> "Instalando oído…"
                        wakeState is com.irvin.dacrohx.device.WakeModel.State.Failed ->
                            (wakeState as com.irvin.dacrohx.device.WakeModel.State.Failed).reason
                        enabled && running -> "Activo · funciona con la pantalla apagada"
                        enabled -> "Activado (se inicia al abrir la app)"
                        else -> "Apagado"
                    },
                    color = if (enabled && running) C.Ok else C.Mut, fontSize = 13.sp,
                )
            }
            Switch(enabled, { vm.setWakeWord(it) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Red))
        }
        Text(
            "Mientras está activo verás una notificación fija y el punto verde del micrófono. " +
                "Después de reiniciar el teléfono, abre Irvin una vez para reactivarlo.",
            color = C.Mut, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                try {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(android.net.Uri.parse("package:" + ctx.packageName))
                    )
                } catch (e: Exception) { vm.toast("Abre Ajustes → Batería y quita la optimización a Irvin.") }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Que RedMagic no lo apague (batería)") }
    }
}

// =====================================================================================
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Panel)
            .border(1.dp, C.Line, RoundedCornerShape(14.dp)).padding(14.dp)
    ) { content() }
}

@Composable
private fun Label(t: String) = Text(t.uppercase(), color = C.Mut, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))

@Composable
private fun Empty(t: String) = Text(
    t, color = C.Mut, fontSize = 15.sp, textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().border(1.dp, C.Line, RoundedCornerShape(12.dp)).padding(24.dp),
)

@Composable
fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Color.White else Color(0xFFD9D9E2), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(if (selected) C.RedDim else C.Panel)
            .border(1.dp, if (selected) C.Red else C.Line, CircleShape).clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    )
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, bg: Color, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(if (big) 54.dp else 50.dp).clip(CircleShape).background(bg).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White) }
}

@Composable
private fun RedButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = C.Red)) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, single: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier, singleLine = single,
        placeholder = { Text(hint, color = C.Mut) },
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.Red.copy(alpha = 0.7f), unfocusedBorderColor = C.Line,
            focusedContainerColor = C.Bg2, unfocusedContainerColor = C.Bg2, cursorColor = C.Red,
        ),
    )
}

@Composable
private fun ItemRow(text: String, meta: String, dim: Boolean = false, onClick: (() -> Unit)? = null, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Panel).border(1.dp, C.Line, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, color = if (dim) C.Mut else C.Txt, fontSize = 16.sp)
            Text(meta, color = C.Mut, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Borrar", tint = C.Mut) }
    }
}

@Composable
private fun Confirm(text: String, onDismiss: () -> Unit, onYes: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Panel,
        title = { Text("Confirmar") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onYes() }) { Text("Sí, borrar", color = C.Red2) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
