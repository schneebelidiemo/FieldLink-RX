package ch.fieldlink.rx

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import ch.fieldlink.rx.audio.AudioInputRepository
import ch.fieldlink.rx.audio.ReceiverService
import ch.fieldlink.rx.model.DecodeMode
import ch.fieldlink.rx.runtime.ReceiverRuntime
import ch.fieldlink.rx.ui.FieldLinkRxApp
import ch.fieldlink.rx.ui.FieldLinkRxTheme

class MainActivity : ComponentActivity() {
    private var pendingPassword: String? = null
    private var pendingInputId: Int? = null
    private var pendingMode: DecodeMode? = null

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) requestNotificationPermission() else ReceiverRuntime.error(getString(R.string.microphone_permission))
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) beginPendingSession() else ReceiverRuntime.error(getString(R.string.notification_permission))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshInputs()
        setContent {
            FieldLinkRxTheme {
                FieldLinkRxApp(
                    onRefreshInputs = ::refreshInputs,
                    onSelectInput = ReceiverRuntime::selectInput,
                    onSelectMode = ReceiverRuntime::selectMode,
                    onStart = ::requestStart,
                    onStop = { ReceiverService.stop(this) },
                    onNewSession = ReceiverRuntime::requireNewPassword,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInputs()
    }

    private fun refreshInputs() {
        ReceiverRuntime.setInputs(AudioInputRepository.list(this))
    }

    private fun requestStart(password: String, inputId: Int?, mode: DecodeMode) {
        pendingPassword = password
        pendingInputId = inputId
        pendingMode = mode
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            beginPendingSession()
        }
    }

    private fun beginPendingSession() {
        val password = pendingPassword ?: return
        val inputId = pendingInputId
        val mode = pendingMode ?: return
        pendingPassword = null
        pendingInputId = null
        pendingMode = null
        ReceiverRuntime.configure(password, inputId, mode)
        ReceiverService.start(this)
    }
}
