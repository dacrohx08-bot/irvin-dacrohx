package com.irvin.dacrohx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.irvin.dacrohx.ai.Assistant
import com.irvin.dacrohx.ai.BrainState
import com.irvin.dacrohx.device.WakeWordService
import com.irvin.dacrohx.ui.IrvinRoot
import com.irvin.dacrohx.ui.IrvinTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm by lazy { Assistant.get(this) }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startWakeIfEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        setContent { IrvinTheme { IrvinRoot(vm) } }
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        startWakeIfEnabled()
    }

    /** Android solo deja iniciar la escucha con la app en pantalla; por eso se reactiva aquí. */
    private fun startWakeIfEnabled() {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (ok && vm.store.settings.value.wakeWord && vm.wake.isReady() && !WakeWordService.running.value) {
            try { WakeWordService.start(this) } catch (_: Exception) {}
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Atajo "Hablar con Irvin": abre directo en modo escucha. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.irvin.dacrohx.VOZ") {
            intent.action = null
            lifecycleScope.launch {
                vm.brain.state.first { it is BrainState.Ready }
                vm.toggleListening()
            }
        }
    }
}
