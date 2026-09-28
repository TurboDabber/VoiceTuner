package com.turbodabber.voicetuner

import android.Manifest
import android.content.Intent
import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.turbodabber.voicetuner.audio.*
import com.turbodabber.voicetuner.audio.dsp.TuningScale
import com.turbodabber.voicetuner.service.AudioProcessingService
import kotlin.math.roundToInt
import java.io.File

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }
    private var microphoneGranted by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private var permissionMessage by mutableStateOf<String?>(null)
    private var previewPlayer: MediaPlayer? = null
    private var previewPlaying by mutableStateOf(false)
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshPermissions()
        permissionMessage = if (granted) null else getString(R.string.mic_denied)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AudioSession.state.value.phase == SessionPhase.IDLE) AudioSession.update(SessionPhase.IDLE, getString(R.string.ready))
        AudioSession.publishRecording(File(filesDir, "recordings").listFiles()
            ?.filter { it.extension == "m4a" && it.length() > 0 }?.maxByOrNull { it.lastModified() })
        val preferences = getSharedPreferences("effects", MODE_PRIVATE)
        AudioSession.effects = EffectSettings(
            autotune = preferences.getFloat("autotune", 0f), reverb = preferences.getFloat("reverb", 0.25f),
            hardTune = preferences.getBoolean("hardTune", true), rootNote = preferences.getInt("rootNote", 9).coerceIn(0, 11),
            scale = runCatching { TuningScale.valueOf(preferences.getString("scale", "MINOR")!!) }.getOrDefault(TuningScale.MINOR))
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
                        title = { Text(getString(R.string.speaker_title)) },
                        text = { Text(getString(R.string.speaker_warning)) },
                        confirmButton = { TextButton(onClick = {
                            showSpeakerConsent = false
                            startAudio(allowSpeaker = true)
                        }) { Text(getString(R.string.accept_start)) } },
                        dismissButton = { TextButton(onClick = { showSpeakerConsent = false }) { Text(getString(R.string.cancel)) } }
                    )
                }
                fun changeEffects(value: EffectSettings) { effects = value; AudioSession.effects = value }
                fun saveEffects() {
                    preferences.edit().putFloat("autotune", effects.autotune).putFloat("reverb", effects.reverb)
                        .putBoolean("hardTune", effects.hardTune).putInt("rootNote", effects.rootNote)
                        .putString("scale", effects.scale.name).apply()
                }
                val idle = session.phase == SessionPhase.IDLE
                Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("VOICETUNER / MVP 0.1", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge)
                        LanguagePicker(AppLanguage.current(this@MainActivity), idle) { tag ->
                            saveEffects()
                            stopPreview()
                            AppLanguage.select(this@MainActivity, tag)
                        }
                        Text(getString(R.string.headline), style = MaterialTheme.typography.headlineLarge)
                        Text(getString(R.string.subtitle), style = MaterialTheme.typography.titleMedium)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (session.phase == SessionPhase.RUNNING) getString(R.string.mic_active) else "● ${session.phase.label()}",
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                Text(session.message.ifEmpty { getString(R.string.ready) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = effects.hardTune, onClick = {
                                changeEffects(effects.copy(hardTune = true)); saveEffects()
                            }, label = { Text("Hard Tune") })
                            FilterChip(selected = !effects.hardTune, onClick = {
                                changeEffects(effects.copy(hardTune = false)); saveEffects()
                            }, label = { Text(getString(R.string.gentle)) })
                        }
                        TuningSelectors(effects) { changeEffects(it); saveEffects() }
                        OutlinedButton(onClick = {
                            changeEffects(effects.copy(autotune = 1f, reverb = 0.15f, hardTune = true)); saveEffects()
                        }) { Text(getString(R.string.rap_preset)) }
                        EffectSlider("Autotune / Hard Tune", effects.autotune,
                            if (effects.hardTune) getString(R.string.hard_description)
                            else getString(R.string.gentle_description),
                            { changeEffects(effects.copy(autotune = it)) }, { saveEffects() })
                        EffectSlider("Reverb", effects.reverb, getString(R.string.reverb_description),
                            { changeEffects(effects.copy(reverb = it)) }, { saveEffects() })
                        Button(onClick = {
                            if (idle) startAudio(record = true)
                            else startService(Intent(this@MainActivity, AudioProcessingService::class.java).setAction(AudioProcessingService.ACTION_STOP))
                        }, enabled = microphoneGranted && session.phase != SessionPhase.STOPPING,
                            modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(if (idle) getString(R.string.record) else getString(R.string.stop)) }
                        Text(getString(R.string.record_hint),
                            style = MaterialTheme.typography.bodySmall)
                        recording?.let { file ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(getString(R.string.last_recording), style = MaterialTheme.typography.titleMedium)
                                    Text(getString(R.string.recording_size, file.length() / 1024))
                                    OutlinedButton(onClick = { if (previewPlaying) stopPreview() else preview(file) }, enabled = idle) {
                                        Text(if (previewPlaying) getString(R.string.stop_playback) else getString(R.string.play_recording))
                                    }
                                    Button(onClick = { shareRecording(file) }, enabled = idle) { Text(getString(R.string.share)) }
                                    Text(getString(R.string.share_hint),
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Text(getString(R.string.headphones_hint),
                            style = MaterialTheme.typography.bodyMedium)
                        if (!microphoneGranted) {
                            OutlinedButton(onClick = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                                modifier = Modifier.fillMaxWidth(), enabled = idle) { Text(getString(R.string.allow_mic)) }
                        }
                        if (!overlayGranted) {
                            OutlinedButton(onClick = {
                                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                                    .onFailure { permissionMessage = getString(R.string.overlay_settings_hint) }
                            }, modifier = Modifier.fillMaxWidth(), enabled = idle) { Text(getString(R.string.allow_overlay)) }
                        }
                        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted) {
                            TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                modifier = Modifier.fillMaxWidth(), enabled = idle) {
                                Text(getString(R.string.allow_notification))
                            }
                        }
                        permissionMessage?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) {
                                Text(getString(R.string.app_settings))
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
                            Text(if (idle) getString(R.string.start_monitor) else getString(R.string.stop))
                        }
                        HorizontalDivider()
                        Text(getString(R.string.local_title), style = MaterialTheme.typography.titleSmall)
                        Text(getString(R.string.local_description),
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
        AudioSession.update(SessionPhase.STARTING, getString(R.string.starting_mic))
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, AudioProcessingService::class.java)
                .setAction(AudioProcessingService.ACTION_START)
                .putExtra(AudioProcessingService.EXTRA_RECORD, record)
                .putExtra(AudioProcessingService.EXTRA_ALLOW_SPEAKER, allowSpeaker))
        }.onFailure { AudioSession.update(SessionPhase.IDLE, getString(R.string.service_failed)) }
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
                    stopPreview(); permissionMessage = getString(R.string.play_failed); true
                }
                previewPlaying = true
                player.prepareAsync()
            }
        }.onFailure { stopPreview(); permissionMessage = getString(R.string.open_failed) }
    }
    private fun shareRecording(file: File) {
        stopPreview()
        runCatching {
            check(file.isFile && file.length() > 0)
            val uri = FileProvider.getUriForFile(this, "$packageName.recordings", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(getString(R.string.recording_label), uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
        }.onFailure { permissionMessage = getString(R.string.share_failed) }
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

@Composable
private fun SessionPhase.label() = when (this) {
    SessionPhase.IDLE -> stringResource(R.string.idle)
    SessionPhase.STARTING -> stringResource(R.string.starting)
    SessionPhase.RUNNING -> stringResource(R.string.active)
    SessionPhase.STOPPING -> stringResource(R.string.stopping)
}

@Composable
private fun TuningSelectors(settings: EffectSettings, onChange: (EffectSettings) -> Unit) {
    val notes = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B / H")
    var keyMenu by remember { mutableStateOf(false) }
    var scaleMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.tuning_title), style = MaterialTheme.typography.titleSmall)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                OutlinedButton(onClick = { keyMenu = true }, enabled = settings.scale != TuningScale.CHROMATIC) {
                    Text(stringResource(R.string.key_label, notes[settings.rootNote]))
                }
                DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                    notes.forEachIndexed { index, note ->
                        DropdownMenuItem(text = { Text(note) }, onClick = { keyMenu = false; onChange(settings.copy(rootNote = index)) })
                    }
                }
            }
            Box {
                OutlinedButton(onClick = { scaleMenu = true }) { Text(scaleLabel(settings.scale)) }
                DropdownMenu(expanded = scaleMenu, onDismissRequest = { scaleMenu = false }) {
                    TuningScale.entries.forEach { scale ->
                        DropdownMenuItem(text = { Text(scaleLabel(scale)) }, onClick = { scaleMenu = false; onChange(settings.copy(scale = scale)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun scaleLabel(scale: TuningScale): String = stringResource(when (scale) {
    TuningScale.CHROMATIC -> R.string.scale_chromatic
    TuningScale.MINOR -> R.string.scale_minor
    TuningScale.MAJOR -> R.string.scale_major
    TuningScale.MINOR_PENTATONIC -> R.string.scale_pentatonic
})

@Composable
private fun LanguagePicker(current: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("${stringResource(R.string.language)} · ${AppLanguage.options[current]}")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AppLanguage.options.forEach { (tag, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(tag) })
                }
            }
        }
        if (!enabled) Text(stringResource(R.string.language_hint), style = MaterialTheme.typography.bodySmall)
    }
}
