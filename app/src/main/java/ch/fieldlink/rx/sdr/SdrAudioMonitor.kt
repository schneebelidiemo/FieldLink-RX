package ch.fieldlink.rx.sdr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max

internal class SdrAudioMonitor : AutoCloseable {
    private var track: AudioTrack? = null

    fun write(samples: FloatArray, muted: Boolean) {
        if (muted || samples.isEmpty()) {
            track?.pause()
            track?.flush()
            return
        }
        val output = track ?: createTrack().also { track = it }
        if (output.playState != AudioTrack.PLAYSTATE_PLAYING) output.play()
        output.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
    }

    private fun createTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            SdrDemodulator.OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        check(minimum > 0) { "48 kHz audio monitoring is not supported." }
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SdrDemodulator.OUTPUT_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minimum * 2, 16_384))
            .build()
    }

    override fun close() {
        track?.let { output ->
            runCatching { output.stop() }
            output.release()
        }
        track = null
    }
}
