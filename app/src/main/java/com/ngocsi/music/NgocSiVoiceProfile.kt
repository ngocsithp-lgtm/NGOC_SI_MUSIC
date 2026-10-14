package com.ngocsi.music

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Experimental, local-only voice-print filter for the Preview build.
 *
 * This is a lightweight signal fingerprint, not a trained speaker-embedding model. It can
 * reduce casual false activation but must not be treated as strong biometric authentication.
 * Raw audio is never persisted; only the feature vector is encrypted using Android Keystore.
 */
internal object NgocSiVoiceProfile {
    private const val PREFS = "ngoc_si_owner_voice"
    private const val KEY_PROFILE = "encrypted_profile"
    private const val KEY_LOCK_ENABLED = "voice_lock_enabled"
    private const val KEY_ALIAS = "ngoc_si_voice_profile_key"
    private const val FRAME_SIZE = 256
    private const val HOP_SIZE = 128
    private const val BAND_COUNT = 16
    private const val CEPSTRA_COUNT = 10
    private const val FEATURE_COUNT = CEPSTRA_COUNT * 2
    private const val MIN_SAMPLES = 2_048
    private const val COSINE_THRESHOLD = 0.94f

    fun hasProfile(context: Context): Boolean = loadProfile(context) != null

    fun isLockEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCK_ENABLED, false) && hasProfile(context)

    fun setLockEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && loadProfile(context) == null) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LOCK_ENABLED, enabled).apply()
        return true
    }

    fun clearProfile(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_PROFILE).putBoolean(KEY_LOCK_ENABLED, false).apply()
    }

    fun extractFeatures(pcmBigEndian: ByteArray): FloatArray? {
        val sampleCount = pcmBigEndian.size / 2
        if (sampleCount < MIN_SAMPLES) return null
        val samples = FloatArray(sampleCount)
        val input = ByteBuffer.wrap(pcmBigEndian).order(ByteOrder.BIG_ENDIAN)
        var mean = 0.0
        for (i in 0 until sampleCount) {
            samples[i] = input.short.toFloat() / 32768f
            mean += samples[i]
        }
        val dc = (mean / sampleCount).toFloat()
        var peak = 0f
        for (i in samples.indices) {
            samples[i] -= dc
            peak = maxOf(peak, kotlin.math.abs(samples[i]))
        }
        if (peak < 0.008f) return null
        for (i in samples.indices) samples[i] /= peak

        val frameFeatures = ArrayList<FloatArray>()
        var start = 0
        while (start + FRAME_SIZE <= samples.size) {
            val bandLogs = FloatArray(BAND_COUNT)
            val bandCounts = IntArray(BAND_COUNT)
            for (bin in 1..FRAME_SIZE / 2) {
                var real = 0.0
                var imaginary = 0.0
                for (n in 0 until FRAME_SIZE) {
                    val window = 0.54 - 0.46 * cos(2.0 * Math.PI * n / (FRAME_SIZE - 1))
                    val value = samples[start + n] * window
                    val angle = 2.0 * Math.PI * bin * n / FRAME_SIZE
                    real += value * cos(angle)
                    imaginary -= value * kotlin.math.sin(angle)
                }
                val band = (((bin - 1) * BAND_COUNT) / (FRAME_SIZE / 2)).coerceIn(0, BAND_COUNT - 1)
                bandLogs[band] += (real * real + imaginary * imaginary).toFloat()
                bandCounts[band]++
            }
            for (band in 0 until BAND_COUNT) {
                bandLogs[band] = ln(
                    bandLogs[band] / maxOf(1, bandCounts[band]) + 1e-8f
                )
            }
            val cepstra = FloatArray(CEPSTRA_COUNT)
            for (coefficient in 1..CEPSTRA_COUNT) {
                var value = 0.0
                for (band in 0 until BAND_COUNT) {
                    value += bandLogs[band] * cos(
                        Math.PI * coefficient * (band + 0.5) / BAND_COUNT
                    )
                }
                cepstra[coefficient - 1] = value.toFloat()
            }
            frameFeatures.add(cepstra)
            start += HOP_SIZE
        }
        if (frameFeatures.size < 6) return null

        val output = FloatArray(FEATURE_COUNT)
        for (dimension in 0 until CEPSTRA_COUNT) {
            var average = 0.0
            for (frame in frameFeatures) average += frame[dimension]
            average /= frameFeatures.size
            output[dimension] = average.toFloat()
            var variance = 0.0
            for (frame in frameFeatures) {
                val delta = frame[dimension] - average
                variance += delta * delta
            }
            output[CEPSTRA_COUNT + dimension] =
                sqrt(variance / frameFeatures.size).toFloat()
        }
        return output.takeIf { vector ->
            vector.all { it.isFinite() } && vector.any { kotlin.math.abs(it) > 1e-5f }
        }
    }

    fun combine(samples: List<FloatArray>): FloatArray? {
        if (samples.size < 3 || samples.any { it.size != FEATURE_COUNT }) return null
        val result = FloatArray(FEATURE_COUNT)
        for (sample in samples) {
            for (i in result.indices) result[i] += sample[i] / samples.size
        }
        return result.takeIf { it.all(Float::isFinite) }
    }

    fun matches(enrolled: FloatArray, candidate: FloatArray): Boolean {
        if (enrolled.size != FEATURE_COUNT || candidate.size != FEATURE_COUNT) return false
        var dot = 0.0
        var a2 = 0.0
        var b2 = 0.0
        for (i in enrolled.indices) {
            dot += enrolled[i] * candidate[i]
            a2 += enrolled[i] * enrolled[i]
            b2 += candidate[i] * candidate[i]
        }
        if (a2 <= 1e-9 || b2 <= 1e-9) return false
        return (dot / sqrt(a2 * b2)).toFloat() >= COSINE_THRESHOLD
    }

    fun saveProfile(context: Context, features: FloatArray): Boolean {
        if (features.size != FEATURE_COUNT || features.any { !it.isFinite() }) return false
        return runCatching {
            val plain = features.joinToString(",") { it.toString() }.toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plain)
            val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROFILE, packed)
                .putBoolean(KEY_LOCK_ENABLED, true)
                .commit()
        }.getOrDefault(false)
    }

    fun loadProfile(context: Context): FloatArray? = runCatching {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PROFILE, null) ?: return null
        val separator = packed.indexOf(':')
        require(separator > 0 && separator < packed.lastIndex)
        val iv = Base64.decode(packed.substring(0, separator), Base64.NO_WRAP)
        val encrypted = Base64.decode(packed.substring(separator + 1), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val decoded = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            .split(',').map(String::toFloat)
        decoded.toFloatArray().takeIf { it.size == FEATURE_COUNT && it.all(Float::isFinite) }
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
