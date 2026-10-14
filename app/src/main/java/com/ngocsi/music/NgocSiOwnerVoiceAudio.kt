package com.ngocsi.music

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures a short utterance locally so the exact same PCM can be used both for
 * on-device speaker verification and Android speech recognition. PCM stays in memory.
 */
internal object NgocSiOwnerVoiceAudio {
    const val SAMPLE_RATE = 16_000
    private const val VOICE_THRESHOLD_RMS = 420.0
    private const val PRE_ROLL_BYTES = SAMPLE_RATE * 2 / 4
    private const val MIN_CAPTURE_BYTES = SAMPLE_RATE * 2
    private const val END_SILENCE_MILLIS = 750L
    private const val MAX_CAPTURE_MILLIS = 12_000L

    @SuppressLint("MissingPermission")
    fun captureUtterance(
        shouldStop: () -> Boolean,
        onVoiceDetected: () -> Unit = {}
    ): ByteArray? {
        val minimumBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minimumBuffer <= 0 || shouldStop()) return null

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimumBuffer * 2, SAMPLE_RATE / 2)
            )
        } catch (_: Exception) {
            return null
        }

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED || shouldStop()) return null
            recorder.startRecording()
            val buffer = ByteArray(2048)
            val preRoll = ByteArrayOutputStream()
            val captured = ByteArrayOutputStream()
            val captureStartedAt = System.currentTimeMillis()
            var speechStartedAt = 0L
            var lastVoiceAt = 0L

            while (!shouldStop() &&
                System.currentTimeMillis() - captureStartedAt < MAX_CAPTURE_MILLIS
            ) {
                val count = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (_: Exception) {
                    return null
                }
                if (count <= 0) continue

                var energy = 0.0
                var index = 0
                while (index + 1 < count) {
                    val sample = (
                        (buffer[index + 1].toInt() shl 8) or
                            (buffer[index].toInt() and 0xff)
                        ).toShort().toInt()
                    energy += sample.toDouble() * sample
                    index += 2
                }
                val rms = kotlin.math.sqrt(energy / (count / 2).coerceAtLeast(1))
                val now = System.currentTimeMillis()

                if (speechStartedAt == 0L && rms >= VOICE_THRESHOLD_RMS) {
                    speechStartedAt = now
                    lastVoiceAt = now
                    runCatching { onVoiceDetected() }
                    val previousAudio = preRoll.toByteArray()
                    if (previousAudio.isNotEmpty()) captured.write(previousAudio)
                    captured.write(buffer, 0, count)
                } else if (speechStartedAt == 0L) {
                    preRoll.write(buffer, 0, count)
                    if (preRoll.size() > PRE_ROLL_BYTES) {
                        val previousAudio = preRoll.toByteArray()
                        preRoll.reset()
                        preRoll.write(
                            previousAudio,
                            previousAudio.size - PRE_ROLL_BYTES,
                            PRE_ROLL_BYTES
                        )
                    }
                } else {
                    if (rms >= VOICE_THRESHOLD_RMS) lastVoiceAt = now
                    captured.write(buffer, 0, count)
                    if (now - speechStartedAt >= 1_000L &&
                        now - lastVoiceAt >= END_SILENCE_MILLIS
                    ) break
                }
            }

            val pcm = captured.toByteArray()
            return pcm.takeIf {
                !shouldStop() && it.size >= MIN_CAPTURE_BYTES
            }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            runCatching { recorder.release() }
        }
    }
}

/**
 * Feeds already-captured raw 16 kHz mono PCM into SpeechRecognizer (Android 13+).
 * This avoids depending on the optional onBufferReceived callback for speaker checks.
 */
internal class NgocSiVoiceAudioSourcePipe private constructor(
    private val readDescriptor: ParcelFileDescriptor,
    private val writeDescriptor: ParcelFileDescriptor,
    private val pcm: ByteArray
) {
    private val writerStarted = AtomicBoolean(false)

    fun writeAsync(scope: CoroutineScope) {
        if (!writerStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeDescriptor).use { output ->
                    output.write(pcm)
                    output.flush()
                }
            } catch (_: Exception) {
                runCatching { writeDescriptor.close() }
            }
        }
    }

    fun close() {
        runCatching { readDescriptor.close() }
        if (!writerStarted.get()) runCatching { writeDescriptor.close() }
    }

    companion object {
        fun create(intent: Intent, pcm: ByteArray): NgocSiVoiceAudioSourcePipe? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || pcm.isEmpty()) return null
            val descriptors = try {
                ParcelFileDescriptor.createPipe()
            } catch (_: Exception) {
                return null
            }
            return try {
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, descriptors[0])
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                intent.putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                intent.putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                    NgocSiOwnerVoiceAudio.SAMPLE_RATE
                )
                NgocSiVoiceAudioSourcePipe(descriptors[0], descriptors[1], pcm)
            } catch (_: Exception) {
                runCatching { descriptors[0].close() }
                runCatching { descriptors[1].close() }
                null
            }
        }
    }
}
