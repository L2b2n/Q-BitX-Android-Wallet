package org.qbitx.wallet.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.qbitx.wallet.crypto.AddressUtils
import org.qbitx.wallet.crypto.DilithiumNative
import java.security.MessageDigest

data class WalletInfo(val id: Int, val name: String, val address: String)

data class TxRecord(
    val txid: String,
    val toAddress: String,
    val amount: Double,
    val fee: String,
    val timestamp: Long,
    val walletId: Int
)

class KeyManager(context: Context) {

    private val dilithium = DilithiumNative()
    private val gson = Gson()

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

    init { migrate() }

    /** Migrate v1.0.x single-wallet → v1.1.0 multi-wallet format */
    private fun migrate() {
        val oldPub = prefs.getString("pubkey", null)
        if (oldPub != null && !prefs.contains("wallet_ids")) {
            val oldPriv = prefs.getString("privkey", null) ?: return
            prefs.edit()
                .putString("w0_pub", oldPub)
                .putString("w0_priv", oldPriv)
                .putString("w0_name", "Haupt-Wallet")
                .putString("wallet_ids", "0")
                .putInt("next_id", 1)
                .putInt("active_id", 0)
                .remove("pubkey")
                .remove("privkey")
                .apply()
        }
    }

    // ---- Multi-Wallet ----

    private fun walletIds(): List<Int> {
        val s = prefs.getString("wallet_ids", "") ?: ""
        return if (s.isEmpty()) emptyList() else s.split(",").mapNotNull { it.toIntOrNull() }
    }

    fun listWallets(): List<WalletInfo> = walletIds().mapNotNull { id ->
        val b64 = prefs.getString("w${id}_pub", null) ?: return@mapNotNull null
        val pub = Base64.decode(b64, Base64.NO_WRAP)
        val name = prefs.getString("w${id}_name", "Wallet $id") ?: "Wallet $id"
        WalletInfo(id, name, AddressUtils.pubkeyToAddress(pub))
    }

    fun getActiveWalletId(): Int = prefs.getInt("active_id", -1)

    fun setActiveWallet(id: Int) {
        prefs.edit().putInt("active_id", id).apply()
    }

    fun hasWallet(): Boolean = walletIds().isNotEmpty()

    fun createWallet(name: String = ""): WalletInfo {
        val id = prefs.getInt("next_id", 0)
        val keys = dilithium.generateKeypair()
            ?: throw RuntimeException("Dilithium key generation failed")

        val pubkey = keys[0]
        val privkey = keys[1]
        val walletName = name.ifEmpty { "Wallet ${listWallets().size + 1}" }
        val address = AddressUtils.pubkeyToAddress(pubkey)
        val ids = walletIds() + id

        prefs.edit()
            .putString("w${id}_pub", Base64.encodeToString(pubkey, Base64.NO_WRAP))
            .putString("w${id}_priv", Base64.encodeToString(privkey, Base64.NO_WRAP))
            .putString("w${id}_name", walletName)
            .putString("wallet_ids", ids.joinToString(","))
            .putInt("next_id", id + 1)
            .putInt("active_id", id)
            .apply()

        return WalletInfo(id, walletName, address)
    }

    fun renameWallet(id: Int, newName: String) {
        prefs.edit().putString("w${id}_name", newName).apply()
    }

    fun getAddress(): String? {
        val id = getActiveWalletId(); if (id < 0) return null
        val b64 = prefs.getString("w${id}_pub", null) ?: return null
        return AddressUtils.pubkeyToAddress(Base64.decode(b64, Base64.NO_WRAP))
    }

    fun getPublicKey(): ByteArray? {
        val id = getActiveWalletId(); if (id < 0) return null
        val b64 = prefs.getString("w${id}_pub", null) ?: return null
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    fun sign(message: ByteArray): ByteArray? {
        val id = getActiveWalletId(); if (id < 0) return null
        val b64 = prefs.getString("w${id}_priv", null) ?: return null
        val priv = Base64.decode(b64, Base64.NO_WRAP)
        val sig = dilithium.sign(message, priv)
        priv.fill(0)
        return sig
    }

    fun verify(signature: ByteArray, message: ByteArray): Boolean {
        val pub = getPublicKey() ?: return false
        return dilithium.verify(signature, message, pub)
    }

    fun exportPublicKeyHex(): String? {
        val pub = getPublicKey() ?: return null
        return AddressUtils.toHex(pub)
    }

    fun exportPrivateKeyHex(): String? {
        val id = getActiveWalletId(); if (id < 0) return null
        val b64 = prefs.getString("w${id}_priv", null) ?: return null
        val priv = Base64.decode(b64, Base64.NO_WRAP)
        val hex = AddressUtils.toHex(priv)
        priv.fill(0)
        return hex
    }

    fun exportBackup(): String? {
        val id = getActiveWalletId(); if (id < 0) return null
        val privB64 = prefs.getString("w${id}_priv", null) ?: return null
        val pubB64 = prefs.getString("w${id}_pub", null) ?: return null
        return "$privB64:$pubB64"
    }

    fun importWallet(backup: String, name: String = ""): WalletInfo {
        val parts = backup.trim().split(":")
        require(parts.size == 2) { "Ungültiges Backup-Format" }

        val privkey = Base64.decode(parts[0], Base64.NO_WRAP)
        val pubkey = Base64.decode(parts[1], Base64.NO_WRAP)

        require(privkey.size == dilithium.getSecretKeyBytes()) {
            "Ungültige Private-Key-Länge: ${privkey.size}"
        }
        require(pubkey.size == dilithium.getPublicKeyBytes()) {
            "Ungültige Public-Key-Länge: ${pubkey.size}"
        }

        val testMsg = "qbitx-import-test".toByteArray()
        val sig = dilithium.sign(testMsg, privkey)
            ?: throw RuntimeException("Schlüssel ungültig: Signatur fehlgeschlagen")
        require(dilithium.verify(sig, testMsg, pubkey)) {
            "Schlüssel ungültig: Verifizierung fehlgeschlagen"
        }

        val id = prefs.getInt("next_id", 0)
        val walletName = name.ifEmpty { "Importiert ${listWallets().size + 1}" }
        val address = AddressUtils.pubkeyToAddress(pubkey)
        val ids = walletIds() + id

        prefs.edit()
            .putString("w${id}_pub", parts[1])
            .putString("w${id}_priv", parts[0])
            .putString("w${id}_name", walletName)
            .putString("wallet_ids", ids.joinToString(","))
            .putInt("next_id", id + 1)
            .putInt("active_id", id)
            .apply()

        privkey.fill(0)
        return WalletInfo(id, walletName, address)
    }

    fun deleteWallet(id: Int) {
        val ids = walletIds().filter { it != id }
        val editor = prefs.edit()
            .remove("w${id}_pub")
            .remove("w${id}_priv")
            .remove("w${id}_name")
            .putString("wallet_ids", if (ids.isEmpty()) "" else ids.joinToString(","))
        if (getActiveWalletId() == id) {
            editor.putInt("active_id", ids.firstOrNull() ?: -1)
        }
        editor.apply()
    }

    fun deleteAllWallets() {
        val pinHash = prefs.getString("pin_hash", null)
        val txHistory = prefs.getString("tx_history", null)
        prefs.edit().clear().apply()
        val editor = prefs.edit()
        if (pinHash != null) editor.putString("pin_hash", pinHash)
        if (txHistory != null) editor.putString("tx_history", txHistory)
        editor.apply()
    }

    // ---- PIN ----

    fun hasPin(): Boolean = prefs.contains("pin_hash")

    fun setPin(pin: String) {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("qbitx-pin:$pin".toByteArray())
        prefs.edit().putString("pin_hash", AddressUtils.toHex(hash)).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString("pin_hash", null) ?: return false
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("qbitx-pin:$pin".toByteArray())
        return stored == AddressUtils.toHex(hash)
    }

    fun removePin() {
        prefs.edit().remove("pin_hash").apply()
    }

    // ---- TX History ----

    fun addTxRecord(txid: String, toAddress: String, amount: Double, fee: String) {
        val record = TxRecord(txid, toAddress, amount, fee, System.currentTimeMillis(), getActiveWalletId())
        val history = getAllTxHistory().toMutableList()
        history.add(0, record)
        prefs.edit().putString("tx_history", gson.toJson(history)).apply()
    }

    fun getAllTxHistory(): List<TxRecord> {
        val json = prefs.getString("tx_history", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TxRecord>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) { emptyList() }
    }

    fun getTxHistoryForActiveWallet(): List<TxRecord> {
        val id = getActiveWalletId()
        return getAllTxHistory().filter { it.walletId == id }
    }
}
