package org.qbitx.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
    private var refreshJob: Job? = null

    private val _uiState = MutableStateFlow(WalletUiState())
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    init {
        val hasPIN = keyManager.hasPin()
        _uiState.value = _uiState.value.copy(isLocked = hasPIN, hasPin = hasPIN)
        val savedUrl = keyManager.getSavedRpcUrl()
        if (!savedUrl.isNullOrEmpty()) {
            try {
                val normalizedUrl = NodeRpcClient.normalizeRpcUrl(savedUrl)
                _uiState.value = _uiState.value.copy(rpcUrl = normalizedUrl)
                rpcClient.configure(normalizedUrl)
            } catch (_: Exception) {
                keyManager.saveRpcUrl("https://qbitx.solopool.site/")
            }
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
        val pendingDelta = pendingOutDelta(txHistory)
        _uiState.value = _uiState.value.copy(
            hasWallet = has, address = address,
            wallets = wallets, activeWalletName = activeName,
            txHistory = txHistory,
            unconfirmedBalance = pendingDelta
        )
    }

    /** Sum all pending outgoing TXs (negative delta in QBX). */
    private fun pendingOutDelta(history: List<TxRecord>): Double = history
        .filter { it.confirmations == 0 && it.direction == "out" }
        .sumOf { -(it.amount + parseFeeQbx(it.fee)) }

    /** Parse a stored fee string like "0.00053500 QBX" back to a Double in QBX. */
    private fun parseFeeQbx(fee: String): Double {
        if (fee.isBlank()) return 0.0
        val number = fee.trim().split(' ').firstOrNull()?.replace(',', '.') ?: return 0.0
        return number.toDoubleOrNull() ?: 0.0
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
        // Cancel any ongoing refresh to prevent stale data overwriting new wallet
        refreshJob?.cancel()
        refreshJob = null
        isRefreshing = false
        keyManager.setActiveWallet(id)
        checkWallet()
        _uiState.value = _uiState.value.copy(
            balance = 0.0, unconfirmedBalance = 0.0,
            immatureBalance = 0.0, immatureBlocks = 0,
            isScanningHistory = false, scanProgressText = null
        )
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
            refreshJob?.cancel()
            refreshJob = null
            isRefreshing = false
            keyManager.deleteWallet(id)
            checkWallet()
            _uiState.value = _uiState.value.copy(
                balance = 0.0, unconfirmedBalance = 0.0,
                immatureBalance = 0.0, immatureBlocks = 0,
                isScanningHistory = false, scanProgressText = null
            )
            if (_uiState.value.hasWallet) {
                viewModelScope.launch { refreshBalance() }
            }
        }
    }

    fun connectToNode(rpcUrl: String) {
        viewModelScope.launch {
            try {
                val normalizedUrl = NodeRpcClient.normalizeRpcUrl(rpcUrl)
                _uiState.value = _uiState.value.copy(isLoading = true, error = null, rpcUrl = normalizedUrl)
                rpcClient.configure(normalizedUrl)
                val info = rpcClient.getBlockchainInfo()
                keyManager.saveRpcUrl(normalizedUrl)
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
        refreshJob = viewModelScope.launch {
            isRefreshing = true
            try {
                val address = _uiState.value.address

                // Launch price fetch and blockchain info concurrently
                val priceDeferred = async { rpcClient.fetchQbxPriceUsdt() }
                val infoDeferred = async { rpcClient.getBlockchainInfo() }

                if (address.isNotEmpty()) {
                    val scanResult = rpcClient.scanTxOutSet(address)
                    val spendable = scanResult.totalAmount - scanResult.immatureAmount
                    val pendingDelta = pendingOutDelta(_uiState.value.txHistory)
                    _uiState.value = _uiState.value.copy(
                        balance = spendable,
                        unconfirmedBalance = pendingDelta,
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
                // Re-read storage to preserve TXs added during processing (e.g. from sendQBX)
                val latestLocal = keyManager.getTxHistoryForActiveWallet()
                val resultByTxid = baseRecords.associateBy { it.txid }
                val merged = baseRecords.toMutableList()
                for (tx in latestLocal) {
                    if (tx.txid !in resultByTxid) {
                        merged.add(tx)
                    }
                }
                val sorted = merged.sortedByDescending { it.timestamp }
                // Only write if still on the same wallet (guard against cancelled/stale jobs)
                if (keyManager.getActiveWalletId() == walletId) {
                    keyManager.replaceTxHistoryForWallet(sorted, walletId)
                    _uiState.value = _uiState.value.copy(txHistory = sorted)
                }
            } catch (_: Exception) {}
        }

        // Phase 2: Scan blockchain for additional TXs (incremental)
        try {
            val blockHeight = _uiState.value.blockHeight
            var lastScanned = keyManager.getLastScannedHeight(walletId)

            // Detect TX data loss and force full rescan when needed
            val lastKnownCount = keyManager.getLastScanTxCount(walletId)
            if (lastScanned > 0 && (localHistory.isEmpty() || lastKnownCount < 0 || localHistory.size < lastKnownCount)) {
                lastScanned = 0
                keyManager.setLastScannedHeight(walletId, 0)
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
                if (highestScanned > lastScanned && keyManager.getActiveWalletId() == walletId) {
                    keyManager.setLastScannedHeight(walletId, highestScanned)
                }

                // Fetch details for newly discovered TXs
                val newTxids = txids.filter { it !in baseTxids }
                if (newTxids.isNotEmpty() && keyManager.getActiveWalletId() == walletId) {
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

                // Save TX count after successful scan for data-loss detection
                if (keyManager.getActiveWalletId() == walletId) {
                    keyManager.setLastScanTxCount(walletId, keyManager.getTxHistoryForActiveWallet().size)
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
                val endpointInfo = try {
                    rpcClient.getBlockchainInfo()
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(nodeConnected = false)
                    throw Exception("Keine gültige Node-Verbindung. RPC-URL prüfen und neu verbinden.")
                }

                _uiState.value = _uiState.value.copy(
                    nodeConnected = true,
                    chain = endpointInfo.chain,
                    blockHeight = endpointInfo.blocks
                )

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

                // Hard cap on inputs per TX to stay below the typical nginx
                // client_max_body_size (1 MB) of public RPC proxies.
                // Each PQ input is ~5 KB raw and ~10 KB hex-in-JSON, so 80 inputs
                // give a safe payload well below 1 MB.
                val MAX_INPUTS_PER_TX = 80
                val DUST = 546L

                data class PlannedBatch(
                    val inputs: List<org.qbitx.wallet.network.Utxo>,
                    val sendAmtSat: Long,
                    val changeSat: Long,
                    val actualFeeSat: Long
                )

                data class PreparedBatch(
                    val signedHex: String,
                    val sendAmtSat: Long,
                    val actualFeeSat: Long
                )

                // Preflight the full split plan before sending anything so a failed
                // feasibility check cannot leave the user with partial broadcasts.
                val planPool = spendable.sortedByDescending { it.amount }.toMutableList()
                val plannedBatches = mutableListOf<PlannedBatch>()
                var remainingToSend = amountSat

                while (remainingToSend > 0) {
                    if (planPool.isEmpty()) {
                        throw Exception(
                            "Nicht genug Guthaben (benötigt: ${"%.8f".format(remainingToSend / 1e8)} QBX plus Gebühren)."
                        )
                    }

                    val batch = mutableListOf<org.qbitx.wallet.network.Utxo>()
                    var batchInSat = 0L
                    while (batch.size < MAX_INPUTS_PER_TX && planPool.isNotEmpty()) {
                        val utxo = planPool.removeAt(0)
                        batch.add(utxo)
                        batchInSat += Math.round(utxo.amount * 1e8)
                        val feeWithChange = TransactionBuilder.estimateFee(batch.size, 2, feeRate)
                        if (batchInSat >= remainingToSend + feeWithChange) break
                    }

                    val fee2 = TransactionBuilder.estimateFee(batch.size, 2, feeRate)
                    val fee1 = TransactionBuilder.estimateFee(batch.size, 1, feeRate)

                    val planned = if (batchInSat >= remainingToSend + fee2) {
                        val rawChange = batchInSat - remainingToSend - fee2
                        if (rawChange >= DUST) {
                            PlannedBatch(
                                inputs = batch,
                                sendAmtSat = remainingToSend,
                                changeSat = rawChange,
                                actualFeeSat = fee2
                            )
                        } else {
                            PlannedBatch(
                                inputs = batch,
                                sendAmtSat = remainingToSend,
                                changeSat = 0L,
                                actualFeeSat = batchInSat - remainingToSend
                            )
                        }
                    } else {
                        if (planPool.isEmpty()) {
                            throw Exception(
                                "Nicht genug Guthaben (benötigt: ${"%.8f".format(remainingToSend / 1e8)} QBX plus Gebühren)."
                            )
                        }
                        if (batchInSat <= fee1) {
                            throw Exception(
                                "Saldo besteht aus zu vielen winzigen UTXOs — bitte höhere Gebühr wählen oder kleinere Beträge senden."
                            )
                        }
                        PlannedBatch(
                            inputs = batch,
                            sendAmtSat = batchInSat - fee1,
                            changeSat = 0L,
                            actualFeeSat = fee1
                        )
                    }

                    plannedBatches.add(planned)
                    remainingToSend -= planned.sendAmtSat
                }

                val preparedBatches = plannedBatches.map { batch ->
                    val recipientAmountQbx = batch.sendAmtSat / 1e8
                    val changeAmountQbx = if (batch.changeSat > 0) batch.changeSat / 1e8 else null

                    val unsignedHex = rpcClient.createRawTransaction(
                        inputs = batch.inputs,
                        recipientAddress = toAddress,
                        recipientAmount = recipientAmountQbx,
                        changeAddress = if (batch.changeSat > 0) myAddress else null,
                        changeAmount = changeAmountQbx
                    )

                    val scriptPubKeys = mutableMapOf<Int, String>()
                    batch.inputs.forEachIndexed { i, utxo -> scriptPubKeys[i] = utxo.scriptPubKey }

                    PreparedBatch(
                        signedHex = TransactionBuilder.signTransaction(
                            unsignedTxHex = unsignedHex,
                            utxoScriptPubKeys = scriptPubKeys,
                            signFn = { hash -> keyManager.sign(hash) },
                            publicKey = publicKey
                        ),
                        sendAmtSat = batch.sendAmtSat,
                        actualFeeSat = batch.actualFeeSat
                    )
                }

                val sentTxids = mutableListOf<String>()
                val pendingRecords = mutableListOf<TxRecord>()
                var totalActualFeeSat = 0L
                val sendStart = System.currentTimeMillis()
                val activeWalletId = keyManager.getActiveWalletId()

                preparedBatches.forEachIndexed { index, batch ->
                    val txid = rpcClient.sendRawTransaction(batch.signedHex)
                    sentTxids.add(txid)
                    totalActualFeeSat += batch.actualFeeSat

                    val partAmountQbx = batch.sendAmtSat / 1e8
                    val partFeeQbx = batch.actualFeeSat / 1e8
                    val partFeeStr = "%.8f QBX".format(partFeeQbx)

                    keyManager.addTxRecord(txid, toAddress, partAmountQbx, partFeeStr)

                    pendingRecords.add(
                        TxRecord(
                            txid = txid,
                            toAddress = toAddress,
                            amount = partAmountQbx,
                            fee = partFeeStr,
                            timestamp = sendStart + index,
                            walletId = activeWalletId,
                            direction = "out",
                            confirmations = 0
                        )
                    )
                }

                val feeQbx = totalActualFeeSat / 1e8

                val lastTxid = sentTxids.last()

                // Immediately deduct sent amount + fee from displayed balance.
                val currentBalance = _uiState.value.balance
                val deducted = amount + feeQbx
                val updatedHistory = pendingRecords.sortedByDescending { it.timestamp } +
                    _uiState.value.txHistory.filter { h -> sentTxids.none { it == h.txid } }
                _uiState.value = _uiState.value.copy(
                    isLoading = false, lastTxId = lastTxid,
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
