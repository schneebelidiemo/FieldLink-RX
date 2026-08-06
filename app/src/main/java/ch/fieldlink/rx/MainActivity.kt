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
import ch.fieldlink.rx.model.AudioCaptureMode
import ch.fieldlink.rx.model.CwSettings
import ch.fieldlink.rx.runtime.ReceiverRuntime
import ch.fieldlink.rx.ui.FieldLinkRxApp
import ch.fieldlink.rx.ui.FieldLinkRxTheme

class MainActivity : ComponentActivity() {
    private var pendingPassword: String? = null
    private var pendingInputId: Int? = null
    private var pendingMode: DecodeMode? = null
    private var pendingAudioCaptureMode: AudioCaptureMode? = null
    private val cwPreferences by lazy { getSharedPreferences("cw_settings", MODE_PRIVATE) }

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
        ReceiverRuntime.updateCwSettings(loadCwSettings())
        refreshInputs()
        setContent {
            FieldLinkRxTheme {
                FieldLinkRxApp(
                    onRefreshInputs = ::refreshInputs,
                    onSelectInput = ReceiverRuntime::selectInput,
                    onSelectMode = ReceiverRuntime::selectMode,
                    onSelectAudioCaptureMode = ReceiverRuntime::selectAudioCaptureMode,
                    onUpdateCwSettings = ::saveCwSettings,
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

    private fun loadCwSettings(): CwSettings = CwSettings(
        automaticSpeed = cwPreferences.getBoolean("automatic_speed", true),
        manualWpm = cwPreferences.getInt("manual_wpm", 18).coerceIn(3, 60),
        automaticTone = cwPreferences.getBoolean("automatic_tone", true),
        manualToneHz = cwPreferences.getInt("manual_tone_hz", 700).coerceIn(200, 1_500),
        automaticNoiseThreshold = cwPreferences.getBoolean("automatic_noise", true),
        sensitivity = cwPreferences.getInt("sensitivity", 50).coerceIn(0, 100),
        messageGapSeconds = cwPreferences.getInt("message_gap_seconds", 3).coerceIn(1, 15),
    )

    private fun saveCwSettings(settings: CwSettings) {
        ReceiverRuntime.updateCwSettings(settings)
        cwPreferences.edit()
            .putBoolean("automatic_speed", settings.automaticSpeed)
            .putInt("manual_wpm", settings.manualWpm)
            .putBoolean("automatic_tone", settings.automaticTone)
            .putInt("manual_tone_hz", settings.manualToneHz)
            .putBoolean("automatic_noise", settings.automaticNoiseThreshold)
            .putInt("sensitivity", settings.sensitivity)
            .putInt("message_gap_seconds", settings.messageGapSeconds)
            .apply()
    }

    private fun requestStart(
        password: String,
        inputId: Int?,
        mode: DecodeMode,
        audioCaptureMode: AudioCaptureMode,
    ) {
        pendingPassword = password
        pendingInputId = inputId
        pendingMode = mode
        pendingAudioCaptureMode = audioCaptureMode
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
        val audioCaptureMode = pendingAudioCaptureMode ?: return
        pendingPassword = null
        pendingInputId = null
        pendingMode = null
        pendingAudioCaptureMode = null
        ReceiverRuntime.configure(password, inputId, mode, audioCaptureMode)
        ReceiverService.start(this)
    }
}
