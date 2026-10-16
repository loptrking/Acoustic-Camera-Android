package com.example.acoustic_camera

import android.app.Activity
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Writes non-personal microphone hardware metadata to this app's private directory. */
class MicDiagnosticActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        writeMicrophoneInventory()
        finish()
    }

    private fun writeMicrophoneInventory() {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val report = JSONObject().apply {
            put("format_version", 1)
            put("app_package", packageName)
            put("sample_rate_property", manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE))
            put("frames_per_buffer_property", manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER))
            put(
                "unprocessed_source_supported",
                manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true",
            )
            put(
                "input_devices",
                JSONArray().apply {
                    manager.getDevices(AudioManager.GET_DEVICES_INPUTS).forEach { device ->
                        put(JSONObject().apply {
                            put("id", device.id)
                            put("type", device.type)
                            put("type_name", audioDeviceTypeName(device.type))
                            put("product_name", device.productName.toString())
                            put("address", device.address)
                            put("channel_counts", JSONArray(device.channelCounts.toList()))
                            put("channel_index_masks", JSONArray(device.channelIndexMasks.toList()))
                            put("channel_masks", JSONArray(device.channelMasks.toList()))
                            put("sample_rates", JSONArray(device.sampleRates.toList()))
                            put("encodings", JSONArray(device.encodings.toList()))
                        })
                    }
                },
            )
            val inventory = runCatching { manager.microphones }
            put("microphone_query_succeeded", inventory.isSuccess)
            inventory.exceptionOrNull()?.let { put("microphone_query_error", it.javaClass.simpleName) }
            put(
                "microphones",
                JSONArray().apply {
                    inventory.getOrDefault(emptyList()).forEach { microphone ->
                        put(JSONObject().apply {
                            put("id", microphone.id)
                            put("type", microphone.type)
                            put("type_name", audioDeviceTypeName(microphone.type))
                            put("address", microphone.address)
                            put("location", microphone.location)
                            put("group", microphone.group)
                            put("index_in_group", microphone.indexInTheGroup)
                            put("directionality", microphone.directionality)
                            put("position", JSONObject().apply {
                                put("x", microphone.position.x)
                                put("y", microphone.position.y)
                                put("z", microphone.position.z)
                            })
                            put("orientation", JSONObject().apply {
                                put("x", microphone.orientation.x)
                                put("y", microphone.orientation.y)
                                put("z", microphone.orientation.z)
                            })
                            put(
                                "frequency_response",
                                JSONArray().apply {
                                    microphone.frequencyResponse.forEach { pair ->
                                        put(JSONArray().put(pair.first).put(pair.second))
                                    }
                                },
                            )
                            put(
                                "channel_mapping",
                                JSONArray().apply {
                                    microphone.channelMapping.forEach { pair ->
                                        put(JSONArray().put(pair.first).put(pair.second))
                                    }
                                },
                            )
                        })
                    }
                },
            )
        }
        File(filesDir, "microphone_inventory.json").writeText(report.toString(2))
    }

    private fun audioDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        else -> "TYPE_$type"
    }
}
