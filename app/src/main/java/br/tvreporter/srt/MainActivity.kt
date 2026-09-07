package br.tvreporter.srt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaFormat
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.tvreporter.srt.databinding.ActivityMainBinding
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.extensions.defaultCameraId
import io.github.thibaultbee.streampack.core.interfaces.releaseBlocking
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.ext.srt.configuration.mediadescriptor.SrtMediaDescriptor
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var streamer: SingleStreamer

    private var isStreaming = false
    private var isMuted = false
    private var isPrepared = false
    private var suppressDeviceCallbacks = false

    private data class CameraChoice(val id: String, val label: String)
    private data class MicrophoneChoice(val id: Int?, val label: String)

    private var cameraChoices: List<CameraChoice> = emptyList()
    private var microphoneChoices: List<MicrophoneChoice> = emptyList()

    private val bitrateOptions = listOf(2, 3, 4, 6, 8)
    private val latencyOptions = listOf(120, 200, 300, 500, 1000)
    private val fpsOptions = listOf(30, 60)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cameraGranted = result[Manifest.permission.CAMERA] == true || hasCameraPermission()
            val micGranted = result[Manifest.permission.RECORD_AUDIO] == true || hasMicPermission()
            if (cameraGranted && micGranted) {
                prepareStreamer()
            } else {
                showToast("Câmera e microfone são necessários para transmitir.")
                setStatus("SEM PERMISSÃO")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        streamer = SingleStreamer(applicationContext)

        setupControls()
        restoreSettings()
        refreshDeviceLists()
        requestPermissionsIfNeeded()
    }

    private fun setupControls() {
        binding.bitrateSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            bitrateOptions.map { "$it Mbps" }
        )
        binding.latencySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            latencyOptions.map { "$it ms" }
        )
        binding.fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            fpsOptions.map { "$it fps" }
        )

        binding.bitrateSpinner.setSelection(bitrateOptions.indexOf(4))
        binding.latencySpinner.setSelection(latencyOptions.indexOf(200))
        binding.fpsSpinner.setSelection(fpsOptions.indexOf(30))

        binding.cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressDeviceCallbacks || !isPrepared || isStreaming) return
                lifecycleScope.launch {
                    runCatching { applySelectedCamera() }
                        .onSuccess { saveSettings() }
                        .onFailure { showToast("Erro ao selecionar câmera: ${it.message}") }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.microphoneSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressDeviceCallbacks || !isPrepared || isStreaming) return
                lifecycleScope.launch {
                    runCatching { applySelectedMicrophone() }
                        .onSuccess { saveSettings() }
                        .onFailure { showToast("Erro ao selecionar microfone: ${it.message}") }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.refreshDevicesButton.setOnClickListener {
            if (!isStreaming) {
                refreshDeviceLists()
                if (isPrepared) {
                    lifecycleScope.launch {
                        runCatching { applySelectedDevices() }
                            .onFailure { showToast("Erro ao atualizar dispositivos: ${it.message}") }
                    }
                }
            }
        }

        binding.liveButton.setOnClickListener {
            if (isStreaming) stopLive() else startLive()
        }

        binding.muteButton.setOnClickListener {
            isMuted = !isMuted
            streamer.audioInput.isMuted = isMuted
            binding.muteButton.text = if (isMuted) "Ativar áudio" else "Mute"
        }
    }

    private fun refreshDeviceLists() {
        suppressDeviceCallbacks = true
        try {
            refreshCameraList()
            refreshMicrophoneList()
        } finally {
            suppressDeviceCallbacks = false
        }
    }

    private fun refreshCameraList() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraChoices = cameraManager.cameraIdList.map { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> "Frontal"
                CameraCharacteristics.LENS_FACING_BACK -> "Traseira"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "Externa"
                else -> "Câmera"
            }
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.joinToString("/") { String.format("%.1f", it) }
                ?.takeIf { it.isNotBlank() }
            val label = buildString {
                append(facing)
                if (focal != null) append(" • ${focal} mm")
                append(" • ID $id")
            }
            CameraChoice(id, label)
        }

        binding.cameraSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            cameraChoices.map { it.label }
        )

        val savedId = getSharedPreferences("stream", MODE_PRIVATE).getString("cameraId", null)
        val defaultId = runCatching { defaultCameraId }.getOrNull()
        val selectedIndex = cameraChoices.indexOfFirst { it.id == savedId }
            .takeIf { it >= 0 }
            ?: cameraChoices.indexOfFirst { it.id == defaultId }.takeIf { it >= 0 }
            ?: 0
        if (cameraChoices.isNotEmpty()) binding.cameraSpinner.setSelection(selectedIndex)
    }

    private fun refreshMicrophoneList() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        microphoneChoices = listOf(MicrophoneChoice(null, "Automático do Android")) +
            devices.map { device ->
                MicrophoneChoice(device.id, microphoneLabel(device))
            }

        binding.microphoneSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            microphoneChoices.map { it.label }
        )

        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        val savedId = if (prefs.contains("microphoneId")) prefs.getInt("microphoneId", -1) else -1
        val selectedIndex = microphoneChoices.indexOfFirst { it.id == savedId }
            .takeIf { it >= 0 }
            ?: 0
        binding.microphoneSpinner.setSelection(selectedIndex)
    }

    private fun microphoneLabel(device: AudioDeviceInfo): String {
        val type = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone interno"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset P2"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "Áudio USB"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "Headset USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telefonia"
            else -> "Entrada de áudio"
        }
        val product = device.productName?.toString()?.takeIf { it.isNotBlank() }
        return if (product != null && !product.equals(type, ignoreCase = true)) {
            "$type • $product"
        } else {
            type
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (hasCameraPermission() && hasMicPermission()) {
            prepareStreamer()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    private fun prepareStreamer() {
        lifecycleScope.launch {
            try {
                streamer.setAudioConfig(
                    AudioConfig(
                        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
                        startBitrate = 128_000,
                        sampleRate = 48_000,
                        channelConfig = AudioFormat.CHANNEL_IN_MONO
                    )
                )
                applySelectedDevices()
                applyVideoConfig()
                binding.preview.setVideoSourceProvider(streamer)
                isPrepared = true
                setStatus("PRONTO")
            } catch (t: Throwable) {
                setStatus("ERRO")
                showToast("Erro ao preparar câmera/áudio: ${t.message}")
            }
        }
    }

    private suspend fun applySelectedDevices() {
        applySelectedMicrophone()
        applySelectedCamera()
    }

    private suspend fun applySelectedCamera() {
        val choice = cameraChoices.getOrNull(binding.cameraSpinner.selectedItemPosition)
            ?: throw IllegalStateException("Nenhuma câmera disponível")
        streamer.setCameraId(choice.id)
    }

    private suspend fun applySelectedMicrophone() {
        val choice = microphoneChoices.getOrNull(binding.microphoneSpinner.selectedItemPosition)
            ?: MicrophoneChoice(null, "Automático do Android")
        streamer.setAudioSource(PreferredMicrophoneSourceFactory(choice.id))
        streamer.audioInput.isMuted = isMuted
    }

    private suspend fun applyVideoConfig() {
        val bitrateMbps = bitrateOptions[binding.bitrateSpinner.selectedItemPosition]
        val fps = fpsOptions[binding.fpsSpinner.selectedItemPosition]
        streamer.setVideoConfig(
            VideoConfig(
                mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
                startBitrate = bitrateMbps * 1_000_000,
                resolution = Size(1920, 1080),
                fps = fps
            )
        )
    }

    private fun startLive() {
        if (!hasCameraPermission() || !hasMicPermission()) {
            requestPermissionsIfNeeded()
            return
        }

        val host = binding.hostEdit.text.toString().trim()
        val port = binding.portEdit.text.toString().toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            showToast("Host ou porta inválidos.")
            return
        }

        val latency = latencyOptions[binding.latencySpinner.selectedItemPosition]
        val streamId = binding.streamIdEdit.text.toString().trim().ifBlank { null }
        val passphrase = binding.passphraseEdit.text.toString().ifBlank { null }

        saveSettings()
        setStatus("CONECTANDO")
        binding.liveButton.isEnabled = false

        lifecycleScope.launch {
            try {
                applySelectedDevices()
                applyVideoConfig()
                val descriptor = SrtMediaDescriptor(
                    host = host,
                    port = port,
                    streamId = streamId,
                    passPhrase = passphrase,
                    latency = latency
                )
                streamer.startStream(descriptor)
                isStreaming = true
                setStatus("NO AR")
                binding.liveButton.text = "SAIR DO AR"
                lockStreamingSettings(true)
            } catch (t: Throwable) {
                isStreaming = false
                setStatus("ERRO")
                showToast("Falha SRT/dispositivo: ${t.message}")
            } finally {
                binding.liveButton.isEnabled = true
            }
        }
    }

    private fun stopLive() {
        binding.liveButton.isEnabled = false
        lifecycleScope.launch {
            try {
                streamer.stopStream()
            } catch (_: Throwable) {
            } finally {
                isStreaming = false
                setStatus("PRONTO")
                binding.liveButton.text = "ENTRAR AO VIVO"
                binding.liveButton.isEnabled = true
                lockStreamingSettings(false)
            }
        }
    }

    private fun lockStreamingSettings(locked: Boolean) {
        binding.hostEdit.isEnabled = !locked
        binding.portEdit.isEnabled = !locked
        binding.streamIdEdit.isEnabled = !locked
        binding.passphraseEdit.isEnabled = !locked
        binding.cameraSpinner.isEnabled = !locked
        binding.microphoneSpinner.isEnabled = !locked
        binding.refreshDevicesButton.isEnabled = !locked
        binding.bitrateSpinner.isEnabled = !locked
        binding.latencySpinner.isEnabled = !locked
        binding.fpsSpinner.isEnabled = !locked
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun saveSettings() {
        val cameraId = cameraChoices.getOrNull(binding.cameraSpinner.selectedItemPosition)?.id
        val microphoneId = microphoneChoices.getOrNull(binding.microphoneSpinner.selectedItemPosition)?.id

        getSharedPreferences("stream", MODE_PRIVATE).edit()
            .putString("host", binding.hostEdit.text.toString())
            .putString("port", binding.portEdit.text.toString())
            .putString("streamId", binding.streamIdEdit.text.toString())
            .putString("passphrase", binding.passphraseEdit.text.toString())
            .putString("cameraId", cameraId)
            .apply {
                if (microphoneId == null) remove("microphoneId") else putInt("microphoneId", microphoneId)
            }
            .putInt("bitrate", binding.bitrateSpinner.selectedItemPosition)
            .putInt("latency", binding.latencySpinner.selectedItemPosition)
            .putInt("fps", binding.fpsSpinner.selectedItemPosition)
            .apply()
    }

    private fun restoreSettings() {
        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        binding.hostEdit.setText(prefs.getString("host", "192.168.20.53"))
        binding.portEdit.setText(prefs.getString("port", "6767"))
        binding.streamIdEdit.setText(prefs.getString("streamId", ""))
        binding.passphraseEdit.setText(prefs.getString("passphrase", ""))
        binding.bitrateSpinner.setSelection(prefs.getInt("bitrate", bitrateOptions.indexOf(4)))
        binding.latencySpinner.setSelection(prefs.getInt("latency", latencyOptions.indexOf(200)))
        binding.fpsSpinner.setSelection(prefs.getInt("fps", fpsOptions.indexOf(30)))
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        try {
            streamer.releaseBlocking()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
