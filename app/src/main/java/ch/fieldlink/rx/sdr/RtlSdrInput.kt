package ch.fieldlink.rx.sdr

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import ch.fieldlink.rx.audio.AudioInputRepository
import ch.fieldlink.rx.model.AudioCaptureInfo
import ch.fieldlink.rx.model.AudioCaptureSource
import ch.fieldlink.rx.model.SdrSettings
import ch.fieldlink.rx.model.SignalSnapshot
import ch.fieldlink.rx.model.SpectrumFrame
import java.util.concurrent.atomic.AtomicBoolean

class RtlSdrInput(
    private val context: Context,
    private val inputId: Int?,
) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = SdrDemodulator.INPUT_SAMPLE_RATE
        private const val DC_AVOIDANCE_OFFSET_HZ = 12_000

        internal fun isValidNativeHandle(handle: Long): Boolean = handle != 0L
    }

    private val stopping = AtomicBoolean(false)
    private val demodulator = SdrDemodulator()
    private val spectrumAnalyzer = RfSpectrumAnalyzer()
    private val monitor = SdrAudioMonitor()
    private var connection: UsbDeviceConnection? = null
    @Volatile private var nativeHandle = 0L
    private var configuredSettings: SdrSettings? = null

    fun captureInfo(): AudioCaptureInfo {
        val device = AudioInputRepository.resolveRtlSdr(context, inputId)
        return AudioCaptureInfo(
            sampleRateHz = SAMPLE_RATE,
            source = AudioCaptureSource.RTL_SDR,
            routedDevice = device?.productName ?: "RTL-SDR Blog V4",
        )
    }

    fun run(
        settingsProvider: () -> SdrSettings,
        onAudio: (FloatArray) -> Unit,
        onSignal: (SignalSnapshot) -> Unit,
        onSpectrum: (SpectrumFrame) -> Unit,
    ) {
        val device = checkNotNull(AudioInputRepository.resolveRtlSdr(context, inputId)) {
            "The selected RTL-SDR V4 is no longer connected."
        }
        val usbManager = context.getSystemService(UsbManager::class.java)
        check(usbManager.hasPermission(device)) { "USB permission for the RTL-SDR V4 is missing." }
        val openedConnection = checkNotNull(usbManager.openDevice(device)) {
            "Android could not open the RTL-SDR V4."
        }
        connection = openedConnection
        val openedHandle = NativeRtlSdrBridge.open(openedConnection.fileDescriptor, device.deviceName)
        if (!isValidNativeHandle(openedHandle)) {
            val error = NativeRtlSdrBridge.lastOpenError()
            openedConnection.close()
            connection = null
            error("The RTL-SDR V4 driver could not open the device (code $error).")
        }
        nativeHandle = openedHandle

        try {
            val initial = settingsProvider().validated()
            val initialTuning = tuningFor(initial.frequencyHz)
            val result = NativeRtlSdrBridge.configure(
                openedHandle,
                initialTuning.hardwareFrequencyHz,
                SAMPLE_RATE,
                initial.automaticGain,
                initial.manualGainPercent,
                initial.ppmCorrection,
            )
            check(result >= 0) { "The RTL-SDR V4 configuration failed (code $result)." }
            configuredSettings = initial

            val readResult = NativeRtlSdrBridge.run(
                openedHandle,
                NativeRtlSdrBridge.IqListener { iq ->
                    if (stopping.get()) return@IqListener
                    val settings = settingsProvider().validated()
                    applyChangedHardwareSettings(openedHandle, settings)
                    spectrumAnalyzer.add(iq, settings.frequencyHz)?.let(onSpectrum)
                    val audio = demodulator.process(iq, settings, tuningFor(settings.frequencyHz).offsetHz)
                    if (audio.samples.isNotEmpty()) {
                        onAudio(audio.samples)
                        monitor.write(audio.samples, settings.monitorMuted)
                    }
                    onSignal(SignalSnapshot(rmsDb = audio.rfLevelDb, peakDb = audio.rfLevelDb))
                    configuredSettings = settings
                },
            )
            if (!stopping.get()) check(readResult >= 0) { "RTL-SDR USB reception failed (code $readResult)." }
        } finally {
            finishNative()
        }
    }

    private fun applyChangedHardwareSettings(handle: Long, settings: SdrSettings) {
        val previous = configuredSettings ?: return
        if (settings.frequencyHz != previous.frequencyHz) {
            val result = NativeRtlSdrBridge.setFrequency(handle, tuningFor(settings.frequencyHz).hardwareFrequencyHz)
            check(result >= 0) { "RTL-SDR frequency change failed (code $result)." }
            demodulator.reset()
            spectrumAnalyzer.reset()
        }
        if (
            settings.automaticGain != previous.automaticGain ||
            settings.manualGainPercent != previous.manualGainPercent
        ) {
            val result = NativeRtlSdrBridge.setGain(
                handle,
                settings.automaticGain,
                settings.manualGainPercent,
            )
            check(result >= 0) { "RTL-SDR gain change failed (code $result)." }
        }
        if (settings.ppmCorrection != previous.ppmCorrection) {
            val result = NativeRtlSdrBridge.setPpm(handle, settings.ppmCorrection)
            check(result >= 0) { "RTL-SDR PPM correction failed (code $result)." }
        }
        if (settings.modulation != previous.modulation || settings.bandwidthHz != previous.bandwidthHz) {
            demodulator.reset()
        }
    }

    private fun SdrSettings.validated(): SdrSettings = copy(
        frequencyHz = frequencyHz.coerceIn(SdrSettings.MIN_FREQUENCY_HZ, SdrSettings.MAX_FREQUENCY_HZ),
        manualBandwidthHz = manualBandwidthHz.coerceIn(500, 200_000),
        manualGainPercent = manualGainPercent.coerceIn(0, 100),
        ppmCorrection = ppmCorrection.coerceIn(-100, 100),
        squelchThresholdDb = squelchThresholdDb.coerceIn(-120, 0),
    )

    private data class Tuning(val hardwareFrequencyHz: Long, val offsetHz: Int)

    private fun tuningFor(requestedFrequencyHz: Long): Tuning =
        if (requestedFrequencyHz <= SdrSettings.MAX_FREQUENCY_HZ - DC_AVOIDANCE_OFFSET_HZ) {
            Tuning(requestedFrequencyHz + DC_AVOIDANCE_OFFSET_HZ, DC_AVOIDANCE_OFFSET_HZ)
        } else {
            Tuning(requestedFrequencyHz - DC_AVOIDANCE_OFFSET_HZ, -DC_AVOIDANCE_OFFSET_HZ)
        }

    override fun close() {
        stopping.set(true)
        val handle = nativeHandle
        if (isValidNativeHandle(handle)) NativeRtlSdrBridge.cancel(handle)
        monitor.close()
    }

    private fun finishNative() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (isValidNativeHandle(handle)) NativeRtlSdrBridge.close(handle)
        connection?.close()
        connection = null
        monitor.close()
    }
}
