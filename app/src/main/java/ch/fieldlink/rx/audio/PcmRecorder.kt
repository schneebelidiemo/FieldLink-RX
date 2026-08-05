package ch.fieldlink.rx.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
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

        val source = if (context.packageManager.hasSystemFeature("android.hardware.audio.pro")) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        val record = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minimum * 4, BLOCK_SAMPLES * 8))
            .build()

        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord could not be initialized." }

        val preferred = AudioInputRepository.resolve(context, selectedDeviceId)
        if (selectedDeviceId != null) {
            check(preferred != null) { "The selected audio input is no longer available." }
            check(record.setPreferredDevice(preferred)) { "Android rejected the selected audio input." }
        }

        disableAudioEffects(record.audioSessionId)
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Audio capture did not start." }
        audioRecord = record
        return record
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

