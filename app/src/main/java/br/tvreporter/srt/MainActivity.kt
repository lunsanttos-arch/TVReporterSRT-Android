package br.tvreporter.srt

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.MediaFormat
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import br.tvreporter.srt.databinding.ActivityMainBinding
import io.github.thibaultbee.streampack.core.elements.sources.audio.audiorecord.MicrophoneSourceFactory
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

    private val bitrateOptions = listOf(2, 3, 4, 6, 8)
    private val latencyOptions = listOf(120, 200, 300, 500, 1000)
    private val fpsOptions = listOf(30, 60)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cameraGranted = result[Manifest.permission.CAMERA] == true
            val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
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

        binding.liveButton.setOnClickListener {
            if (isStreaming) stopLive() else startLive()
        }

        binding.muteButton.setOnClickListener {
            isMuted = !isMuted
            streamer.audioInput.isMuted = isMuted
            binding.muteButton.text = if (isMuted) "Ativar áudio" else "Mute"
        }

        binding.switchCameraButton.setOnClickListener {
            if (!hasCameraPermission()) return@setOnClickListener
            lifecycleScope.launch {
                runCatching { streamer.switchBackToFront(this@MainActivity) }
                    .onFailure { showToast("Erro ao trocar câmera: ${it.message}") }
            }
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
                streamer.setAudioSource(MicrophoneSourceFactory())
                streamer.setCameraId(defaultCameraId)
                streamer.setAudioConfig(
                    AudioConfig(
                        mimeType = MediaFormat.MIMETYPE_AUDIO_AAC,
                        startBitrate = 128_000,
                        sampleRate = 48_000,
                        channelConfig = AudioFormat.CHANNEL_IN_STEREO
                    )
                )
                applyVideoConfig()
                binding.preview.setVideoSourceProvider(streamer)
                setStatus("PRONTO")
            } catch (t: Throwable) {
                setStatus("ERRO")
                showToast("Erro ao preparar câmera: ${t.message}")
            }
        }
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
                showToast("Falha SRT: ${t.message}")
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
        getSharedPreferences("stream", MODE_PRIVATE).edit()
            .putString("host", binding.hostEdit.text.toString())
            .putString("port", binding.portEdit.text.toString())
            .putString("streamId", binding.streamIdEdit.text.toString())
            .putString("passphrase", binding.passphraseEdit.text.toString())
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
        if (isStreaming) {
            try {
                streamer.releaseBlocking()
            } catch (_: Throwable) {
            }
        } else {
            streamer.releaseBlocking()
        }
        super.onDestroy()
    }
}
