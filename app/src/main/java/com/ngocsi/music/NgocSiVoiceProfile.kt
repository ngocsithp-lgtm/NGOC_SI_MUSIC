package com.ngocsi.music

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
 * Uses a neural speaker embedding model (sherpa-onnx / 3D-Speaker) on-device. The enrolled
 * speaker templates are encrypted and retained. Temporary PCM buffers stay in memory and are discarded.
 * This is speaker similarity, not presentation-attack/liveness detection; recordings and voice
 * synthesis can still defeat it, so do not use it as the sole security control for sensitive data.
 */
internal object NgocSiVoiceProfile {
    private const val PREFS = "ngoc_si_owner_voice"
    private const val KEY_PROFILE = "encrypted_profile"
    private const val KEY_PROFILE_VERSION = "profile_version"
    private const val LEGACY_PROFILE_VERSION = 2
    private const val PROFILE_VERSION = 3
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

    fun hasProfile(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return preferences.getInt(KEY_PROFILE_VERSION, 0) in
            setOf(LEGACY_PROFILE_VERSION, PROFILE_VERSION) &&
            !preferences.getString(KEY_PROFILE, null).isNullOrBlank()
    }

    /**
     * Returns whether the user requested the lock, even if its profile is broken or from
     * an old schema. Callers must reject speech when the request is on but the profile fails
     * to decrypt/load; never silently turn a corrupt profile into "lock disabled".
     */
    fun isLockEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCK_ENABLED, false)

    fun setLockEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && loadProfiles(context) == null) return false
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

    fun matches(enrolled: FloatArray, candidate: FloatArray): Boolean =
        cosineSimilarity(enrolled, candidate) >= COSINE_THRESHOLD

    /**
     * Compare against each independently enrolled utterance rather than only the centroid.
     * This preserves natural variation in pitch, loudness and phrasing between enrollment
     * prompts and later commands while keeping the same conservative similarity threshold.
     */
    fun matches(enrolled: List<FloatArray>, candidate: FloatArray): Boolean {
        if (enrolled.isEmpty()) return false
        val bestSimilarity = enrolled.asSequence()
            .map { cosineSimilarity(it, candidate) }
            .filter { it.isFinite() }
            .maxOrNull() ?: return false
        return bestSimilarity >= COSINE_THRESHOLD
    }

    private fun cosineSimilarity(enrolled: FloatArray, candidate: FloatArray): Float {
        if (enrolled.size < MIN_EMBEDDING_SIZE || enrolled.size != candidate.size) return Float.NEGATIVE_INFINITY
        if (enrolled.any { !it.isFinite() } || candidate.any { !it.isFinite() }) return Float.NEGATIVE_INFINITY
        var dot = 0.0
        var enrolledNorm = 0.0
        var candidateNorm = 0.0
        for (index in enrolled.indices) {
            dot += enrolled[index].toDouble() * candidate[index]
            enrolledNorm += enrolled[index].toDouble() * enrolled[index]
            candidateNorm += candidate[index].toDouble() * candidate[index]
        }
        if (enrolledNorm < 1e-12 || candidateNorm < 1e-12) return Float.NEGATIVE_INFINITY
        return (dot / sqrt(enrolledNorm * candidateNorm)).toFloat()
    }

    fun saveProfile(context: Context, features: FloatArray): Boolean {
        // Keep compatibility for a single legacy embedding; new enrollments store all 3 samples.
        val normalized = normalizeEmbedding(features) ?: return false
        return saveEncryptedProfile(
            context = context,
            plain = normalized.joinToString(",") { it.toString() },
            version = LEGACY_PROFILE_VERSION
        )
    }

    fun saveProfile(context: Context, features: List<FloatArray>): Boolean {
        if (features.size < 3) return false
        val normalized = features.map { normalizeEmbedding(it) ?: return false }
        val dimension = normalized.first().size
        if (normalized.any { it.size != dimension }) return false
        val serialized = normalized.joinToString(";") { embedding ->
            embedding.joinToString(",") { it.toString() }
        }
        return saveEncryptedProfile(context, serialized, PROFILE_VERSION)
    }

    private fun saveEncryptedProfile(context: Context, plain: String, version: Int): Boolean =
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROFILE, packed)
                .putInt(KEY_PROFILE_VERSION, version)
                .putBoolean(KEY_LOCK_ENABLED, true)
                .commit()
        }.getOrDefault(false)

    fun loadProfile(context: Context): FloatArray? {
        val templates = loadProfiles(context) ?: return null
        return combine(templates) ?: templates.firstOrNull()
    }

    /**
     * Version 2 stored an averaged embedding. Version 3 stores the three enrollment templates.
     * Older profiles remain readable and can keep working until the owner re-enrolls.
     */
    fun loadProfiles(context: Context): List<FloatArray>? = runCatching {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = preferences.getInt(KEY_PROFILE_VERSION, 0)
        if (version != LEGACY_PROFILE_VERSION && version != PROFILE_VERSION) return@runCatching null
        val packed = preferences.getString(KEY_PROFILE, null) ?: return@runCatching null
        val separator = packed.indexOf(':')
        if (separator <= 0 || separator >= packed.lastIndex) return@runCatching null
        val iv = Base64.decode(packed.substring(0, separator), Base64.NO_WRAP)
        val encrypted = Base64.decode(packed.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val decoded = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        val serialized = if (version == LEGACY_PROFILE_VERSION) listOf(decoded) else decoded.split(';')
        val templates = serialized.map { entry ->
            val values = entry.split(',').map(String::toFloat).toFloatArray()
            normalizeEmbedding(values) ?: return@runCatching null
        }
        if (templates.isEmpty()) return@runCatching null
        val dimension = templates.first().size
        if (templates.any { it.size != dimension }) return@runCatching null
        if (version == PROFILE_VERSION && templates.size < 3) return@runCatching null
        templates
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
