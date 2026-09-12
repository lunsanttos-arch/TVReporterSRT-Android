package br.tvreporter.srt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
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
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
    private lateinit var audioPreviewMonitor: AudioPreviewMonitor

    private var isStreaming = false
    private var isPrepared = false
    private var isMuted = false

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
                setStatus("SEM PERMISSÃO")
                showToast("Câmera e microfone são necessários para transmitir.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        streamer = SingleStreamer(applicationContext)
        audioPreviewMonitor = AudioPreviewMonitor(applicationContext)
        isMuted = getSharedPreferences("stream", MODE_PRIVATE).getBoolean("muted", false)

        refreshDeviceLists()
        setupControls()
        observeAudioLevel()
        requestPermissionsIfNeeded()
    }

    private fun setupControls() {
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        binding.liveButton.setOnClickListener {
            if (isStreaming) stopLive() else startLive()
        }
        setStatus("OFFLINE")
    }

    private fun observeAudioLevel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AudioLevelMonitor.level.collect { level ->
                    binding.audioLevelMeter.setLevel(level)
                }
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (hasCameraPermission() && hasMicPermission()) {
            prepareStreamer()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
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
                applySelectedCamera()
                applyVideoConfig()
                binding.preview.setVideoSourceProvider(streamer)
                isPrepared = true
                startAudioPreview()
                setStatus("OFFLINE")
            } catch (t: Throwable) {
                setStatus("ERRO")
                showToast("Erro ao preparar câmera/áudio: ${t.message}")
            }
        }
    }

    private fun showSettingsDialog() {
        if (isStreaming) {
            showToast("Saia do ar antes de alterar as configurações.")
            return
        }

        refreshDeviceLists()
        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)

        val hostEdit = view.findViewById<EditText>(R.id.hostEdit)
        val portEdit = view.findViewById<EditText>(R.id.portEdit)
        val streamIdEdit = view.findViewById<EditText>(R.id.streamIdEdit)
        val passphraseEdit = view.findViewById<EditText>(R.id.passphraseEdit)
        val cameraSpinner = view.findViewById<Spinner>(R.id.cameraSpinner)
        val microphoneSpinner = view.findViewById<Spinner>(R.id.microphoneSpinner)
        val bitrateSpinner = view.findViewById<Spinner>(R.id.bitrateSpinner)
        val latencySpinner = view.findViewById<Spinner>(R.id.latencySpinner)
        val fpsSpinner = view.findViewById<Spinner>(R.id.fpsSpinner)
        val muteCheckBox = view.findViewById<CheckBox>(R.id.muteCheckBox)
        val refreshButton = view.findViewById<View>(R.id.refreshDevicesButton)

        hostEdit.setText(prefs.getString("host", "192.168.20.53"))
        portEdit.setText(prefs.getString("port", "6767"))
        streamIdEdit.setText(prefs.getString("streamId", ""))
        passphraseEdit.setText(prefs.getString("passphrase", ""))
        muteCheckBox.isChecked = prefs.getBoolean("muted", false)

        bitrateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bitrateOptions.map { "$it Mbps" })
        latencySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, latencyOptions.map { "$it ms" })
        fpsSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fpsOptions.map { "$it fps" })
        bitrateSpinner.setSelection(prefs.getInt("bitrate", bitrateOptions.indexOf(4)).coerceIn(0, bitrateOptions.lastIndex))
        latencySpinner.setSelection(prefs.getInt("latency", latencyOptions.indexOf(200)).coerceIn(0, latencyOptions.lastIndex))
        fpsSpinner.setSelection(prefs.getInt("fps", fpsOptions.indexOf(30)).coerceIn(0, fpsOptions.lastIndex))

        fun populateDevices() {
            val currentCamera = cameraSpinner.selectedItemPosition.takeIf { it >= 0 }
                ?.let { cameraChoices.getOrNull(it)?.id }
                ?: prefs.getString("cameraId", null)
            val currentMic = microphoneSpinner.selectedItemPosition.takeIf { it >= 0 }
                ?.let { microphoneChoices.getOrNull(it)?.id }
                ?: if (prefs.contains("microphoneId")) prefs.getInt("microphoneId", -1) else null

            cameraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraChoices.map { it.label })
            microphoneSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, microphoneChoices.map { it.label })

            val cameraIndex = cameraChoices.indexOfFirst { it.id == currentCamera }.takeIf { it >= 0 } ?: defaultCameraIndex()
            val micIndex = microphoneChoices.indexOfFirst { it.id == currentMic }.takeIf { it >= 0 } ?: 0
            if (cameraChoices.isNotEmpty()) cameraSpinner.setSelection(cameraIndex)
            if (microphoneChoices.isNotEmpty()) microphoneSpinner.setSelection(micIndex)
        }

        populateDevices()
        refreshButton.setOnClickListener {
            refreshDeviceLists()
            populateDevices()
            showToast("Dispositivos atualizados.")
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setView(view)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = hostEdit.text.toString().trim()
                val port = portEdit.text.toString().toIntOrNull()
                if (host.isBlank() || port == null || port !in 1..65535) {
                    showToast("Host ou porta inválidos.")
                    return@setOnClickListener
                }

                val cameraId = cameraChoices.getOrNull(cameraSpinner.selectedItemPosition)?.id
                val microphoneId = microphoneChoices.getOrNull(microphoneSpinner.selectedItemPosition)?.id

                prefs.edit()
                    .putString("host", host)
                    .putString("port", port.toString())
                    .putString("streamId", streamIdEdit.text.toString().trim())
                    .putString("passphrase", passphraseEdit.text.toString())
                    .putString("cameraId", cameraId)
                    .apply {
                        if (microphoneId == null) remove("microphoneId") else putInt("microphoneId", microphoneId)
                    }
                    .putInt("bitrate", bitrateSpinner.selectedItemPosition)
                    .putInt("latency", latencySpinner.selectedItemPosition)
                    .putInt("fps", fpsSpinner.selectedItemPosition)
                    .putBoolean("muted", muteCheckBox.isChecked)
                    .apply()

                isMuted = muteCheckBox.isChecked
                lifecycleScope.launch {
                    runCatching {
                        applySelectedCamera()
                        applyVideoConfig()
                        restartAudioPreview()
                    }.onFailure { showToast("Erro ao aplicar configuração: ${it.message}") }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun refreshDeviceLists() {
        refreshCameraList()
        refreshMicrophoneList()
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
    }

    private fun refreshMicrophoneList() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        microphoneChoices = listOf(MicrophoneChoice(null, "Automático do Android")) + devices.map { device ->
            MicrophoneChoice(device.id, microphoneLabel(device))
        }
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
        return if (product != null && !product.equals(type, ignoreCase = true)) "$type • $product" else type
    }

    private fun defaultCameraIndex(): Int {
        val defaultId = runCatching { defaultCameraId }.getOrNull()
        return cameraChoices.indexOfFirst { it.id == defaultId }.takeIf { it >= 0 } ?: 0
    }

    private fun selectedCameraId(): String {
        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        val saved = prefs.getString("cameraId", null)
        return cameraChoices.firstOrNull { it.id == saved }?.id
            ?: cameraChoices.getOrNull(defaultCameraIndex())?.id
            ?: throw IllegalStateException("Nenhuma câmera disponível")
    }

    private fun selectedMicrophoneId(): Int? {
        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        val saved = if (prefs.contains("microphoneId")) prefs.getInt("microphoneId", -1) else null
        return microphoneChoices.firstOrNull { it.id == saved }?.id
    }

    private suspend fun applySelectedCamera() {
        streamer.setCameraId(selectedCameraId())
    }

    private suspend fun applySelectedMicrophone() {
        streamer.setAudioSource(PreferredMicrophoneSourceFactory(selectedMicrophoneId()))
        streamer.audioInput.isMuted = isMuted
    }

    private suspend fun applyVideoConfig() {
        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        val bitrateIndex = prefs.getInt("bitrate", bitrateOptions.indexOf(4)).coerceIn(0, bitrateOptions.lastIndex)
        val fpsIndex = prefs.getInt("fps", fpsOptions.indexOf(30)).coerceIn(0, fpsOptions.lastIndex)
        streamer.setVideoConfig(
            VideoConfig(
                mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
                startBitrate = bitrateOptions[bitrateIndex] * 1_000_000,
                resolution = Size(1920, 1080),
                fps = fpsOptions[fpsIndex]
            )
        )
    }

    private fun startAudioPreview() {
        if (!isStreaming && hasMicPermission()) audioPreviewMonitor.start(selectedMicrophoneId())
    }

    private fun restartAudioPreview() {
        audioPreviewMonitor.stop()
        startAudioPreview()
    }

    private fun startLive() {
        if (!hasCameraPermission() || !hasMicPermission()) {
            requestPermissionsIfNeeded()
            return
        }
        if (!isPrepared) {
            prepareStreamer()
            return
        }

        val prefs = getSharedPreferences("stream", MODE_PRIVATE)
        val host = prefs.getString("host", "192.168.20.53")?.trim().orEmpty()
        val port = prefs.getString("port", "6767")?.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            showToast("Abra as configurações e informe um host/porta válidos.")
            return
        }

        val latencyIndex = prefs.getInt("latency", latencyOptions.indexOf(200)).coerceIn(0, latencyOptions.lastIndex)
        val streamId = prefs.getString("streamId", "")?.trim()?.ifBlank { null }
        val passphrase = prefs.getString("passphrase", "")?.ifBlank { null }
        isMuted = prefs.getBoolean("muted", false)

        setStatus("CONECTANDO")
        binding.liveButton.isEnabled = false
        audioPreviewMonitor.stop()

        lifecycleScope.launch {
            try {
                applySelectedCamera()
                applySelectedMicrophone()
                applyVideoConfig()
                val descriptor = SrtMediaDescriptor(
                    host = host,
                    port = port,
                    streamId = streamId,
                    passPhrase = passphrase,
                    latency = latencyOptions[latencyIndex]
                )
                streamer.startStream(descriptor)
                isStreaming = true
                setStatus("AO VIVO")
            } catch (t: Throwable) {
                isStreaming = false
                setStatus("ERRO")
                startAudioPreview()
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
                setStatus("OFFLINE")
                binding.liveButton.isEnabled = true
                startAudioPreview()
            }
        }
    }

    private fun setStatus(text: String) {
        binding.statusText.text = text
        val color = when (text) {
            "AO VIVO" -> Color.rgb(244, 67, 54)
            "CONECTANDO" -> Color.rgb(255, 152, 0)
            "ERRO", "SEM PERMISSÃO" -> Color.rgb(183, 28, 28)
            else -> Color.rgb(110, 35, 35)
        }
        binding.liveButton.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        audioPreviewMonitor.stop()
        try {
            streamer.releaseBlocking()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }
}
