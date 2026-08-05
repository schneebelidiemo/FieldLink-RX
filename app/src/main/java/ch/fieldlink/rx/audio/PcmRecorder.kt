package ch.fieldlink.rx.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import ch.fieldlink.rx.model.AudioCaptureInfo
import ch.fieldlink.rx.model.AudioCaptureSource
import kotlin.math.max

class PcmRecorder(
    private val context: Context,
    private val selectedDeviceId: Int?,
) : AutoCloseable {
    companion object {
        const val SAMPLE_RATE = 48_000
        const val BLOCK_SAMPLES = 2_048
    }

    private var audioRecord: AudioRecord? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var selectedSource = MediaRecorder.AudioSource.DEFAULT

    fun start(): AudioRecord {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is missing."
        }

        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "48 kHz mono audio input is not supported." }

        val manager = context.getSystemService(AudioManager::class.java)
        val supportsUnprocessed = manager
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.toBooleanStrictOrNull() == true
        val sources = buildList {
            if (supportsUnprocessed) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }.distinct()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val bufferSize = max(minimum * 4, BLOCK_SAMPLES * 8)
        var lastFailure: Throwable? = null
        var record: AudioRecord? = null
        for (source in sources) {
            val candidate = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            }.onFailure { lastFailure = it }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                selectedSource = source
                record = candidate
                break
            }
            candidate.release()
        }
        val initializedRecord = record
            ?: throw IllegalStateException("AudioRecord could not be initialized at 48 kHz.", lastFailure)

        val preferred = AudioInputRepository.resolve(context, selectedDeviceId)
        if (selectedDeviceId != null) {
            check(preferred != null) { "The selected audio input is no longer available." }
            check(initializedRecord.setPreferredDevice(preferred)) { "Android rejected the selected audio input." }
        }

        disableAudioEffects(initializedRecord.audioSessionId)
        initializedRecord.startRecording()
        check(initializedRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Audio capture did not start." }
        check(initializedRecord.sampleRate == SAMPLE_RATE) {
            "Android opened the input at ${initializedRecord.sampleRate} Hz instead of 48 kHz."
        }
        audioRecord = initializedRecord
        return initializedRecord
    }

    fun captureInfo(): AudioCaptureInfo {
        val record = checkNotNull(audioRecord) { "Audio capture has not started." }
        val routed = record.routedDevice
        return AudioCaptureInfo(
            sampleRateHz = record.sampleRate,
            source = sourceName(selectedSource),
            routedDevice = routed?.productName?.toString().orEmpty().ifBlank {
                AudioInputRepository.resolve(context, selectedDeviceId)?.productName?.toString().orEmpty()
            },
        )
    }

    private fun sourceName(source: Int): AudioCaptureSource = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> AudioCaptureSource.UNPROCESSED
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> AudioCaptureSource.VOICE_RECOGNITION
        MediaRecorder.AudioSource.MIC -> AudioCaptureSource.MICROPHONE
        else -> AudioCaptureSource.OTHER
    }

    private fun disableAudioEffects(sessionId: Int) {
        if (AutomaticGainControl.isAvailable()) {
            automaticGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = false }
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = false }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = false }
        }
    }

    override fun close() {
        audioRecord?.let { record ->
            runCatching { record.stop() }
            record.release()
        }
        audioRecord = null
        automaticGainControl?.release()
        noiseSuppressor?.release()
        echoCanceler?.release()
        automaticGainControl = null
        noiseSuppressor = null
        echoCanceler = null
    }
}
