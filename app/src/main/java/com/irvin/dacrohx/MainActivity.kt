package com.irvin.dacrohx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.irvin.dacrohx.ai.Assistant
import com.irvin.dacrohx.ai.BrainState
import com.irvin.dacrohx.device.BubbleService
import com.irvin.dacrohx.device.WakeWordService
import com.irvin.dacrohx.ui.IrvinRoot
import com.irvin.dacrohx.ui.IrvinTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val vm by lazy { Assistant.get(this) }
    private var leftAt = 0L

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startServicesIfEnabled()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_AUDIO))
        setContent { IrvinTheme { IrvinRoot(vm, onUnlock = { authenticate() }) } }
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        vm.uiVisible = false
    }

    override fun onResume() {
        super.onResume()
        vm.uiVisible = true
        startServicesIfEnabled()
        com.irvin.dacrohx.device.NotifListener.ensureConnected(this)
        if (vm.locked.value) authenticate()
    }

    override fun onStop() {
        super.onStop()
        leftAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        // Se vuelve a bloquear si saliste más de 1 minuto.
        if (leftAt > 0 && System.currentTimeMillis() - leftAt > 60_000) vm.lock()
    }

    /** Pide la huella (o el PIN del teléfono) para entrar. */
    fun authenticate() {
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            vm.unlock(); return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { vm.unlock() }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Irvin Dacrohx")
                .setSubtitle("Pon tu huella para entrar")
                .setAllowedAuthenticators(auth)
                .build()
        )
    }

    /** Android solo deja iniciar la escucha y la burbuja con la app en pantalla; por eso se reactivan aquí. */
    private fun startServicesIfEnabled() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val s = vm.store.settings.value
        if (mic && s.wakeWord && vm.wake.isReady() && !WakeWordService.running.value) {
            try { WakeWordService.start(this) } catch (_: Exception) {}
        }
        if (mic && s.bubble && BubbleService.canDraw(this) && !BubbleService.running) {
            try { BubbleService.start(this) } catch (_: Exception) {}
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Atajo / widget / botón rápido "Hablar con Irvin": abre directo en modo escucha. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "com.irvin.dacrohx.VOZ") {
            intent.action = null
            // Escucha al instante; el cerebro termina de despertar mientras hablas.
            lifecycleScope.launch { kotlinx.coroutines.delay(300); vm.toggleListening() }
        }
    }
}
