package ch.fieldlink.rx.audio

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import ch.fieldlink.rx.model.AudioInput
import ch.fieldlink.rx.model.AudioInputKind

object AudioInputRepository {
    private const val RTL_SDR_VENDOR_ID = 0x0BDA
    private const val RTL_SDR_PRODUCT_ID = 0x2838
    private const val RTL_SDR_ID_BASE = -1_000_000

    fun list(context: Context): List<AudioInput> {
        val manager = context.getSystemService(AudioManager::class.java)
        val microphoneInputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .map { device ->
                AudioInput(
                    id = device.id,
                    productName = device.productName?.toString().orEmpty().ifBlank { typeName(device.type) },
                    typeName = typeName(device.type),
                    isBuiltIn = device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC,
                )
            }
        val usbManager = context.getSystemService(UsbManager::class.java)
        val sdrInputs = usbManager.deviceList.values
            .filter(::isSupportedRtlSdr)
            .map(::toAudioInput)
        return (microphoneInputs + sdrInputs)
            .sortedWith(
                compareByDescending<AudioInput> { it.kind == AudioInputKind.RTL_SDR }
                    .thenByDescending { it.isBuiltIn }
                    .thenBy { it.productName },
            )
    }

    fun resolve(context: Context, deviceId: Int?): AudioDeviceInfo? {
        if (deviceId == null) return null
        val manager = context.getSystemService(AudioManager::class.java)
        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId && it.isSource }
    }

    fun resolveRtlSdr(context: Context, inputId: Int?): UsbDevice? {
        if (inputId == null) return null
        val usbDeviceId = inputIdToUsbDeviceId(inputId) ?: return null
        return context.getSystemService(UsbManager::class.java)
            .deviceList
            .values
            .firstOrNull { it.deviceId == usbDeviceId && isSupportedRtlSdr(it) }
    }

    fun inputIdFor(device: UsbDevice): Int? = device
        .takeIf(::isSupportedRtlSdr)
        ?.let { RTL_SDR_ID_BASE - it.deviceId }

    fun isSupportedRtlSdr(device: UsbDevice): Boolean =
        device.vendorId == RTL_SDR_VENDOR_ID && device.productId == RTL_SDR_PRODUCT_ID

    private fun toAudioInput(device: UsbDevice): AudioInput = AudioInput(
        id = RTL_SDR_ID_BASE - device.deviceId,
        productName = device.productName?.takeIf { it.isNotBlank() } ?: "RTL-SDR Blog V4",
        typeName = "RTL-SDR V4 · USB",
        isBuiltIn = false,
        kind = AudioInputKind.RTL_SDR,
        usbDeviceId = device.deviceId,
    )

    private fun inputIdToUsbDeviceId(inputId: Int): Int? =
        inputId.takeIf { it <= RTL_SDR_ID_BASE }?.let { RTL_SDR_ID_BASE - it }

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
