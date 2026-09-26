package com.turbodabber.voicetuner.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

fun AudioManager.hasWiredOutput(): Boolean = getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
}
