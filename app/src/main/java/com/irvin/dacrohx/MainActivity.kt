package com.irvin.dacrohx

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.irvin.dacrohx.ai.BrainState
import com.irvin.dacrohx.ui.IrvinRoot
import com.irvin.dacrohx.ui.IrvinTheme
import com.irvin.dacrohx.ui.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
        setContent { IrvinTheme { IrvinRoot(vm) } }
        handleIntent(intent)
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
