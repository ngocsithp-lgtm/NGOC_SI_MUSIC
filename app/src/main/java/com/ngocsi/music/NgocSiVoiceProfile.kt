package com.ngocsi.music

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.sqrt

/**
 * Local-only owner speaker verification for the AI Preview build.
 *
 * Uses a neural speaker embedding model (sherpa-onnx / 3D-Speaker) on-device. Only a normalized
 * embedding is encrypted and retained. Temporary PCM buffers stay in memory and are discarded.
 * This is speaker similarity, not presentation-attack/liveness detection; recordings and voice
 * synthesis can still defeat it, so do not use it as the sole security control for sensitive data.
 */
internal object NgocSiVoiceProfile {
    private const val PREFS = "ngoc_si_owner_voice"
    private const val KEY_PROFILE = "encrypted_profile"
    private const val KEY_PROFILE_VERSION = "profile_version"
    private const val PROFILE_VERSION = 2
    private const val KEY_LOCK_ENABLED = "voice_lock_enabled"
    private const val KEY_ALIAS = "ngoc_si_voice_profile_key"
    private const val MODEL_NAME = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
    private const val SAMPLE_RATE = 16_000
    private const val MIN_PCM_SAMPLES = SAMPLE_RATE
    private const val MIN_EMBEDDING_SIZE = 64
    // Initial conservative threshold. Device/noise-specific false accepts/rejects still need field tests.
    private const val COSINE_THRESHOLD = 0.55f

    private val nativeLock = Any()
    @Volatile private var extractor: SpeakerEmbeddingExtractor? = null

    fun hasProfile(context: Context): Boolean = loadProfile(context) != null

    fun isLockEnabled(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return preferences.getBoolean(KEY_LOCK_ENABLED, false) && hasProfile(context)
    }

    fun setLockEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && loadProfile(context) == null) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
        return true
    }

    fun clearProfile(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PROFILE)
            .remove(KEY_PROFILE_VERSION)
            .putBoolean(KEY_LOCK_ENABLED, false)
            .apply()
    }

    /**
     * Convert SpeechRecognizer's captured PCM16 buffer to a speaker embedding.
     * Android does not guarantee onBufferReceived on every recognition service; null means
     * "cannot verify", and callers must reject that utterance while voice lock is enabled.
     */
    fun extractFeatures(context: Context, pcmLittleEndian: ByteArray): FloatArray? {
        val samples = pcm16LittleEndianToFloat(pcmLittleEndian) ?: return null
        return synchronized(nativeLock) {
            val model = getExtractor(context) ?: return@synchronized null
            try {
                val stream = model.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    stream.inputFinished()
                    if (!model.isReady(stream)) return@synchronized null
                    normalizeEmbedding(model.compute(stream))
                } finally {
                    runCatching { stream.release() }
                }
            } catch (_: Throwable) {
                // Missing/corrupt model or native runtime must never become an authentication success.
                null
            }
        }
    }

    private fun pcm16LittleEndianToFloat(pcm: ByteArray): FloatArray? {
        val sampleCount = pcm.size / 2
        if (sampleCount < MIN_PCM_SAMPLES) return null
        val input = ByteBuffer.wrap(pcm, 0, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(sampleCount)
        var mean = 0.0
        for (index in samples.indices) {
            val sample = input.short.toFloat() / 32768f
            samples[index] = sample
            mean += sample
        }
        val dc = (mean / sampleCount).toFloat()
        var energy = 0.0
        for (index in samples.indices) {
            samples[index] -= dc
            energy += samples[index] * samples[index]
        }
        if (energy / sampleCount < 0.000004) return null
        return samples
    }

    private fun getExtractor(context: Context): SpeakerEmbeddingExtractor? {
        extractor?.let { return it }
        return runCatching {
            // The CI workflow packages the model as an APK asset. No network request or API key is
            // used at runtime; model inference stays on the phone.
            require(context.assets.list("")?.contains(MODEL_NAME) == true) {
                "Speaker embedding model is missing from Preview assets"
            }
            SpeakerEmbeddingExtractor(
                assetManager = context.assets,
                config = SpeakerEmbeddingExtractorConfig(
                    model = MODEL_NAME,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu"
                )
            ).also { extractor = it }
        }.getOrNull()
    }

    private fun normalizeEmbedding(input: FloatArray): FloatArray? {
        if (input.size < MIN_EMBEDDING_SIZE || input.any { !it.isFinite() }) return null
        var normSquared = 0.0
        for (value in input) normSquared += value.toDouble() * value
        if (normSquared < 1e-12) return null
        val norm = sqrt(normSquared).toFloat()
        return FloatArray(input.size) { index -> input[index] / norm }
    }

    fun combine(samples: List<FloatArray>): FloatArray? {
        if (samples.size < 3) return null
        val dimension = samples.firstOrNull()?.size ?: return null
        if (dimension < MIN_EMBEDDING_SIZE ||
            samples.any { it.size != dimension || it.any { value -> !value.isFinite() } }
        ) return null

        val combined = FloatArray(dimension)
        for (sample in samples) {
            for (index in 0 until dimension) combined[index] += sample[index] / samples.size
        }
        return normalizeEmbedding(combined)
    }

    fun matches(enrolled: FloatArray, candidate: FloatArray): Boolean {
        if (enrolled.size < MIN_EMBEDDING_SIZE || enrolled.size != candidate.size) return false
        if (enrolled.any { !it.isFinite() } || candidate.any { !it.isFinite() }) return false
        var dot = 0.0
        var enrolledNorm = 0.0
        var candidateNorm = 0.0
        for (index in enrolled.indices) {
            dot += enrolled[index].toDouble() * candidate[index]
            enrolledNorm += enrolled[index].toDouble() * enrolled[index]
            candidateNorm += candidate[index].toDouble() * candidate[index]
        }
        if (enrolledNorm < 1e-12 || candidateNorm < 1e-12) return false
        return (dot / sqrt(enrolledNorm * candidateNorm)).toFloat() >= COSINE_THRESHOLD
    }

    fun saveProfile(context: Context, features: FloatArray): Boolean {
        val normalized = normalizeEmbedding(features) ?: return false
        return runCatching {
            val plain = normalized.joinToString(",") { it.toString() }.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plain)
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROFILE, packed)
                .putInt(KEY_PROFILE_VERSION, PROFILE_VERSION)
                .putBoolean(KEY_LOCK_ENABLED, true)
                .commit()
        }.getOrDefault(false)
    }

    fun loadProfile(context: Context): FloatArray? = runCatching {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (preferences.getInt(KEY_PROFILE_VERSION, 0) != PROFILE_VERSION) return null
        val packed = preferences.getString(KEY_PROFILE, null) ?: return null
        val separator = packed.indexOf(':')
        require(separator > 0 && separator < packed.lastIndex)
        val iv = Base64.decode(packed.substring(0, separator), Base64.NO_WRAP)
        val encrypted = Base64.decode(packed.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val decoded = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            .split(',').map(String::toFloat)
        normalizeEmbedding(decoded.toFloatArray())
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
