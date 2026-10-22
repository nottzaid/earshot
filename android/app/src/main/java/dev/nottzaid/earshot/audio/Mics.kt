package dev.nottzaid.earshot.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** An input the user can pick. [key] is stable across reboots; [id] is not. */
data class Mic(val key: String, val id: Int, val label: String, val external: Boolean)

object Mics {
    fun list(context: Context): List<Mic> {
        val am = context.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull(::toMic)
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.external }, { order(it.key) }))
    }

    fun watch(context: Context): Flow<List<Mic>> = callbackFlow {
        val am = context.getSystemService(AudioManager::class.java)
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { trySend(list(context)) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { trySend(list(context)) }
        }
        trySend(list(context))
        am.registerAudioDeviceCallback(cb, null)
        awaitClose { am.unregisterAudioDeviceCallback(cb) }
    }.distinctUntilChanged()

    /** Picks the saved mic if it is still there, else the phone's main mic. */
    fun resolve(mics: List<Mic>, key: String?): Mic? = mics.firstOrNull { it.key == key } ?: mics.firstOrNull()

    private fun order(key: String) = when (key) {
        "builtin:bottom" -> 0
        "builtin:back" -> 1
        else -> 2
    }

    private fun toMic(d: AudioDeviceInfo): Mic? {
        val address = d.address.orEmpty()
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> when {
                address.contains("back", true) -> Mic("builtin:back", d.id, "Back", false)
                address.contains("top", true) || address.contains("front", true) ->
                    Mic("builtin:top", d.id, "Top", false)
                else -> Mic("builtin:bottom", d.id, "Bottom", false)
            }
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> Mic("wired", d.id, "Headset", true)
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                Mic("usb:${d.productName}", d.id, d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB", true)
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                null // Bluetooth mics are 16 kHz narrowband over SCO: not worth offering here
            else -> null
        }
    }
}
