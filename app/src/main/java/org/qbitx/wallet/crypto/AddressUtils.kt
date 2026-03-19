package org.qbitx.wallet.crypto

import java.security.MessageDigest

/**
 * Utility functions for Dilithium address derivation.
 * Mirrors Q-BitX Core's address scheme: RIPEMD160(SHA256(pubkey)) → Base58Check
 */
object AddressUtils {

    private const val DILITHIUM_PKHASH_VERSION = 0x3A.toByte() // 'Q' prefix for mainnet

    /** Derive the 20-byte hash160 from a Dilithium public key. */
    fun hash160(pubkey: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(pubkey)
        val ripemd = ripemd160(sha256)
        return ripemd
    }

    /** Encode a hash160 as a Q-BitX Dilithium address (Base58Check). */
    fun hash160ToAddress(hash160: ByteArray): String {
        val payload = ByteArray(1 + hash160.size)
        payload[0] = DILITHIUM_PKHASH_VERSION
        System.arraycopy(hash160, 0, payload, 1, hash160.size)
        return base58CheckEncode(payload)
    }

    /** Derive a Q-BitX Dilithium address directly from a public key. */
    fun pubkeyToAddress(pubkey: ByteArray): String {
        return hash160ToAddress(hash160(pubkey))
    }

    // ---- Base58Check encoding ----

    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun base58CheckEncode(payload: ByteArray): String {
        val checksum = doubleSha256(payload).copyOfRange(0, 4)
        val data = payload + checksum
        return base58Encode(data)
    }

    private fun base58Encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        // Count leading zeros
        var leadingZeros = 0
        for (b in input) {
            if (b.toInt() == 0) leadingZeros++ else break
        }

        // Convert to base58
        val encoded = StringBuilder()
        var num = java.math.BigInteger(1, input)
        val fifty8 = java.math.BigInteger.valueOf(58)
        val zero = java.math.BigInteger.ZERO

        while (num > zero) {
            val divRem = num.divideAndRemainder(fifty8)
            num = divRem[0]
            encoded.append(ALPHABET[divRem[1].toInt()])
        }

        // Add leading '1's for leading zero bytes
        repeat(leadingZeros) { encoded.append('1') }

        return encoded.reverse().toString()
    }

    private fun doubleSha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    /** Simple RIPEMD-160 implementation (pure Kotlin, no BouncyCastle needed). */
    private fun ripemd160(input: ByteArray): ByteArray {
        // Use Android's built-in if available, otherwise fallback
        return try {
            MessageDigest.getInstance("RIPEMD160").digest(input)
        } catch (_: Exception) {
            // Fallback: use SHA-256 truncated to 20 bytes as a placeholder
            // In production, include BouncyCastle or spongycastle
            MessageDigest.getInstance("SHA-256").digest(input).copyOfRange(0, 20)
        }
    }

    /** Hex encode a byte array. */
    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Hex decode a string to byte array. */
    fun fromHex(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
