package br.tvreporter.srt

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class ReturnSrtPlayer(
    context: Context,
    private val videoLayout: VLCVideoLayout,
    private val onStateChanged: (String) -> Unit
) {
    private val libVlc = LibVLC(
        context,
        arrayListOf(
            "--network-caching=120",
            "--live-caching=120",
            "--clock-jitter=0",
            "--clock-synchro=0"
        )
    )

    private val player = MediaPlayer(libVlc)

    init {
        player.attachViews(videoLayout, null, false, false)
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> onStateChanged("CONECTANDO")
                MediaPlayer.Event.Playing -> onStateChanged("RETORNO OK")
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering < 100f) onStateChanged("BUFFER \${event.buffering.toInt()}%")
                }
                MediaPlayer.Event.EndReached -> onStateChanged("SEM SINAL")
                MediaPlayer.Event.EncounteredError -> onStateChanged("ERRO")
                MediaPlayer.Event.Stopped -> onStateChanged("PARADO")
            }
        }
    }

    fun play(host: String, port: Int) {
        stop()

        val media = Media(libVlc, Uri.parse("srt://\$host:\$port"))
        media.addOption(":network-caching=120")
        media.addOption(":live-caching=120")
        media.addOption(":drop-late-frames")
        media.addOption(":skip-frames")
        player.media = media
        media.release()

        onStateChanged("CONECTANDO")
        player.play()
    }

    fun stop() {
        if (player.isPlaying) player.stop()
    }

    fun release() {
        runCatching { player.stop() }
        runCatching { player.detachViews() }
        runCatching { player.release() }
        runCatching { libVlc.release() }
    }
}
