package br.tvreporter.srt

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioPreviewMonitor(private val context: Context) {
    private var job: Job? = null
    private var record: AudioRecord? = null

    fun start(deviceId: Int?) {
        stop()

        val sampleRate = 48_000
        val channelMask = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) return

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 2)
            .build()

        if (deviceId != null) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val device: AudioDeviceInfo? = audioManager
                .getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id == deviceId }
            if (device != null) audioRecord.setPreferredDevice(device)
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return
        }

        record = audioRecord
        audioRecord.startRecording()
        val buffer = ByteArray(minBuffer)
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) AudioLevelMonitor.updateFromPcm16(buffer, read)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        record?.let { audioRecord ->
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
            }
            runCatching { audioRecord.release() }
        }
        record = null
        AudioLevelMonitor.clear()
    }
}
