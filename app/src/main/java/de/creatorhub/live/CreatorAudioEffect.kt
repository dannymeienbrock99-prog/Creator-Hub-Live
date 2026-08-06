package de.creatorhub.live

import com.pedro.encoder.input.audio.CustomAudioEffect
import kotlin.math.abs

class CreatorAudioEffect(
    private val onLevel: (Int) -> Unit
) : CustomAudioEffect() {

    @Volatile
    var gain: Float = 1f

    override fun process(pcmBuffer: ByteArray): ByteArray {
        var peak = 0
        var index = 0
        while (index + 1 < pcmBuffer.size) {
            val original = ((pcmBuffer[index + 1].toInt() shl 8) or
                (pcmBuffer[index].toInt() and 0xFF)).toShort().toInt()
            val amplified = (original * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            peak = maxOf(peak, abs(amplified))
            pcmBuffer[index] = (amplified and 0xFF).toByte()
            pcmBuffer[index + 1] = ((amplified shr 8) and 0xFF).toByte()
            index += 2
        }
        onLevel(((peak / 32767f) * 100f).toInt().coerceIn(0, 100))
        return pcmBuffer
    }
}
