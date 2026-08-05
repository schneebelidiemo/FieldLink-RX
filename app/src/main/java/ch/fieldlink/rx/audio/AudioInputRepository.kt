package ch.fieldlink.rx.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import ch.fieldlink.rx.model.AudioInput

object AudioInputRepository {
    fun list(context: Context): List<AudioInput> {
        val manager = context.getSystemService(AudioManager::class.java)
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .map { device ->
                AudioInput(
                    id = device.id,
                    productName = device.productName?.toString().orEmpty().ifBlank { typeName(device.type) },
                    typeName = typeName(device.type),
                    isBuiltIn = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
                )
            }
            .sortedWith(compareByDescending<AudioInput> { it.isBuiltIn }.thenBy { it.productName })
    }

    fun resolve(context: Context, deviceId: Int?): AudioDeviceInfo? {
        if (deviceId == null) return null
        val manager = context.getSystemService(AudioManager::class.java)
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId && it.isSource }
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line input"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line input"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony input"
        else -> "Audio input $type"
    }
}

