package org.qbitx.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.qbitx.wallet.crypto.TransactionBuilder
import org.qbitx.wallet.data.KeyManager
import org.qbitx.wallet.network.NodeRpcClient
import org.qbitx.wallet.network.RpcException

data class WalletUiState(
    val hasWallet: Boolean = false,
    val address: String = "",
    val balance: Double = 0.0,
    val unconfirmedBalance: Double = 0.0,
    val nodeConnected: Boolean = false,
    val chain: String = "",
    val blockHeight: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastTxId: String? = null,
    val rpcUrl: String = "https://qbitx.solopool.site/"
)

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val keyManager = KeyManager(application)
    val rpcClient = NodeRpcClient(
        rpcUrl = "https://qbitx.solopool.site/"
    )

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    init {
        checkWallet()
        autoConnect()
    }

    private fun autoConnect() {
        viewModelScope.launch {
            try {
                val info = rpcClient.getBlockchainInfo()
                _uiState.value = _uiState.value.copy(
                    nodeConnected = true,
                    chain = info.chain,
                    blockHeight = info.blocks
                )
                refreshBalance()
            } catch (_: Exception) {
                // Proxy nicht erreichbar — User kann manuell verbinden
            }
        }
    }

    private fun checkWallet() {
        val hasWallet = keyManager.hasWallet()
        val address = keyManager.getAddress() ?: ""
        _uiState.value = _uiState.value.copy(
            hasWallet = hasWallet,
            address = address
        )
    }

    fun createWallet() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val address = keyManager.createWallet()
                _uiState.value = _uiState.value.copy(
                    hasWallet = true,
                    address = address,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Wallet-Erstellung fehlgeschlagen: ${e.message}"
                )
            }
        }
    }

    fun connectToNode(rpcUrl: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true, error = null,
                rpcUrl = rpcUrl
            )
            rpcClient.configure(rpcUrl)
            try {
                val info = rpcClient.getBlockchainInfo()
                _uiState.value = _uiState.value.copy(
                    nodeConnected = true,
                    chain = info.chain,
                    blockHeight = info.blocks,
                    isLoading = false
                )
                refreshBalance()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    nodeConnected = false,
                    isLoading = false,
                    error = "Node-Verbindung fehlgeschlagen: ${e.message}"
                )
            }
        }
    }

    fun refreshBalance() {
        viewModelScope.launch {
            try {
                val address = _uiState.value.address
                if (address.isNotEmpty()) {
                    val scanResult = rpcClient.scanTxOutSet(address)
                    _uiState.value = _uiState.value.copy(
                        balance = scanResult.totalAmount,
                        unconfirmedBalance = 0.0
                    )
                }
                val info = rpcClient.getBlockchainInfo()
                _uiState.value = _uiState.value.copy(blockHeight = info.blocks)
            } catch (_: Exception) {
                // Silently fail — balance unavailable
            }
        }
    }

    /**
     * Send QBX using fully local Dilithium signing.
     * Flow: scantxoutset → createrawtransaction → local sign → sendrawtransaction
     * Private keys NEVER leave the device.
     */
    fun sendQBX(toAddress: String, amount: Double, feePolicy: String = "normal") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, lastTxId = null)
            try {
                val myAddress = _uiState.value.address
                val publicKey = keyManager.getPublicKey()
                    ?: throw Exception("Kein Wallet vorhanden")

                // 1. Get UTXOs for our address
                val scanResult = rpcClient.scanTxOutSet(myAddress)
                if (scanResult.unspents.isEmpty()) {
                    throw Exception("Keine verfügbaren UTXOs")
                }

                // 2. Select UTXOs and calculate fee
                val feeRate = TransactionBuilder.feeRateForPolicy(feePolicy)
                val amountSat = Math.round(amount * 1e8)

                val selected = mutableListOf<org.qbitx.wallet.network.Utxo>()
                var totalInputSat = 0L

                for (utxo in scanResult.unspents.sortedByDescending { it.amount }) {
                    selected.add(utxo)
                    totalInputSat += Math.round(utxo.amount * 1e8)
                    val fee = TransactionBuilder.estimateFee(selected.size, 2, feeRate)
                    if (totalInputSat >= amountSat + fee) break
                }

                // Check if we can afford it (try 2-output first, then 1-output)
                val fee2out = TransactionBuilder.estimateFee(selected.size, 2, feeRate)
                val fee1out = TransactionBuilder.estimateFee(selected.size, 1, feeRate)
                val changeSat = totalInputSat - amountSat - fee2out

                val actualChange: Long

                if (changeSat >= 546) {
                    // Change worth keeping
                    actualChange = changeSat
                } else if (totalInputSat >= amountSat + fee1out) {
                    // Change is dust — absorb into fee
                    actualChange = 0
                } else {
                    val needed = (amountSat + fee1out) / 1e8
                    throw Exception("Nicht genug Guthaben (benötigt: ${"%.8f".format(needed)} QBX)")
                }

                // 3. Create unsigned transaction via proxy
                val recipientAmountQbx = amountSat / 1e8
                val changeAmountQbx = if (actualChange > 0) actualChange / 1e8 else null

                val unsignedHex = rpcClient.createRawTransaction(
                    inputs = selected,
                    recipientAddress = toAddress,
                    recipientAmount = recipientAmountQbx,
                    changeAddress = if (actualChange > 0) myAddress else null,
                    changeAmount = changeAmountQbx
                )

                // 4. Sign LOCALLY with Dilithium — keys never leave the phone
                val scriptPubKeys = mutableMapOf<Int, String>()
                selected.forEachIndexed { i, utxo -> scriptPubKeys[i] = utxo.scriptPubKey }

                val signedHex = TransactionBuilder.signTransaction(
                    unsignedTxHex = unsignedHex,
                    utxoScriptPubKeys = scriptPubKeys,
                    signFn = { hash -> keyManager.sign(hash) },
                    publicKey = publicKey
                )

                // 5. Broadcast signed transaction
                val txid = rpcClient.sendRawTransaction(signedHex)

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    lastTxId = txid
                )
                refreshBalance()
            } catch (e: RpcException) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Senden fehlgeschlagen: ${e.message}"
                )
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun clearLastTx() {
        _uiState.value = _uiState.value.copy(lastTxId = null)
    }

    fun getPublicKeyHex(): String = keyManager.exportPublicKeyHex() ?: ""

    fun exportBackup(): String = keyManager.exportBackup() ?: ""

    fun importWallet(backup: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val address = keyManager.importWallet(backup)
                _uiState.value = _uiState.value.copy(
                    hasWallet = true,
                    address = address,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Import fehlgeschlagen: ${e.message}"
                )
            }
        }
    }

    fun deleteWallet() {
        keyManager.deleteWallet()
        _uiState.value = WalletUiState()
    }
}
