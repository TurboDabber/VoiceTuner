package com.turbodabber.voicetuner

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turbodabber.voicetuner.audio.*
import com.turbodabber.voicetuner.service.AudioProcessingService
import kotlin.math.roundToInt
import java.io.File

class MainActivity : ComponentActivity() {
    private var microphoneGranted by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var permissionMessage by mutableStateOf<String?>(null)
    private var previewPlayer: MediaPlayer? = null
    private var previewPlaying by mutableStateOf(false)
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshPermissions()
        AudioSession.publishRecording(File(filesDir, "recordings").listFiles()
            ?.filter { it.extension == "m4a" && it.length() > 0 }?.maxByOrNull { it.lastModified() })
        permissionMessage = if (granted) null else "Brak dostępu do mikrofonu. Możesz nadać go w ustawieniach aplikacji."
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("effects", MODE_PRIVATE)
        AudioSession.effects = EffectSettings(preferences.getFloat("autotune", 0f), preferences.getFloat("reverb", 0.25f))
        refreshPermissions()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFB5F56A), onPrimary = Color(0xFF173500),
                background = Color(0xFF101510), surface = Color(0xFF1B231B)
            )) {
                val session by AudioSession.state.collectAsStateWithLifecycle()
                val recording by AudioSession.recording.collectAsStateWithLifecycle()
                var effects by remember { mutableStateOf(AudioSession.effects) }
                var showSpeakerConsent by remember { mutableStateOf(false) }
                if (showSpeakerConsent) {
                    AlertDialog(
                        onDismissRequest = { showSpeakerConsent = false },
                        title = { Text("Odsłuch bez słuchawek?") },
                        text = { Text("Głośnik może wracać do mikrofonu i wywołać głośny pisk lub narastające echo, szczególnie przy mocnym reverbie. Zmniejsz głośność telefonu. Zgoda dotyczy tylko tego uruchomienia.") },
                        confirmButton = { TextButton(onClick = {
                            showSpeakerConsent = false
                            startAudio(allowSpeaker = true)
                        }) { Text("Rozumiem, uruchom") } },
                        dismissButton = { TextButton(onClick = { showSpeakerConsent = false }) { Text("Anuluj") } }
                    )
                }
                fun changeEffects(value: EffectSettings) { effects = value; AudioSession.effects = value }
                fun saveEffects() { preferences.edit().putFloat("autotune", effects.autotune).putFloat("reverb", effects.reverb).apply() }
                val idle = session.phase == SessionPhase.IDLE
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("VOICETUNER / MVP 0.1", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge)
                        Text("Twój głos.\nTwoje brzmienie.", style = MaterialTheme.typography.headlineLarge)
                        Text("Nagraj głos i udostępnij z efektami", style = MaterialTheme.typography.titleMedium)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (session.phase == SessionPhase.RUNNING) "● MIKROFON AKTYWNY" else "● ${session.phase.label()}",
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                Text(session.message)
                            }
                        }
                        EffectSlider("Autotune", effects.autotune,
                            "W przygotowaniu — ten suwak jeszcze nie zmienia wysokości głosu.",
                            { changeEffects(effects.copy(autotune = it)) }, { saveEffects() })
                        EffectSlider("Reverb", effects.reverb, "Od lekkiej przestrzeni do zalewającego pogłosu. 100%: sam pogłos, długi ogon.",
                            { changeEffects(effects.copy(reverb = it)) }, { saveEffects() })
                        Button(onClick = {
                            if (idle) startAudio(record = true)
                            else startService(Intent(this@MainActivity, AudioProcessingService::class.java).setAction(AudioProcessingService.ACTION_STOP))
                        }, enabled = microphoneGranted && session.phase != SessionPhase.STOPPING,
                            modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(if (idle) "Nagraj wiadomość z efektami" else "STOP") }
                        Text("Nagrywanie bez odsłuchu i bez słuchawek. Naciśnij STOP, aby zapisać plik M4A. Maksymalnie 10 minut.",
                            style = MaterialTheme.typography.bodySmall)
                        recording?.let { file ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Ostatnie nagranie", style = MaterialTheme.typography.titleMedium)
                                    Text("${file.length() / 1024} KB · M4A z efektami")
                                    OutlinedButton(onClick = { if (previewPlaying) stopPreview() else preview(file) }, enabled = idle) {
                                        Text(if (previewPlaying) "Zatrzymaj odtwarzanie" else "Odsłuchaj nagranie")
                                    }
                                    Button(onClick = { shareRecording(file) }, enabled = idle) { Text("Udostępnij · Messenger") }
                                    Text("Wybierz Messengera i odbiorcę w oknie udostępniania. To plik audio, nie nagrywanie bezpośrednio w Messengerze.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Text("Zalecane słuchawki przewodowe lub USB. Bez nich możesz uruchomić odsłuch po zaakceptowaniu ryzyka sprzężenia.",
                            style = MaterialTheme.typography.bodyMedium)
                        if (!microphoneGranted) {
                            OutlinedButton(onClick = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                                modifier = Modifier.fillMaxWidth(), enabled = idle) { Text("1. Zezwól na mikrofon") }
                        }
                        if (!overlayGranted) {
                            OutlinedButton(onClick = {
                                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                                    .onFailure { permissionMessage = "Otwórz ustawienia systemowe i zezwól VoiceTuner na wyświetlanie nad innymi aplikacjami." }
                            }, modifier = Modifier.fillMaxWidth(), enabled = idle) { Text("2. Włącz pływający STOP") }
                        }
                        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                            TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                modifier = Modifier.fillMaxWidth(), enabled = idle) {
                                Text("Włącz powiadomienie ze STOP (opcjonalnie)")
                            }
                        }
                        permissionMessage?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) {
                                Text("Ustawienia aplikacji")
                            }
                        }
                        Button(onClick = {
                            if (idle) {
                                if (getSystemService(AudioManager::class.java).hasWiredOutput()) startAudio()
                                else showSpeakerConsent = true
                            } else {
                                startService(Intent(this@MainActivity, AudioProcessingService::class.java)
                                    .setAction(AudioProcessingService.ACTION_STOP))
                            }
                        }, enabled = if (idle) microphoneGranted && overlayGranted else session.phase != SessionPhase.STOPPING,
                            modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text(if (idle) "Uruchom odsłuch" else "STOP")
                        }
                        HorizontalDivider()
                        Text("Działa wewnątrz VoiceTuner", style = MaterialTheme.typography.titleSmall)
                        Text("Nagrania są zapisywane lokalnie. Wysyłasz je samodzielnie przez udostępnianie. Odsłuch nie zapisuje plików. Aplikacja nie zmienia mikrofonu podczas rozmów w Messengerze.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    private fun startAudio(allowSpeaker: Boolean = false, record: Boolean = false) {
        refreshPermissions()
        if (!microphoneGranted || (!record && !overlayGranted)) return
        stopPreview()
        // Called only by a tap while this activity is visible (microphone while-in-use rules).
        AudioSession.update(SessionPhase.STARTING, "Uruchamianie mikrofonu…")
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, AudioProcessingService::class.java)
                .setAction(AudioProcessingService.ACTION_START)
                .putExtra(AudioProcessingService.EXTRA_RECORD, record)
                .putExtra(AudioProcessingService.EXTRA_ALLOW_SPEAKER, allowSpeaker))
        }.onFailure { AudioSession.update(SessionPhase.IDLE, it.message ?: "Nie udało się uruchomić usługi.") }
    }
    private fun stopPreview() {
        previewPlayer?.release()
        previewPlayer = null
        previewPlaying = false
    }
    private fun preview(file: File) {
        stopPreview()
        runCatching {
            previewPlayer = MediaPlayer()
            previewPlayer!!.let { player ->
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { stopPreview() }
                player.setOnErrorListener { _, _, _ ->
                    stopPreview(); permissionMessage = "Nie udało się odtworzyć nagrania."; true
                }
                previewPlaying = true
                player.prepareAsync()
            }
        }.onFailure { stopPreview(); permissionMessage = "Nie udało się otworzyć nagrania." }
    }
    private fun shareRecording(file: File) {
        stopPreview()
        runCatching {
            check(file.isFile && file.length() > 0)
            val uri = FileProvider.getUriForFile(this, "$packageName.recordings", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Nagranie VoiceTuner", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Udostępnij nagranie — wybierz Messenger"))
        }.onFailure { permissionMessage = "Nie udało się udostępnić pliku: ${it.message}" }
    }
    override fun onStop() { stopPreview(); super.onStop() }
    override fun onResume() { super.onResume(); refreshPermissions() }
    private fun refreshPermissions() {
        microphoneGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        overlayGranted = Settings.canDrawOverlays(this)
        notificationsGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun EffectSlider(title: String, amount: Float, description: String,
                         onValue: (Float) -> Unit, onFinished: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text("${(amount * 100).roundToInt()}%", color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = amount, onValueChange = onValue, onValueChangeFinished = onFinished)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun SessionPhase.label() = when (this) {
    SessionPhase.IDLE -> "GOTOWY"
    SessionPhase.STARTING -> "URUCHAMIANIE"
    SessionPhase.RUNNING -> "AKTYWNY"
    SessionPhase.STOPPING -> "ZATRZYMYWANIE"
}
