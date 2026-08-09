package ch.fieldlink.rx

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import ch.fieldlink.rx.model.AudioInputKind
import ch.fieldlink.rx.model.CwSettings
import ch.fieldlink.rx.runtime.ReceiverRuntime
import ch.fieldlink.rx.ui.FieldLinkRxApp
import ch.fieldlink.rx.ui.FieldLinkRxTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val ACTION_USB_PERMISSION = "ch.fieldlink.rx.USB_PERMISSION"
    }

    private var pendingPassword: String? = null
    private var pendingInputId: Int? = null
    private var pendingMode: DecodeMode? = null
    private var pendingAudioCaptureMode: AudioCaptureMode? = null
    private val cwPreferences by lazy { getSharedPreferences("cw_settings", MODE_PRIVATE) }
    private var usbReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (!AudioInputRepository.isSupportedRtlSdr(device)) return
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                        getSystemService(UsbManager::class.java).hasPermission(device)
                    if (granted) requestNotificationPermission()
                    else ReceiverRuntime.error(getString(R.string.usb_permission_denied))
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshInputs(preferSdr = true)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detachedInputId = AudioInputRepository.inputIdFor(device)
                    val wasSelected = detachedInputId != null &&
                        ReceiverRuntime.state.value.selectedInputId == detachedInputId
                    refreshInputs()
                    if (wasSelected) {
                        ReceiverService.stop(this@MainActivity, getString(R.string.sdr_disconnected))
                    }
                }
            }
        }
    }

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
        registerUsbReceiver()
        ReceiverRuntime.updateCwSettings(loadCwSettings())
        refreshInputs(preferSdr = true)
        setContent {
            FieldLinkRxTheme {
                FieldLinkRxApp(
                    onRefreshInputs = { refreshInputs() },
                    onSelectInput = ReceiverRuntime::selectInput,
                    onSelectMode = ReceiverRuntime::selectMode,
                    onSelectAudioCaptureMode = ReceiverRuntime::selectAudioCaptureMode,
                    onUpdateCwSettings = ::saveCwSettings,
                    onUpdateSdrSettings = ReceiverRuntime::updateSdrSettings,
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.usbDevice()?.let { AudioInputRepository.isSupportedRtlSdr(it) } == true) {
            refreshInputs(preferSdr = true)
        }
    }

    override fun onDestroy() {
        if (usbReceiverRegistered) unregisterReceiver(usbReceiver)
        usbReceiverRegistered = false
        super.onDestroy()
    }

    private fun refreshInputs(preferSdr: Boolean = false) {
        val inputs = AudioInputRepository.list(this)
        ReceiverRuntime.setInputs(inputs)
        if (preferSdr) inputs.firstOrNull { it.kind == AudioInputKind.RTL_SDR }?.let {
            ReceiverRuntime.selectInput(it.id)
        }
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
        val selectedInput = ReceiverRuntime.state.value.inputs.firstOrNull { it.id == inputId }
        if (selectedInput?.kind == AudioInputKind.RTL_SDR) {
            requestUsbPermission(inputId)
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            requestNotificationPermission()
        }
    }

    private fun requestUsbPermission(inputId: Int?) {
        val device = AudioInputRepository.resolveRtlSdr(this, inputId)
        if (device == null) {
            ReceiverRuntime.error(getString(R.string.sdr_disconnected))
            return
        }
        val manager = getSystemService(UsbManager::class.java)
        if (manager.hasPermission(device)) {
            requestNotificationPermission()
            return
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        manager.requestPermission(device, permissionIntent)
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        usbReceiverRegistered = true
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

    private fun Intent.usbDevice(): UsbDevice? =
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
}
