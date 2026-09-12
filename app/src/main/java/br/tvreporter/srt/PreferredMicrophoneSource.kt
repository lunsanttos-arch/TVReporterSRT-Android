package br.tvreporter.srt

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.thibaultbee.streampack.core.elements.sources.audio.AudioSourceConfig
import io.github.thibaultbee.streampack.core.elements.sources.audio.IAudioSourceInternal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer

class PreferredMicrophoneSourceFactory(
    private val deviceId: Int?
) : IAudioSourceInternal.Factory {
    override suspend fun create(context: Context): IAudioSourceInternal {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = deviceId?.let { id ->
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
        }
        return PreferredMicrophoneSource(deviceId, device)
    }

    override fun isSourceEquals(source: IAudioSourceInternal?): Boolean {
        return source is PreferredMicrophoneSource && source.deviceId == deviceId
    }
}

class PreferredMicrophoneSource(
    val deviceId: Int?,
    private val preferredDevice: AudioDeviceInfo?
) : IAudioSourceInternal {
    private var audioRecord: AudioRecord? = null
    private var configuredBufferSize: Int = 0
    private val streaming = MutableStateFlow(false)

    override val isStreamingFlow: StateFlow<Boolean> = streaming

    override val minBufferSize: Int
        get() = configuredBufferSize.coerceAtLeast(1)

    override suspend fun configure(config: AudioSourceConfig) {
        release()

        configuredBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            config.channelConfig,
            config.byteFormat
        )
        require(configuredBufferSize > 0) { "Configuração de áudio não suportada" }

        val format = AudioFormat.Builder()
            .setEncoding(config.byteFormat)
            .setSampleRate(config.sampleRate)
            .setChannelMask(config.channelConfig)
            .build()

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            .setAudioFormat(format)
            .setBufferSizeInBytes(configuredBufferSize)
            .build()

        if (preferredDevice != null && !record.setPreferredDevice(preferredDevice)) {
            record.release()
            throw IllegalStateException("O Android não conseguiu selecionar ${preferredDevice.productName}")
        }

        require(record.state == AudioRecord.STATE_INITIALIZED) {
            record.release()
            "Não foi possível inicializar a entrada de áudio"
        }
        audioRecord = record
    }

    override suspend fun startStream() {
        val record = requireNotNull(audioRecord) { "Entrada de áudio não preparada" }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.startRecording()
        }
        streaming.value = true
    }

    override suspend fun stopStream() {
        audioRecord?.let { record ->
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        }
        streaming.value = false
    }

    override fun fillAudioFrame(buffer: ByteBuffer): Long {
        val record = requireNotNull(audioRecord) { "Entrada de áudio não preparada" }
        val length = record.read(buffer, buffer.remaining())
        if (length <= 0) throw IllegalStateException("Falha ao capturar áudio: $length")
        return System.nanoTime() / 1_000L
    }

    override fun release() {
        streaming.value = false
        audioRecord?.runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        audioRecord = null
    }
}
