package com.turbodabber.voicetuner.service

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.turbodabber.voicetuner.MainActivity
import com.turbodabber.voicetuner.R
import com.turbodabber.voicetuner.audio.*
import java.io.File
import java.util.UUID

class AudioProcessingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: AudioEngine? = null
    private var overlay: StopOverlay? = null
    private var destroyed = false
    private var stopping = false
    private var allowSpeaker = false
    private var recordingMode = false
    private var stopMessage = "Zatrzymano mikrofon"
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (!recordingMode && !allowSpeaker && !audioManager.hasWiredOutput()) stopSession("Odłączono słuchawki — uruchom ponownie i zaakceptuj odsłuch bez nich")
        }
    }
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!recordingMode && !allowSpeaker) stopSession("Zmieniono wyjście audio — odsłuch zatrzymany")
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        ContextCompat.registerReceiver(this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Odsłuch mikrofonu", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSession(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        if (engine != null || stopping) return START_NOT_STICKY
        recordingMode = intent.getBooleanExtra(EXTRA_RECORD, false)
        try {
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Nadaj dostęp do mikrofonu."
            }
            check(recordingMode || Settings.canDrawOverlays(this)) { "Nadaj zgodę na pływający przycisk STOP." }
            val type = when {
                Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    (if (recordingMode) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else -> 0
            }
            ServiceCompat.startForeground(this, 1, notification(), type)
            AudioSession.update(SessionPhase.STARTING, "Uruchamianie mikrofonu…")
            allowSpeaker = intent.getBooleanExtra(EXTRA_ALLOW_SPEAKER, false)
            check(recordingMode || allowSpeaker || audioManager.hasWiredOutput()) { "Podłącz słuchawki lub zaakceptuj odsłuch bez nich na ekranie aplikacji." }
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener({ change ->
                    if (change < 0) stopSession("Inna aplikacja przejęła audio — odsłuch zatrzymany")
                }, handler).build()
            check(audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Inna aplikacja korzysta z audio. Spróbuj ponownie po zakończeniu odtwarzania lub rozmowy."
            }
            if (Settings.canDrawOverlays(this)) overlay = StopOverlay(this) { stopSession() }.also { it.show() }
            engine = AudioEngine(
                recordingFile = if (recordingMode) File(filesDir, "recordings/VoiceTuner-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.m4a") else null,
                onStarted = { handler.post {
                    if (!destroyed && !stopping) AudioSession.update(SessionPhase.RUNNING,
                        if (recordingMode) "Nagrywanie z efektami · maks. 10 minut · STOP zapisuje plik" else "Mikrofon → efekty → lokalny odsłuch")
                } },
                onFinished = { error, file -> handler.post {
                    if (file != null) AudioSession.publishRecording(file)
                    if (!destroyed) {
                        engine = null
                        stopMessage = error ?: if (file != null) "Nagranie zapisane — możesz je odsłuchać i udostępnić" else stopMessage
                        finishService()
                    }
                } }
            ).also { it.start() }
        } catch (e: Exception) {
            stopMessage = e.message ?: "Nie udało się uruchomić odsłuchu."
            stopSession(stopMessage)
        }
        return START_NOT_STICKY
    }

    private fun stopSession(message: String = "Zatrzymano mikrofon") {
        if (stopping) return
        stopping = true
        stopMessage = message
        overlay?.remove()
        if (engine != null) {
            AudioSession.update(SessionPhase.STOPPING, if (recordingMode) "Zapisywanie nagrania i ogona pogłosu…" else "Zatrzymywanie mikrofonu…")
            engine?.stop()
        } else {
            AudioSession.update(SessionPhase.IDLE, message)
            finishService()
        }
    }

    private fun finishService() {
        stopping = true
        overlay?.remove()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic).setContentTitle(if (recordingMode) "VoiceTuner · nagrywanie z efektami" else "VoiceTuner · lokalny odsłuch")
            .setContentText("Mikrofon jest używany. Dotknij STOP, aby zakończyć.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "STOP", stop).build()
    }

    override fun onDestroy() {
        destroyed = true
        engine?.stop()
        overlay?.remove()
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        unregisterReceiver(noisyReceiver)
        AudioSession.update(SessionPhase.IDLE, stopMessage)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.turbodabber.voicetuner.START"
        const val ACTION_STOP = "com.turbodabber.voicetuner.STOP"
        const val EXTRA_ALLOW_SPEAKER = "allow_speaker_for_this_session"
        const val EXTRA_RECORD = "record_to_file"
        private const val CHANNEL = "microphone_monitor"
    }
}
