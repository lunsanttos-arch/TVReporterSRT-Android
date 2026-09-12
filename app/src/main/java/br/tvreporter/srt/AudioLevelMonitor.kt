package br.tvreporter.srt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

object AudioLevelMonitor {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    fun updateFromPcm16(data: ByteArray, bytesRead: Int) {
        if (bytesRead < 2) {
            _level.value = 0f
            return
        }

        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < bytesRead) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample.toDouble() * sample.toDouble()
            samples++
            i += 2
        }

        if (samples == 0) {
            _level.value = 0f
            return
        }

        val rms = sqrt(sum / samples)
        val normalized = (rms / 10000.0).coerceIn(0.0, 1.0)
        _level.value = normalized.toFloat()
    }

    fun clear() {
        _level.value = 0f
    }
}
