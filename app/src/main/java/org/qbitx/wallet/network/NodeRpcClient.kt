package org.qbitx.wallet.network

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Stateless JSON-RPC client for the Q-BitX public proxy.
 * Only uses blockchain queries + tx building + broadcasting.
 * No wallet operations — keys never leave the device.
 */
class NodeRpcClient(
    private var rpcUrl: String = "https://qbitx.solopool.site/"
) {
    private val gson = Gson()
    private val JSON = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var requestId = 0

    fun configure(rpcUrl: String) {
        this.rpcUrl = rpcUrl.trimEnd('/')
    }

    private fun buildUrl(): String = rpcUrl

    /** Execute a JSON-RPC call and return the result as JsonObject. */
    suspend fun call(method: String, vararg params: Any?): JsonObject = withContext(Dispatchers.IO) {
        val id = ++requestId
        val body = gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to method,
            "params" to params.toList()
        ))

        val requestBuilder = Request.Builder()
            .url(buildUrl())
            .post(body.toRequestBody(JSON))

        val request = requestBuilder.build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RpcException("Empty response from node")

        val json = JsonParser.parseString(responseBody).asJsonObject

        if (json.has("error") && !json.get("error").isJsonNull) {
            val error = json.getAsJsonObject("error")
            val code = error.get("code")?.asInt ?: -1
            val message = error.get("message")?.asString ?: "Unknown RPC error"
            throw RpcException("RPC error $code: $message")
        }

        json
    }

    /**
     * Scan the UTXO set for a specific address.
     * Stateless — works for ANY address without a wallet.
     */
    suspend fun scanTxOutSet(address: String): ScanResult {
        val scanObjects = com.google.gson.JsonArray().apply {
            add("addr($address)")
        }
        val result = call("scantxoutset", "start", scanObjects)
        val obj = result.getAsJsonObject("result")
            ?: return ScanResult(totalAmount = 0.0, unspents = emptyList())

        data class RawUtxo(val txid: String, val vout: Int, val amount: Double, val confs: Int, val scriptPubKey: String)

        val rawUnspents = obj.getAsJsonArray("unspents")?.map { elem ->
            val u = elem.asJsonObject
            val confs = u.get("height")?.asInt?.let { obj.get("height")?.asInt?.minus(it)?.plus(1) } ?: 0
            RawUtxo(
                txid = u.get("txid").asString,
                vout = u.get("vout").asInt,
                amount = u.get("amount").asDouble,
                confs = confs,
                scriptPubKey = u.get("scriptPubKey")?.asString ?: ""
            )
        } ?: emptyList()

        // Check coinbase status in parallel for UTXOs with < 100 confirmations
        val coinbaseResults = coroutineScope {
            rawUnspents.map { raw ->
                if (raw.confs < 100) async { isCoinbaseTx(raw.txid) }
                else async { false }
            }.awaitAll()
        }

        val unspents = rawUnspents.mapIndexed { i, raw ->
            Utxo(
                txid = raw.txid,
                vout = raw.vout,
                address = address,
                amount = raw.amount,
                confirmations = raw.confs,
                scriptPubKey = raw.scriptPubKey,
                isCoinbase = coinbaseResults[i]
            )
        }

        val immature = unspents.filter { it.isCoinbase && it.confirmations < 100 }
        val immatureAmt = immature.sumOf { it.amount }
        val immatureBlks = if (immature.isNotEmpty()) 100 - immature.maxOf { it.confirmations } else 0

        return ScanResult(
            totalAmount = obj.get("total_amount")?.asDouble ?: 0.0,
            unspents = unspents,
            immatureAmount = immatureAmt,
            immatureBlocks = immatureBlks
        )
    }

    /** Check if a transaction is a coinbase transaction. */
    private suspend fun isCoinbaseTx(txid: String): Boolean {
        return try {
            val result = call("getrawtransaction", txid, true)
            val tx = result.getAsJsonObject("result")
            val vin = tx?.getAsJsonArray("vin")
            vin != null && vin.size() > 0 && vin[0].asJsonObject.has("coinbase")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Create an unsigned raw transaction (stateless).
     * Uses the node to correctly encode addresses → scriptPubKeys.
     */
    suspend fun createRawTransaction(
        inputs: List<Utxo>,
        recipientAddress: String,
        recipientAmount: Double,
        changeAddress: String?,
        changeAmount: Double?
    ): String = withContext(Dispatchers.IO) {
        val id = ++requestId

        val inputsArr = JsonArray()
        for (utxo in inputs) {
            inputsArr.add(JsonObject().apply {
                addProperty("txid", utxo.txid)
                addProperty("vout", utxo.vout)
            })
        }

        val outputsArr = JsonArray()
        outputsArr.add(JsonObject().apply {
            addProperty(recipientAddress, recipientAmount)
        })
        if (changeAddress != null && changeAmount != null && changeAmount > 0.0) {
            outputsArr.add(JsonObject().apply {
                addProperty(changeAddress, changeAmount)
            })
        }

        val paramsArr = JsonArray().apply {
            add(inputsArr)
            add(outputsArr)
        }

        val requestBody = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", "createrawtransaction")
            add("params", paramsArr)
        }

        val body = requestBody.toString()

        val requestBuilder = Request.Builder()
            .url(buildUrl())
            .post(body.toRequestBody(JSON))

        val req = requestBuilder.build()
        val response = client.newCall(req).execute()
        val responseBody = response.body?.string()
            ?: throw RpcException("Empty response from node")

        val json = JsonParser.parseString(responseBody).asJsonObject
        if (json.has("error") && !json.get("error").isJsonNull) {
            val error = json.getAsJsonObject("error")
            val code = error.get("code")?.asInt ?: -1
            val message = error.get("message")?.asString ?: "Unknown RPC error"
            throw RpcException("RPC error $code: $message")
        }

        json.get("result")?.asString ?: throw RpcException("Failed to create raw transaction")
    }

    /** Broadcast a signed raw transaction. Returns the txid. */
    suspend fun sendRawTransaction(hexTx: String): String {
        val result = call("sendrawtransaction", hexTx)
        return result.get("result")?.asString ?: throw RpcException("Failed to broadcast tx")
    }

    /** Get blockchain info (stateless). */
    suspend fun getBlockchainInfo(): BlockchainInfo {
        val result = call("getblockchaininfo")
        val obj = result.getAsJsonObject("result")
        return BlockchainInfo(
            chain = obj.get("chain")?.asString ?: "unknown",
            blocks = obj.get("blocks")?.asInt ?: 0,
            headers = obj.get("headers")?.asInt ?: 0,
            bestBlockHash = obj.get("bestblockhash")?.asString ?: ""
        )
    }

    /** Get full transaction details (verbose). */
    suspend fun getTransactionDetails(txid: String): TxDetail? {
        return try {
            val result = call("getrawtransaction", txid, true)
            val tx = result.getAsJsonObject("result") ?: return null
            val confs = tx.get("confirmations")?.asInt ?: 0
            val time = tx.get("time")?.asLong ?: tx.get("blocktime")?.asLong ?: 0L

            val vouts = tx.getAsJsonArray("vout")?.map { v ->
                val vo = v.asJsonObject
                val value = vo.get("value")?.asDouble ?: 0.0
                val spk = vo.getAsJsonObject("scriptPubKey")
                val addrs = mutableListOf<String>()
                spk?.get("address")?.asString?.let { addrs.add(it) }
                if (addrs.isEmpty()) {
                    spk?.getAsJsonArray("addresses")?.forEach { a -> addrs.add(a.asString) }
                }
                TxVout(value, addrs)
            } ?: emptyList()

            val vinList = tx.getAsJsonArray("vin")?.mapNotNull { v ->
                val vi = v.asJsonObject
                if (vi.has("coinbase")) null
                else {
                    val inTxid = vi.get("txid")?.asString ?: return@mapNotNull null
                    val inVout = vi.get("vout")?.asInt ?: 0
                    TxVin(inTxid, inVout)
                }
            } ?: emptyList()

            TxDetail(txid, confs, time, vouts, vinList)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Check if a specific address funded any input of a transaction.
     * Looks up the previous TX for each vin and checks the specific output.
     */
    suspend fun isAddressSpender(detail: TxDetail, address: String): Boolean {
        for (vin in detail.vinList) {
            try {
                val prevTx = getTransactionDetails(vin.txid) ?: continue
                if (vin.vout < prevTx.voutList.size) {
                    if (prevTx.voutList[vin.vout].addresses.contains(address)) {
                        return true
                    }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Discover all transaction IDs for an address using address-indexed RPC methods.
     * Returns null if the node does not support any address indexing.
     */
    suspend fun discoverAllTxIds(address: String): List<String>? {
        // Try searchrawtransactions (Bitcoin forks with -txindex / -reindex)
        try {
            val json = call("searchrawtransactions", address, 1, 0, 9999)
            val results = json.getAsJsonArray("result")
            if (results != null && results.size() > 0) {
                return results.mapNotNull { it.asJsonObject.get("txid")?.asString }
            }
        } catch (_: Exception) {}
        // Try getaddresstxids (insight-style address index)
        try {
            val params = JsonObject().apply { add("addresses", JsonArray().apply { add(address) }) }
            val json = call("getaddresstxids", params)
            val results = json.getAsJsonArray("result")
            if (results != null && results.size() > 0) {
                return results.map { it.asString }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Fetch QBX/USDT price from KlingEx public API. Returns null on failure. */
    suspend fun fetchQbxPriceUsdt(): Double? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.klingex.io/api/tickers")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val arr = JsonParser.parseString(body).asJsonArray
            for (elem in arr) {
                val obj = elem.asJsonObject
                if (obj.get("ticker_id")?.asString == "QBX_USDT") {
                    return@withContext obj.get("last_price")?.asString?.toDoubleOrNull()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Test connection to the proxy/node. */
    suspend fun testConnection(): Boolean {
        return try {
            getBlockchainInfo()
            true
        } catch (_: Exception) {
            false
        }
    }
}

data class SendResult(val txid: String, val fee: Long)
data class BlockchainInfo(val chain: String, val blocks: Int, val headers: Int, val bestBlockHash: String)
data class Utxo(val txid: String, val vout: Int, val address: String, val amount: Double, val confirmations: Int, val scriptPubKey: String, val isCoinbase: Boolean = false)
data class ScanResult(val totalAmount: Double, val unspents: List<Utxo>, val immatureAmount: Double = 0.0, val immatureBlocks: Int = 0)

data class TxVout(val value: Double, val addresses: List<String>)
data class TxVin(val txid: String, val vout: Int)
data class TxDetail(
    val txid: String,
    val confirmations: Int,
    val time: Long,       // unix epoch seconds
    val voutList: List<TxVout>,
    val vinList: List<TxVin>  // txid:vout pairs of inputs
)

class RpcException(message: String) : Exception(message)
