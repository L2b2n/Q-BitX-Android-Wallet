package org.qbitx.wallet.crypto

import java.security.MessageDigest

/**
 * Utility functions for Dilithium address derivation.
 * Mirrors Q-BitX Core's address scheme: RIPEMD160(SHA256(pubkey)) → Base58Check
 */
object AddressUtils {

    private const val DILITHIUM_PKHASH_VERSION = 0x32.toByte() // 'M' prefix for mainnet (version byte 50)

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

    /** RIPEMD-160 hash. Always uses our verified pure-Kotlin implementation. */
    fun ripemd160(input: ByteArray): ByteArray {
        return ripemd160Pure(input)
    }

    // =============== Pure-Kotlin RIPEMD-160 ===============
    // Faithful implementation of the RIPEMD-160 hash per the original spec
    // (Dobbertin, Bosselaers, Preneel — 1996)

    private fun ripemd160Pure(message: ByteArray): ByteArray {
        // Padding: append 1-bit, then zeros, then 64-bit LE length
        val msgLen = message.size
        val bitLen = msgLen.toLong() * 8

        // Number of bytes after padding must be multiple of 64
        var padLen = 64 - ((msgLen + 9) % 64)
        if (padLen == 64) padLen = 0
        val padded = ByteArray(msgLen + 1 + padLen + 8)
        System.arraycopy(message, 0, padded, 0, msgLen)
        padded[msgLen] = 0x80.toByte()
        // Little-endian 64-bit length
        for (i in 0..7) padded[padded.size - 8 + i] = ((bitLen ushr (i * 8)) and 0xFF).toByte()

        // Initial hash values
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        // Process each 64-byte block
        var offset = 0
        while (offset < padded.size) {
            val x = IntArray(16)
            for (i in 0..15) {
                x[i] = (padded[offset + i * 4].toInt() and 0xFF) or
                        ((padded[offset + i * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((padded[offset + i * 4 + 2].toInt() and 0xFF) shl 16) or
                        ((padded[offset + i * 4 + 3].toInt() and 0xFF) shl 24)
            }

            var al = h0; var bl = h1; var cl = h2; var dl = h3; var el = h4
            var ar = h0; var br = h1; var cr = h2; var dr = h3; var er = h4

            // Left rounds
            val rl = intArrayOf(
                0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,
                7,4,13,1,10,6,15,3,12,0,9,5,2,14,11,8,
                3,10,14,4,9,15,8,1,2,7,0,6,13,11,5,12,
                1,9,11,10,0,8,12,4,13,3,7,15,14,5,6,2,
                4,0,5,9,7,12,2,10,14,1,3,8,11,6,15,13
            )
            val sl = intArrayOf(
                11,14,15,12,5,8,7,9,11,13,14,15,6,7,9,8,
                7,6,8,13,11,9,7,15,7,12,15,9,11,7,13,12,
                11,13,6,7,14,9,13,15,14,8,13,6,5,12,7,5,
                11,12,14,15,14,15,9,8,9,14,5,6,8,6,5,12,
                9,15,5,11,6,8,13,12,5,12,13,14,11,8,5,6
            )
            // Right rounds
            val rr = intArrayOf(
                5,14,7,0,9,2,11,4,13,6,15,8,1,10,3,12,
                6,11,3,7,0,13,5,10,14,15,8,12,4,9,1,2,
                15,5,1,3,7,14,6,9,11,8,12,2,10,0,4,13,
                8,6,4,1,3,11,15,0,5,12,2,13,9,7,10,14,
                12,15,10,4,1,5,8,7,6,2,13,14,0,3,9,11
            )
            val sr = intArrayOf(
                8,9,9,11,13,15,15,5,7,7,8,11,14,14,12,6,
                9,13,15,7,12,8,9,11,7,7,12,7,6,15,13,11,
                9,7,15,11,8,6,6,14,12,13,5,14,13,13,7,5,
                15,5,8,11,14,14,6,14,6,9,12,9,12,5,15,8,
                8,5,12,9,12,5,14,6,8,13,6,5,15,13,11,11
            )

            for (j in 0..79) {
                val fl: Int
                val kl: Int
                val fr: Int
                val kr: Int
                when (j / 16) {
                    0 -> { fl = bl xor cl xor dl;              kl = 0x00000000
                           fr = br xor (cr or dr.inv());       kr = 0x50A28BE6.toInt() }
                    1 -> { fl = (bl and cl) or (bl.inv() and dl); kl = 0x5A827999
                           fr = (br and dr) or (cr and dr.inv()); kr = 0x5C4DD124 }
                    2 -> { fl = (bl or cl.inv()) xor dl;       kl = 0x6ED9EBA1
                           fr = (br or cr.inv()) xor dr;       kr = 0x6D703EF3 }
                    3 -> { fl = (bl and dl) or (cl and dl.inv()); kl = 0x8F1BBCDC.toInt()
                           fr = (br and cr) or (br.inv() and dr); kr = 0x7A6D76E9 }
                    else -> { fl = bl xor (cl or dl.inv());    kl = 0xA953FD4E.toInt()
                              fr = br xor cr xor dr;           kr = 0x00000000 }
                }

                val tl = (al + fl + x[rl[j]] + kl).rotl(sl[j]) + el
                al = el; el = dl; dl = cl.rotl(10); cl = bl; bl = tl

                val tr = (ar + fr + x[rr[j]] + kr).rotl(sr[j]) + er
                ar = er; er = dr; dr = cr.rotl(10); cr = br; br = tr
            }

            val t = h1 + cl + dr
            h1 = h2 + dl + er
            h2 = h3 + el + ar
            h3 = h4 + al + br
            h4 = h0 + bl + cr
            h0 = t

            offset += 64
        }

        // Produce 20-byte (160-bit) hash in little-endian
        val result = ByteArray(20)
        for (i in 0..3) {
            result[i]      = ((h0 ushr (i * 8)) and 0xFF).toByte()
            result[i + 4]  = ((h1 ushr (i * 8)) and 0xFF).toByte()
            result[i + 8]  = ((h2 ushr (i * 8)) and 0xFF).toByte()
            result[i + 12] = ((h3 ushr (i * 8)) and 0xFF).toByte()
            result[i + 16] = ((h4 ushr (i * 8)) and 0xFF).toByte()
        }
        return result
    }

    private fun Int.rotl(n: Int): Int = (this shl n) or (this ushr (32 - n))

    /** Hex encode a byte array. */
    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Hex decode a string to byte array. */
    fun fromHex(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
