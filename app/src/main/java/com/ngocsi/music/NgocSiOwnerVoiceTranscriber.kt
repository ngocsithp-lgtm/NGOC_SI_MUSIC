package com.ngocsi.music

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fully on-device Vietnamese speech-to-text for voice-protected commands.
 * It operates on PCM captured by NgocSiOwnerVoiceAudio and makes no network requests.
 */
internal object NgocSiOwnerVoiceTranscriber {
    private const val MODEL_DIR = "sherpa-onnx-zipformer-vi-30M-int8-2026-02-09"
    private const val SAMPLE_RATE = 16_000
    private const val MIN_SAMPLES = SAMPLE_RATE
    private val lock = Any()

    @Volatile
    private var offlineRecognizer: OfflineRecognizer? = null

    fun transcribe(context: Context, pcmLittleEndian: ByteArray): String? {
        val samples = pcm16ToFloat(pcmLittleEndian) ?: return null
        return synchronized(lock) {
            val recognizer = getRecognizer(context) ?: return@synchronized null
            var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
            try {
                stream = recognizer.createStream()
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                recognizer.getResult(stream).text
                    .trim()
                    .replace(Regex("\\s+"), " ")
                    .takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { stream?.release() }
            }
        }
    }

    private fun getRecognizer(context: Context): OfflineRecognizer? {
        offlineRecognizer?.let { return it }
        return runCatching {
            val modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder.int8.onnx",
                    decoder = "$MODEL_DIR/decoder.onnx",
                    joiner = "$MODEL_DIR/joiner.int8.onnx"
                ),
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "transducer"
            )
            OfflineRecognizer(
                assetManager = context.assets,
                config = OfflineRecognizerConfig(modelConfig = modelConfig)
            )
        }.getOrNull()?.also { offlineRecognizer = it }
    }

    private fun pcm16ToFloat(pcm: ByteArray): FloatArray? {
        val sampleCount = pcm.size / 2
        if (sampleCount < MIN_SAMPLES) return null
        val input = ByteBuffer.wrap(pcm, 0, sampleCount * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(sampleCount)
        var mean = 0.0
        for (i in samples.indices) {
            val value = input.short.toFloat() / 32768f
            samples[i] = value
            mean += value
        }
        val dc = (mean / sampleCount).toFloat()
        var energy = 0.0
        for (i in samples.indices) {
            samples[i] -= dc
            energy += samples[i] * samples[i]
        }
        if (energy / sampleCount < 0.000004) return null
        return samples
    }
}
