package com.turbodabber.voicetuner.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import com.turbodabber.voicetuner.audio.dsp.TuningScale

enum class SessionPhase { IDLE, STARTING, RUNNING, STOPPING }
data class SessionState(val phase: SessionPhase = SessionPhase.IDLE, val message: String = "Gotowy do odsłuchu")
data class EffectSettings(val autotune: Float = 0f, val reverb: Float = 0.25f,
                          val hardTune: Boolean = true, val rootNote: Int = 9,
                          val scale: TuningScale = TuningScale.MINOR)

/** In-process bridge: service owns session lifecycle; UI owns effect values. */
object AudioSession {
    private val mutableRecording = MutableStateFlow<File?>(null)
    val recording = mutableRecording.asStateFlow()
    fun publishRecording(file: File?) { mutableRecording.value = file }
    private val mutableState = MutableStateFlow(SessionState())
    val state = mutableState.asStateFlow()
    @Volatile var effects = EffectSettings()
    fun update(phase: SessionPhase, message: String) { mutableState.value = SessionState(phase, message) }
}
