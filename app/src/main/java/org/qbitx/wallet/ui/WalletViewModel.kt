package org.qbitx.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.qbitx.wallet.crypto.TransactionBuilder
import org.qbitx.wallet.data.KeyManager
import org.qbitx.wallet.data.TxRecord
import org.qbitx.wallet.data.WalletInfo
import org.qbitx.wallet.network.NodeRpcClient
import org.qbitx.wallet.network.RpcException
import org.qbitx.wallet.network.TxDetail

data class WalletUiState(
    val hasWallet: Boolean = false,
    val address: String = "",
    val balance: Double = 0.0,
    val unconfirmedBalance: Double = 0.0,
    val immatureBalance: Double = 0.0,
    val immatureBlocks: Int = 0,
    val nodeConnected: Boolean = false,
    val chain: String = "",
    val blockHeight: Int = 0,
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastTxId: String? = null,
    val rpcUrl: String = "https://qbitx.solopool.site/",
    val wallets: List<WalletInfo> = emptyList(),
    val activeWalletName: String = "",
    val isLocked: Boolean = false,
    val hasPin: Boolean = false,
    val txHistory: List<TxRecord> = emptyList(),
    val qbxPriceUsdt: Double? = null,
    val isScanningHistory: Boolean = false,
    val scanProgressText: String? = null,
    val feeLow: Double = 1.0,
    val feeNormal: Double = 5.0,
    val feeHigh: Double = 15.0
)

class WalletViewModel(application: Application) : AndroidViewModel(application) {

    private val keyManager = KeyManager(application)
    val rpcClient = NodeRpcClient(rpcUrl = "https://qbitx.solopool.site/")
    private var isRefreshing = false

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    init {
        val hasPIN = keyManager.hasPin()
        _uiState.value = _uiState.value.copy(isLocked = hasPIN, hasPin = hasPIN)
        val savedUrl = keyManager.getSavedRpcUrl()
        if (!savedUrl.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(rpcUrl = savedUrl)
            rpcClient.configure(savedUrl)
        }
        checkWallet()
        autoConnect()
    }

    private fun autoConnect() {
        viewModelScope.launch {
            try {
                val info = rpcClient.getBlockchainInfo()
                _uiState.value = _uiState.value.copy(
                    nodeConnected = true, chain = info.chain, blockHeight = info.blocks
                )
                refreshFeeRates()
                refreshBalance()
            } catch (_: Exception) {}
        }
    }

    private fun checkWallet() {
        val has = keyManager.hasWallet()
        val address = keyManager.getAddress() ?: ""
        val wallets = keyManager.listWallets()
        val activeId = keyManager.getActiveWalletId()
        val activeName = wallets.find { it.id == activeId }?.name ?: ""
        val txHistory = keyManager.getTxHistoryForActiveWallet()
        _uiState.value = _uiState.value.copy(
            hasWallet = has, address = address,
            wallets = wallets, activeWalletName = activeName,
            txHistory = txHistory
        )
    }

    fun createWallet(name: String = "") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                keyManager.createWallet(name)
                checkWallet()
                _uiState.value = _uiState.value.copy(isLoading = false)
                refreshBalance()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Wallet-Erstellung fehlgeschlagen: ${e.message}"
                )
            }
        }
    }

    fun switchWallet(id: Int) {
        keyManager.setActiveWallet(id)
        checkWallet()
        _uiState.value = _uiState.value.copy(balance = 0.0, unconfirmedBalance = 0.0)
        viewModelScope.launch { refreshBalance() }
    }

    fun renameWallet(newName: String) {
        val id = keyManager.getActiveWalletId()
        if (id >= 0) {
            keyManager.renameWallet(id, newName)
            checkWallet()
        }
    }

    fun deleteActiveWallet() {
        val id = keyManager.getActiveWalletId()
        if (id >= 0) {
            keyManager.deleteWallet(id)
            checkWallet()
            _uiState.value = _uiState.value.copy(balance = 0.0, unconfirmedBalance = 0.0)
            if (_uiState.value.hasWallet) {
                viewModelScope.launch { refreshBalance() }
            }
        }
    }

    fun connectToNode(rpcUrl: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, rpcUrl = rpcUrl)
            keyManager.saveRpcUrl(rpcUrl)
            rpcClient.configure(rpcUrl)
            try {
                val info = rpcClient.getBlockchainInfo()
                _uiState.value = _uiState.value.copy(
                    nodeConnected = true, chain = info.chain,
                    blockHeight = info.blocks, isLoading = false
                )
                refreshFeeRates()
                refreshBalance()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    nodeConnected = false, isLoading = false,
                    error = "Node-Verbindung fehlgeschlagen: ${e.message}"
                )
            }
        }
    }

    fun refreshBalance() {
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            try {
                val address = _uiState.value.address

                // Launch price fetch and blockchain info concurrently
                val priceDeferred = async { rpcClient.fetchQbxPriceUsdt() }
                val infoDeferred = async { rpcClient.getBlockchainInfo() }

                if (address.isNotEmpty()) {
                    val scanResult = rpcClient.scanTxOutSet(address)
                    val spendable = scanResult.totalAmount - scanResult.immatureAmount
                    _uiState.value = _uiState.value.copy(
                        balance = spendable,
                        unconfirmedBalance = 0.0,
                        immatureBalance = scanResult.immatureAmount,
                        immatureBlocks = scanResult.immatureBlocks
                    )
                    scanTxHistory(address, scanResult.unspents.map { it.txid }.distinct())
                }

                val info = infoDeferred.await()
                _uiState.value = _uiState.value.copy(blockHeight = info.blocks)

                val price = priceDeferred.await()
                if (price != null) {
                    _uiState.value = _uiState.value.copy(qbxPriceUsdt = price)
                }
            } catch (_: Exception) {} finally {
                isRefreshing = false
            }
        }
    }

    /**
     * Fetch TX details and build TxRecords.
     * Handles both already-confirmed (cached) and fresh fetches.
     */
    private suspend fun fetchTxRecords(
        txids: List<String>,
        localByTxid: Map<String, TxRecord>,
        myAddress: String,
        walletId: Int
    ): List<TxRecord> {
        val CONFIRMED_THRESHOLD = 6
        val alreadyConfirmed = mutableListOf<TxRecord>()
        val txidsToFetch = mutableListOf<String>()

        for (txid in txids) {
            val local = localByTxid[txid]
            if (local != null && local.confirmations >= CONFIRMED_THRESHOLD) {
                alreadyConfirmed.add(local)
            } else {
                txidsToFetch.add(txid)
            }
        }

        if (txidsToFetch.isEmpty()) return alreadyConfirmed

        val semaphore = Semaphore(8)
        val fetchedDetails: List<Pair<String, TxDetail?>> = coroutineScope {
            txidsToFetch.map { txid ->
                async {
                    semaphore.acquire()
                    try {
                        txid to rpcClient.getTransactionDetails(txid)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }

        val records = mutableListOf<TxRecord>()
        records.addAll(alreadyConfirmed)

        for ((txid, detail) in fetchedDetails) {
            val local = localByTxid[txid]

            if (detail == null) {
                if (local != null) records.add(local)
                continue
            }

            var receivedAmount = 0.0
            var sentToOther = 0.0
            var otherAddress = ""

            for (vo in detail.voutList) {
                if (vo.addresses.contains(myAddress)) {
                    receivedAmount += vo.value
                } else {
                    sentToOther += vo.value
                    if (otherAddress.isEmpty() && vo.addresses.isNotEmpty()) {
                        otherAddress = vo.addresses.first()
                    }
                }
            }

            val timestamp = if (detail.time > 0) detail.time * 1000 else
                local?.timestamp ?: System.currentTimeMillis()

            val isSender = if (local != null) {
                local.direction == "out"
            } else {
                rpcClient.isAddressSpender(detail, myAddress)
            }

            if (isSender) {
                if (local != null) {
                    records.add(local.copy(confirmations = detail.confirmations))
                } else {
                    records.add(
                        TxRecord(
                            txid = txid,
                            toAddress = otherAddress,
                            amount = sentToOther,
                            fee = "",
                            timestamp = timestamp,
                            walletId = walletId,
                            direction = "out",
                            confirmations = detail.confirmations
                        )
                    )
                }
            } else {
                if (receivedAmount > 0) {
                    records.add(
                        TxRecord(
                            txid = txid,
                            toAddress = myAddress,
                            amount = receivedAmount,
                            fee = "",
                            timestamp = timestamp,
                            walletId = walletId,
                            direction = "in",
                            confirmations = detail.confirmations
                        )
                    )
                }
            }
        }

        return records
    }

    /**
     * Scan blockchain for TX history using UTXO txids.
     * Phase 1: Immediately show TXs from UTXOs + local history.
     * Phase 2: Scan blockchain for additional TXs (incremental, may take minutes).
     */
    private suspend fun scanTxHistory(myAddress: String, utxoTxids: List<String>) {
        val walletId = keyManager.getActiveWalletId()
        val localHistory = keyManager.getTxHistoryForActiveWallet()
        val localByTxid = localHistory.associateBy { it.txid }

        // Phase 1: Immediately fetch and show UTXO-based + local TXs
        val baseTxids = (utxoTxids + localHistory.map { it.txid }).distinct()
        if (baseTxids.isNotEmpty()) {
            try {
                val baseRecords = fetchTxRecords(baseTxids, localByTxid, myAddress, walletId)
                val sorted = baseRecords.sortedByDescending { it.timestamp }
                keyManager.replaceTxHistoryForWallet(sorted, walletId)
                _uiState.value = _uiState.value.copy(txHistory = sorted)
            } catch (_: Exception) {}
        }

        // Phase 2: Scan blockchain for additional TXs (incremental)
        try {
            val blockHeight = _uiState.value.blockHeight
            var lastScanned = keyManager.getLastScannedHeight(walletId)

            // Reset scan marker if history was cleared (e.g. after re-import)
            if (localHistory.isEmpty() && lastScanned > 0) {
                lastScanned = 0
            }

            if (blockHeight > lastScanned) {
                val totalBlocks = blockHeight - lastScanned
                if (totalBlocks > 100) {
                    _uiState.value = _uiState.value.copy(
                        isScanningHistory = true,
                        scanProgressText = "Scanning blockchain..."
                    )
                }
                val (txids, highestScanned) = rpcClient.discoverAllTxIds(
                    myAddress,
                    blockHeight = blockHeight,
                    lastScannedHeight = lastScanned,
                    onProgress = { scanned, total ->
                        val pct = if (total > 0) scanned * 100 / total else 0
                        _uiState.value = _uiState.value.copy(
                            scanProgressText = "Scanning blocks... $pct%"
                        )
                    }
                )
                _uiState.value = _uiState.value.copy(
                    isScanningHistory = false,
                    scanProgressText = null
                )
                if (highestScanned > lastScanned) {
                    keyManager.setLastScannedHeight(walletId, highestScanned)
                }

                // Fetch details for newly discovered TXs
                val newTxids = txids.filter { it !in baseTxids }
                if (newTxids.isNotEmpty()) {
                    val currentHistory = keyManager.getTxHistoryForActiveWallet()
                    val currentByTxid = currentHistory.associateBy { it.txid }
                    val extraTxids = newTxids.filter { it !in currentByTxid }
                    if (extraTxids.isNotEmpty()) {
                        val newRecords = fetchTxRecords(extraTxids, currentByTxid, myAddress, walletId)
                        val combined = (currentHistory + newRecords).sortedByDescending { it.timestamp }
                        keyManager.replaceTxHistoryForWallet(combined, walletId)
                        _uiState.value = _uiState.value.copy(txHistory = combined)
                    }
                }
            }
        } catch (_: Exception) {
            _uiState.value = _uiState.value.copy(
                isScanningHistory = false,
                scanProgressText = null
            )
        }
    }

    private fun refreshFeeRates() {
        viewModelScope.launch {
            try {
                val (low, normal, high) = rpcClient.fetchFeeRates()
                _uiState.value = _uiState.value.copy(
                    feeLow = low, feeNormal = normal, feeHigh = high
                )
            } catch (_: Exception) { /* keep defaults */ }
        }
    }

    fun sendQBX(toAddress: String, amount: Double, feePolicy: String = "normal") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null, lastTxId = null)
            try {
                // Validate address via node
                if (!rpcClient.validateAddress(toAddress)) {
                    throw Exception("Ungültige Empfängeradresse")
                }

                val myAddress = _uiState.value.address
                val publicKey = keyManager.getPublicKey()
                    ?: throw Exception("Kein Wallet vorhanden")

                val scanResult = rpcClient.scanTxOutSet(myAddress)
                val spendable = scanResult.unspents.filter { !(it.isCoinbase && it.confirmations < 100) }
                if (spendable.isEmpty()) {
                    if (scanResult.unspents.isNotEmpty()) {
                        throw Exception("Alle UTXOs sind unreife Coinbase-Belohnungen (noch ${scanResult.immatureBlocks} Bl\u00f6cke)")
                    }
                    throw Exception("Keine verf\u00fcgbaren UTXOs")
                }

                val state = _uiState.value
                val feeRate = when (feePolicy) {
                    "low" -> state.feeLow.toLong().coerceAtLeast(1L)
                    "high" -> state.feeHigh.toLong().coerceAtLeast(1L)
                    else -> state.feeNormal.toLong().coerceAtLeast(1L)
                }
                val amountSat = Math.round(amount * 1e8)

                val selected = mutableListOf<org.qbitx.wallet.network.Utxo>()
                var totalInputSat = 0L

                for (utxo in spendable.sortedByDescending { it.amount }) {
                    selected.add(utxo)
                    totalInputSat += Math.round(utxo.amount * 1e8)
                    val fee = TransactionBuilder.estimateFee(selected.size, 2, feeRate)
                    if (totalInputSat >= amountSat + fee) break
                }

                val fee2out = TransactionBuilder.estimateFee(selected.size, 2, feeRate)
                val fee1out = TransactionBuilder.estimateFee(selected.size, 1, feeRate)
                val changeSat = totalInputSat - amountSat - fee2out

                val actualChange: Long
                if (changeSat >= 546) {
                    actualChange = changeSat
                } else if (totalInputSat >= amountSat + fee1out) {
                    actualChange = 0
                } else {
                    val needed = (amountSat + fee1out) / 1e8
                    throw Exception("Nicht genug Guthaben (benötigt: ${"%.8f".format(needed)} QBX)")
                }

                val recipientAmountQbx = amountSat / 1e8
                val changeAmountQbx = if (actualChange > 0) actualChange / 1e8 else null

                val unsignedHex = rpcClient.createRawTransaction(
                    inputs = selected,
                    recipientAddress = toAddress,
                    recipientAmount = recipientAmountQbx,
                    changeAddress = if (actualChange > 0) myAddress else null,
                    changeAmount = changeAmountQbx
                )

                val scriptPubKeys = mutableMapOf<Int, String>()
                selected.forEachIndexed { i, utxo -> scriptPubKeys[i] = utxo.scriptPubKey }

                val signedHex = TransactionBuilder.signTransaction(
                    unsignedTxHex = unsignedHex,
                    utxoScriptPubKeys = scriptPubKeys,
                    signFn = { hash -> keyManager.sign(hash) },
                    publicKey = publicKey
                )

                val txid = rpcClient.sendRawTransaction(signedHex)

                // Calculate actual fee paid
                val actualFeeSat = if (actualChange > 0) totalInputSat - amountSat - actualChange else totalInputSat - amountSat
                val feeQbx = actualFeeSat / 1e8

                keyManager.addTxRecord(txid, toAddress, amount, feePolicy)

                // Immediately deduct sent amount + fee from displayed balance
                val currentBalance = _uiState.value.balance
                val deducted = amount + feeQbx
                // Preserve current UI history and prepend new TX (avoid SharedPrefs race)
                val newRecord = TxRecord(
                    txid = txid, toAddress = toAddress, amount = amount,
                    fee = feePolicy, timestamp = System.currentTimeMillis(),
                    walletId = keyManager.getActiveWalletId(),
                    direction = "out", confirmations = 0
                )
                val updatedHistory = listOf(newRecord) + _uiState.value.txHistory
                _uiState.value = _uiState.value.copy(
                    isLoading = false, lastTxId = txid,
                    balance = currentBalance - deducted,
                    unconfirmedBalance = -deducted,
                    txHistory = updatedHistory
                )
            } catch (e: RpcException) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false, error = "Senden fehlgeschlagen: ${e.message}"
                )
            }
        }
    }

    fun clearError() { _uiState.value = _uiState.value.copy(error = null) }
    fun clearLastTx() { _uiState.value = _uiState.value.copy(lastTxId = null) }
    fun getPublicKeyHex(): String = keyManager.exportPublicKeyHex() ?: ""
    fun exportBackup(): String = keyManager.exportBackup() ?: ""

    fun importWallet(backup: String, name: String = "") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                keyManager.importWallet(backup, name)
                checkWallet()
                _uiState.value = _uiState.value.copy(isLoading = false)
                refreshBalance()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Import fehlgeschlagen: ${e.message}"
                )
            }
        }
    }

    fun deleteWallet() {
        keyManager.deleteAllWallets()
        _uiState.value = WalletUiState(hasPin = keyManager.hasPin())
    }

    // ---- PIN ----

    fun verifyPin(pin: String): Boolean = keyManager.verifyPin(pin)

    fun unlock() {
        _uiState.value = _uiState.value.copy(isLocked = false)
    }

    fun setPin(pin: String) {
        keyManager.setPin(pin)
        _uiState.value = _uiState.value.copy(hasPin = true)
    }

    fun removePin() {
        keyManager.removePin()
        _uiState.value = _uiState.value.copy(hasPin = false)
    }
}
