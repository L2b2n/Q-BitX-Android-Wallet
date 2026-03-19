package org.qbitx.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    val nodeHost: String = "192.168.1.100",
    val nodePort: Int = 8332,
    val nodeUser: String = "qbitx",
    val nodePassword: String = "qbitx",
    val walletName: String = ""
)

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val keyManager = KeyManager(application)
    val rpcClient = NodeRpcClient()

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    init {
        checkWallet()
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

    fun connectToNode(host: String, port: Int, user: String, password: String, wallet: String = "") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true, error = null,
                nodeHost = host, nodePort = port,
                nodeUser = user, nodePassword = password,
                walletName = wallet
            )
            rpcClient.configure(host, port, user, password, wallet)
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
                val walletInfo = rpcClient.getWalletInfo()
                _uiState.value = _uiState.value.copy(
                    balance = walletInfo.balance,
                    unconfirmedBalance = walletInfo.unconfirmedBalance,
                    blockHeight = _uiState.value.blockHeight
                )
            } catch (_: Exception) {
                // Silently fail — balance unavailable
            }
        }
    }

    fun sendQBX(toAddress: String, amount: Double, feePolicy: String = "normal") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, lastTxId = null)
            try {
                val result = rpcClient.pqSendToAddress(toAddress, amount, feePolicy)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    lastTxId = result.txid
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
                    error = "Send fehlgeschlagen: ${e.message}"
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
}
