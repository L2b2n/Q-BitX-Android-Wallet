package org.qbitx.wallet.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.qbitx.wallet.crypto.AddressUtils
import org.qbitx.wallet.crypto.DilithiumNative

/**
 * Manages Dilithium keypair storage using Android EncryptedSharedPreferences.
 * Keys are encrypted at rest with AES-256-GCM via Android Keystore.
 */
class KeyManager(context: Context) {

    private val dilithium = DilithiumNative()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "qbitx_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Check if a wallet already exists. */
    fun hasWallet(): Boolean = prefs.contains("pubkey")

    /** Generate a new Dilithium keypair and store it encrypted. */
    fun createWallet(): String {
        val keys = dilithium.generateKeypair()
            ?: throw RuntimeException("Dilithium key generation failed")

        val pubkey = keys[0]
        val privkey = keys[1]

        prefs.edit()
            .putString("pubkey", Base64.encodeToString(pubkey, Base64.NO_WRAP))
            .putString("privkey", Base64.encodeToString(privkey, Base64.NO_WRAP))
            .apply()

        return AddressUtils.pubkeyToAddress(pubkey)
    }

    /** Get the wallet's address. */
    fun getAddress(): String? {
        val pubkeyB64 = prefs.getString("pubkey", null) ?: return null
        val pubkey = Base64.decode(pubkeyB64, Base64.NO_WRAP)
        return AddressUtils.pubkeyToAddress(pubkey)
    }

    /** Get the public key bytes. */
    fun getPublicKey(): ByteArray? {
        val pubkeyB64 = prefs.getString("pubkey", null) ?: return null
        return Base64.decode(pubkeyB64, Base64.NO_WRAP)
    }

    /** Sign a message (transaction hash) with the stored private key. */
    fun sign(message: ByteArray): ByteArray? {
        val privkeyB64 = prefs.getString("privkey", null) ?: return null
        val privkey = Base64.decode(privkeyB64, Base64.NO_WRAP)
        val sig = dilithium.sign(message, privkey)
        // Wipe private key copy from memory
        privkey.fill(0)
        return sig
    }

    /** Verify a signature against a message using the wallet's public key. */
    fun verify(signature: ByteArray, message: ByteArray): Boolean {
        val pubkey = getPublicKey() ?: return false
        return dilithium.verify(signature, message, pubkey)
    }

    /** Export public key as hex string (for sharing). */
    fun exportPublicKeyHex(): String? {
        val pubkey = getPublicKey() ?: return null
        return AddressUtils.toHex(pubkey)
    }

    /** Export private key as hex string (for node import). */
    fun exportPrivateKeyHex(): String? {
        val privkeyB64 = prefs.getString("privkey", null) ?: return null
        val privkey = Base64.decode(privkeyB64, Base64.NO_WRAP)
        val hex = AddressUtils.toHex(privkey)
        privkey.fill(0)
        return hex
    }

    /**
     * Export a full backup string: Base64(privkey) + ":" + Base64(pubkey).
     * This can be used to restore the wallet on another device.
     */
    fun exportBackup(): String? {
        val privB64 = prefs.getString("privkey", null) ?: return null
        val pubB64 = prefs.getString("pubkey", null) ?: return null
        return "$privB64:$pubB64"
    }

    /**
     * Import a wallet from a backup string (privB64:pubB64).
     * Validates key sizes and that signing/verification works.
     */
    fun importWallet(backup: String): String {
        val parts = backup.trim().split(":")
        require(parts.size == 2) { "Ungültiges Backup-Format" }

        val privkey = Base64.decode(parts[0], Base64.NO_WRAP)
        val pubkey = Base64.decode(parts[1], Base64.NO_WRAP)

        val expectedSk = dilithium.getSecretKeyBytes()
        val expectedPk = dilithium.getPublicKeyBytes()
        require(privkey.size == expectedSk) {
            "Ungültige Private-Key-Länge: ${privkey.size} (erwartet $expectedSk)"
        }
        require(pubkey.size == expectedPk) {
            "Ungültige Public-Key-Länge: ${pubkey.size} (erwartet $expectedPk)"
        }

        // Verify the keypair works: sign a test message and verify
        val testMsg = "qbitx-import-test".toByteArray()
        val sig = dilithium.sign(testMsg, privkey)
            ?: throw RuntimeException("Schlüssel ungültig: Signatur fehlgeschlagen")
        val valid = dilithium.verify(sig, testMsg, pubkey)
        require(valid) { "Schlüssel ungültig: Verifizierung fehlgeschlagen" }

        prefs.edit()
            .putString("pubkey", parts[1])
            .putString("privkey", parts[0])
            .apply()

        privkey.fill(0)
        return AddressUtils.pubkeyToAddress(pubkey)
    }

    /** Delete the wallet (irreversible!). */
    fun deleteWallet() {
        prefs.edit().clear().apply()
    }
}
