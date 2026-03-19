package org.qbitx.wallet.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * JSON-RPC client for communicating with a Q-BitX Core node.
 * Handles balance queries, transaction broadcasting, and UTXO listing.
 */
class NodeRpcClient(
    private var host: String = "127.0.0.1",
    private var port: Int = 8332,
    private var user: String = "qbitx",
    private var password: String = "qbitx",
    private var wallet: String = ""
) {
    private val gson = Gson()
    private val JSON = "application/json".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var requestId = 0

    fun configure(host: String, port: Int, user: String, password: String, wallet: String = "") {
        this.host = host
        this.port = port
        this.user = user
        this.password = password
        this.wallet = wallet
    }

    private fun buildUrl(): String {
        val base = "http://$host:$port"
        return if (wallet.isNotEmpty()) "$base/wallet/$wallet" else base
    }

    /** Execute a JSON-RPC call and return the result as JsonObject. */
    suspend fun call(method: String, vararg params: Any?): JsonObject = withContext(Dispatchers.IO) {
        val id = ++requestId
        val body = gson.toJson(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to method,
            "params" to params.toList()
        ))

        val request = Request.Builder()
            .url(buildUrl())
            .post(body.toRequestBody(JSON))
            .header("Authorization", Credentials.basic(user, password))
            .build()

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

    /** Get wallet balance. Returns balance in QBX as a Double. */
    suspend fun getBalance(): Double {
        val result = call("getbalance")
        return result.get("result")?.asDouble ?: 0.0
    }

    /** Get a new PQ address from the node wallet. */
    suspend fun getNewAddress(label: String = "", addressType: String = "pq"): String {
        val result = call("getnewaddress", label, addressType)
        return result.get("result")?.asString ?: throw RpcException("Failed to get new address")
    }

    /** Send QBX using pqsendtoaddress. */
    suspend fun pqSendToAddress(toAddress: String, amount: Double, feePolicy: String = "normal"): SendResult {
        val result = call("pqsendtoaddress", toAddress, amount, feePolicy)
        val obj = result.getAsJsonObject("result")
        return SendResult(
            txid = obj.get("txid")?.asString ?: "",
            amount = obj.get("amount")?.asDouble ?: 0.0,
            fee = obj.get("fee")?.asDouble ?: 0.0,
            change = obj.get("change")?.asDouble ?: 0.0
        )
    }

    /** Send raw transaction (for local signing). */
    suspend fun sendRawTransaction(hexTx: String): String {
        val result = call("sendrawtransaction", hexTx)
        return result.get("result")?.asString ?: throw RpcException("Failed to broadcast tx")
    }

    /** Get blockchain info. */
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

    /** List unspent UTXOs. */
    suspend fun listUnspent(minConf: Int = 1, maxConf: Int = 9999999): List<Utxo> {
        val result = call("listunspent", minConf, maxConf)
        val arr = result.getAsJsonArray("result")
        return arr.map { elem ->
            val obj = elem.asJsonObject
            Utxo(
                txid = obj.get("txid").asString,
                vout = obj.get("vout").asInt,
                address = obj.get("address")?.asString ?: "",
                amount = obj.get("amount").asDouble,
                confirmations = obj.get("confirmations").asInt,
                scriptPubKey = obj.get("scriptPubKey")?.asString ?: ""
            )
        }
    }

    /** Get wallet info. */
    suspend fun getWalletInfo(): WalletInfo {
        val result = call("getwalletinfo")
        val obj = result.getAsJsonObject("result")
        return WalletInfo(
            walletName = obj.get("walletname")?.asString ?: "",
            balance = obj.get("balance")?.asDouble ?: 0.0,
            unconfirmedBalance = obj.get("unconfirmed_balance")?.asDouble ?: 0.0,
            immatureBalance = obj.get("immature_balance")?.asDouble ?: 0.0,
            txCount = obj.get("txcount")?.asInt ?: 0
        )
    }

    /** Test connection to the node. */
    suspend fun testConnection(): Boolean {
        return try {
            getBlockchainInfo()
            true
        } catch (_: Exception) {
            false
        }
    }
}

data class SendResult(val txid: String, val amount: Double, val fee: Double, val change: Double)
data class BlockchainInfo(val chain: String, val blocks: Int, val headers: Int, val bestBlockHash: String)
data class Utxo(val txid: String, val vout: Int, val address: String, val amount: Double, val confirmations: Int, val scriptPubKey: String)
data class WalletInfo(val walletName: String, val balance: Double, val unconfirmedBalance: Double, val immatureBalance: Double, val txCount: Int)

class RpcException(message: String) : Exception(message)
