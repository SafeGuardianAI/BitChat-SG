package com.bitchat.android.mesh.distributed

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides AES-256-GCM encryption and decryption for [MemoryShard] payloads.
 *
 * Encryption flow:
 *  1. Derive a 256-bit AES key from the owner's Noise key pair using HKDF-SHA256.
 *  2. Generate a random 12-byte IV per shard.
 *  3. Encrypt with AES/GCM/NoPadding (128-bit auth tag).
 *  4. Prepend the IV to the ciphertext and Base64-encode the result.
 *
 * The plaintext SHA-256 checksum is computed *before* encryption so that the
 * owner can verify integrity after decryption without exposing plaintext to peers.
 */
object ShardEncryption {

    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val HKDF_INFO = "SafeGuardian-DistributedMemory-v1"

    private val secureRandom = SecureRandom()

    // ---- Public API --------------------------------------------------------

    /**
     * Encrypt [plaintext] using a key derived from [ownerKeyMaterial].
     *
     * @param plaintext      Raw bytes to encrypt.
     * @param ownerKeyMaterial  Owner's Noise private key bytes (or shared secret).
     * @return A pair of (Base64 ciphertext, SHA-256 hex checksum of plaintext).
     */
    fun encrypt(plaintext: ByteArray, ownerKeyMaterial: ByteArray): Pair<String, String> {
        val checksum = sha256Hex(plaintext)
        val aesKey = deriveKey(ownerKeyMaterial)

        val iv = ByteArray(GCM_IV_LENGTH).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val ciphertext = cipher.doFinal(plaintext)

        // IV || ciphertext
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

        val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
        return Pair(encoded, checksum)
    }

    /**
     * Decrypt a Base64-encoded ciphertext that was produced by [encrypt].
     *
     * @param encryptedPayload  Base64 string (IV prepended).
     * @param ownerKeyMaterial  Same key material used during encryption.
     * @param expectedChecksum  SHA-256 hex digest to verify after decryption.
     * @return Decrypted plaintext bytes.
     * @throws ShardIntegrityException if the checksum does not match.
     * @throws javax.crypto.AEADBadTagException if the auth tag is invalid.
     */
    fun decrypt(
        encryptedPayload: String,
        ownerKeyMaterial: ByteArray,
        expectedChecksum: String
    ): ByteArray {
        val combined = Base64.decode(encryptedPayload, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val aesKey = deriveKey(ownerKeyMaterial)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))

        val plaintext = cipher.doFinal(ciphertext)

        // Verify integrity
        val actualChecksum = sha256Hex(plaintext)
        if (actualChecksum != expectedChecksum) {
            throw ShardIntegrityException(
                "Checksum mismatch: expected $expectedChecksum, got $actualChecksum"
            )
        }
        return plaintext
    }

    /**
     * Generate a fresh random AES-256 key for scenarios where no Noise key
     * material is available (e.g. first-time bootstrap).
     */
    fun generateRandomKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(AES_KEY_SIZE, secureRandom)
        return keyGen.generateKey()
    }

    /**
     * Compute HMAC-SHA256 of [data] using [key] for additional integrity checks
     * at the protocol layer.
     */
    fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ---- Internal helpers --------------------------------------------------

    /**
     * Derive a 256-bit AES [SecretKeySpec] from raw key material using
     * a simplified HKDF-expand step (HMAC-SHA256 with fixed info string).
     */
    private fun deriveKey(keyMaterial: ByteArray): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyMaterial, "HmacSHA256"))
        val derived = mac.doFinal(HKDF_INFO.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(derived, "AES")
    }

    /** SHA-256 hex digest. */
    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}

/** Thrown when a decrypted shard's checksum does not match the expected value. */
class ShardIntegrityException(message: String) : SecurityException(message)
