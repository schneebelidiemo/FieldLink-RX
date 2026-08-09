package ch.fieldlink.rx.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.os.IBinder
import ch.fieldlink.rx.MainActivity
import ch.fieldlink.rx.R
import ch.fieldlink.rx.decoder.DecoderCoordinator
import ch.fieldlink.rx.model.AudioInputKind
import ch.fieldlink.rx.model.DecodedMessage
import ch.fieldlink.rx.runtime.ReceiverRuntime
import ch.fieldlink.rx.sdr.RtlSdrInput
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ReceiverService : Service() {
    companion object {
        private const val ACTION_START = "ch.fieldlink.rx.START"
        private const val ACTION_STOP = "ch.fieldlink.rx.STOP"
        private const val CHANNEL_RECEIVER = "fieldlink_receiver"
        private const val CHANNEL_MESSAGES = "fieldlink_messages"
        private const val FOREGROUND_ID = 705
        private const val MESSAGE_ID_BASE = 7_050
        private const val EXTRA_STOP_ERROR = "stop_error"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ReceiverService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context, errorMessage: String? = null) {
            context.startService(
                Intent(context, ReceiverService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_STOP_ERROR, errorMessage),
            )
        }
    }

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FieldLink-RX-Audio").apply { priority = Thread.MAX_PRIORITY }
    }
    private var recorder: PcmRecorder? = null
    private var sdrInput: RtlSdrInput? = null
    private var coordinator: DecoderCoordinator? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopReceiver(intent.getStringExtra(EXTRA_STOP_ERROR))
            return START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) startReceiver()
        return START_NOT_STICKY
    }

    private fun startReceiver() {
        val state = ReceiverRuntime.state.value
        val selectedInput = state.inputs.firstOrNull { it.id == state.selectedInputId }
        val usingSdr = selectedInput?.kind == AudioInputKind.RTL_SDR
        startForeground(
            FOREGROUND_ID,
            receiverNotification(),
            if (usingSdr) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            },
        )

        val password = ReceiverRuntime.passwordCopy()
        if (password == null) {
            ReceiverRuntime.error("The receiver was started without session configuration.")
            stopReceiver()
            return
        }

        val inputId = state.selectedInputId
        val selectedMode = state.selectedMode
        val audioCaptureMode = state.audioCaptureMode
        if (selectedMode == null) {
            ReceiverRuntime.error("The receiver was started without a selected decoder.")
            password.fill('\u0000')
            stopReceiver()
            return
        }
        executor.execute {
            try {
                val decoder = DecoderCoordinator(
                    password = password,
                    selectedMode = selectedMode,
                    onMessage = ::onDecodedMessage,
                    enableSpectrumAnalysis = !usingSdr || selectedMode.needsSpectrumAnalysis(),
                )
                coordinator = decoder
                if (usingSdr) {
                    val rtlSdr = RtlSdrInput(this, inputId)
                    sdrInput = rtlSdr
                    ReceiverRuntime.captureInfo(rtlSdr.captureInfo())
                    ReceiverRuntime.listening()
                    rtlSdr.run(
                        settingsProvider = { ReceiverRuntime.state.value.sdrSettings },
                        onAudio = decoder::process,
                        onSignal = { snapshot -> ReceiverRuntime.signal(snapshot, null) },
                        onSpectrum = ReceiverRuntime::rfSpectrum,
                    )
                } else {
                    val pcmRecorder = PcmRecorder(this, inputId, audioCaptureMode)
                    recorder = pcmRecorder
                    val audioRecord = pcmRecorder.start()
                    ReceiverRuntime.captureInfo(pcmRecorder.captureInfo())
                    ReceiverRuntime.listening()
                    audioLoop(audioRecord, pcmRecorder, decoder)
                }
            } catch (error: Throwable) {
                if (running.get()) ReceiverRuntime.error(error.message ?: getString(R.string.service_error))
            } finally {
                password.fill('\u0000')
                closePipeline()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun DecodeMode.needsSpectrumAnalysis(): Boolean = when (this) {
        DecodeMode.CW,
        DecodeMode.RTTY,
        DecodeMode.PSK31,
        DecodeMode.PSK63 -> true
        else -> false
    }

    private fun audioLoop(audioRecord: AudioRecord, pcmRecorder: PcmRecorder, decoder: DecoderCoordinator) {
        val pcm = ShortArray(PcmRecorder.BLOCK_SAMPLES)
        val samples = FloatArray(PcmRecorder.BLOCK_SAMPLES)
        var refreshedRoute = false
        while (running.get()) {
            val count = audioRecord.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
            if (count < 0) error("AudioRecord read failed with code $count.")
            if (count == 0) continue
            if (!refreshedRoute) {
                ReceiverRuntime.captureInfo(pcmRecorder.captureInfo())
                refreshedRoute = true
            }
            for (index in 0 until count) samples[index] = pcm[index] / 32768f
            decoder.process(if (count == samples.size) samples else samples.copyOf(count))
        }
    }

    private fun onDecodedMessage(message: DecodedMessage) {
        if (!ReceiverRuntime.addMessage(message)) return
        if (message.mode == ch.fieldlink.rx.model.DecodeMode.CW && message.uncertain) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(
            MESSAGE_ID_BASE + (message.id.hashCode() and 0x7ff),
            Notification.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_fieldlink)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_new_message))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun stopReceiver(errorMessage: String? = null) {
        if (!running.getAndSet(false)) {
            if (errorMessage != null) ReceiverRuntime.stopped(errorMessage)
            stopSelf()
            return
        }
        closePipeline()
        ReceiverRuntime.stopped(errorMessage)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closePipeline() {
        runCatching { recorder?.close() }
        recorder = null
        runCatching { sdrInput?.close() }
        sdrInput = null
        runCatching { coordinator?.close() }
        coordinator = null
    }

    override fun onDestroy() {
        running.set(false)
        closePipeline()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RECEIVER,
                getString(R.string.notification_channel_receiver),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_receiver_text)
                setSound(null, null)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                getString(R.string.notification_channel_messages),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 100, 180)
            },
        )
    }

    private fun receiverNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ReceiverService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_RECEIVER)
            .setSmallIcon(R.drawable.ic_fieldlink)
            .setContentTitle(getString(R.string.notification_receiver_title))
            .setContentText(getString(R.string.notification_receiver_text))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_stop), stopIntent).build())
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
