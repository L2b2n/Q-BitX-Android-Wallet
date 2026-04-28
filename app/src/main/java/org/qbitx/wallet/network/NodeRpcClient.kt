package org.qbitx.wallet.network

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Stateless JSON-RPC client for the Q-BitX public proxy.
 * Only uses blockchain queries + tx building + broadcasting.
 * No wallet operations — keys never leave the device.
 */
class NodeRpcClient(
    private var rpcUrl: String = "https://qbitx.solopool.site/"
) {
    companion object {
        fun normalizeRpcUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) {
                throw RpcException("RPC-URL darf nicht leer sein")
            }

            val candidate = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }

            val httpUrl = candidate.toHttpUrlOrNull()
                ?: throw RpcException("Ungültige RPC-URL. Bitte http:// oder https:// mit gültigem Host angeben.")

            if (httpUrl.host.isBlank()) {
                throw RpcException("Ungültige RPC-URL. Host fehlt.")
            }

            return httpUrl.toString().trimEnd('/')
        }
    }

    private val gson = Gson()
    private val JSON = "application/json".toMediaType()

    // Trust all certificates (needed for self-signed certs on private nodes)
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val client: OkHttpClient = run {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private var requestId = 0

    fun configure(rpcUrl: String) {
        this.rpcUrl = normalizeRpcUrl(rpcUrl)
    }

    private fun buildUrl(): String = rpcUrl

    /**
     * Translate a non-2xx HTTP response into a clear, user-facing RpcException.
     * Especially handles common proxy errors (413, 404, 405, 5xx) instead of
     * dumping raw HTML from nginx error pages.
     */
    private fun httpErrorMessage(httpCode: Int, body: String?): String = when (httpCode) {
        413 -> "Server-Limit überschritten (HTTP 413). Der RPC-Proxy lehnt zu große Requests ab — " +
                "PQ-Transaktionen sind durch die Dilithium-Signatur (~5 KB pro Input) zu groß für diesen Node. " +
                "Bitte einen Node mit höherem nginx client_max_body_size verwenden " +
                "(z. B. eigenen qbitx-Node oder anderen öffentlichen Endpoint)."
        404 -> "RPC-Endpunkt nicht gefunden (HTTP 404). RPC-URL prüfen."
        405 -> "Falsche RPC-URL (HTTP 405 Method Not Allowed). Der Server akzeptiert kein POST an diesem Pfad — " +
                "URL korrigieren (häufig fehlt der RPC-Pfad oder der Port)."
        401, 403 -> "Authentifizierung fehlgeschlagen (HTTP $httpCode). RPC-User/Passwort prüfen."
        in 500..599 -> "Node nicht erreichbar (HTTP $httpCode). Bitte später erneut versuchen."
        else -> {
            val snippet = body?.take(200)?.replace("\\s+".toRegex(), " ")?.trim().orEmpty()
            "Node-Fehler HTTP $httpCode${if (snippet.isNotEmpty()) ": $snippet" else ""}"
        }
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

        val requestBuilder = Request.Builder()
            .url(buildUrl())
            .post(body.toRequestBody(JSON))

        val request = requestBuilder.build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        // Give a clear error for common HTTP failures (proxy 413, wrong path 405, etc.)
        // before attempting JSON parse — otherwise users see raw nginx HTML.
        if (!response.isSuccessful) {
            throw RpcException(httpErrorMessage(response.code, responseBody))
        }

        if (responseBody == null) {
            throw RpcException("Leere Antwort vom Node")
        }

        val json = try {
            JsonParser.parseString(responseBody).asJsonObject
        } catch (e: Exception) {
            throw RpcException("Ungültige Antwort vom Node (kein JSON): ${responseBody.take(200)}")
        }

        if (json.has("error") && !json.get("error").isJsonNull) {
            val error = json.getAsJsonObject("error")
            val code = error.get("code")?.asInt ?: -1
            val message = error.get("message")?.asString ?: "Unknown RPC error"
            throw RpcException("RPC-Fehler $code: $message")
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

        if (!response.isSuccessful) {
            throw RpcException(httpErrorMessage(response.code, responseBody))
        }
        if (responseBody == null) {
            throw RpcException("Leere Antwort vom Node")
        }

        val json = JsonParser.parseString(responseBody).asJsonObject
        if (json.has("error") && !json.get("error").isJsonNull) {
            val error = json.getAsJsonObject("error")
            val code = error.get("code")?.asInt ?: -1
            val message = error.get("message")?.asString ?: "Unknown RPC error"
            throw RpcException("RPC-Fehler $code: $message")
        }

        json.get("result")?.asString ?: throw RpcException("createrawtransaction lieferte kein Ergebnis")
    }

    /** Broadcast a signed raw transaction. Returns the txid. */
    suspend fun sendRawTransaction(hexTx: String): String {
        val result = call("sendrawtransaction", hexTx)
        return result.get("result")?.asString ?: throw RpcException("Broadcast fehlgeschlagen")
    }

    /**
     * Estimate fee rate via the node's estimatesmartfee.
     * @param confTarget Number of blocks for confirmation target (e.g. 2, 6, 25)
     * @return fee rate in sat/vB, or null if estimation unavailable
     */
    suspend fun estimateSmartFee(confTarget: Int): Double? {
        return try {
            val result = call("estimatesmartfee", confTarget)
            val obj = result.getAsJsonObject("result") ?: return null
            val feeRateBtcKb = obj.get("feerate")?.asDouble ?: return null
            // Convert BTC/kB to sat/vB: BTC/kB * 1e8 / 1000
            feeRateBtcKb * 1e8 / 1000.0
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetch dynamic fee rates for low/normal/high.
     * Returns a Triple(low, normal, high) in sat/vB.
     * Falls back to defaults if the node doesn't support estimatesmartfee.
     */
    suspend fun fetchFeeRates(): Triple<Double, Double, Double> = coroutineScope {
        val highDef = async { estimateSmartFee(2) }   // next 2 blocks
        val normDef = async { estimateSmartFee(6) }   // next 6 blocks
        val lowDef  = async { estimateSmartFee(25) }  // next 25 blocks
        val high = highDef.await() ?: 15.0
        val norm = normDef.await() ?: 5.0
        val low  = lowDef.await()  ?: 1.0
        Triple(
            low.coerceAtLeast(1.0),
            norm.coerceAtLeast(1.0),
            high.coerceAtLeast(1.0)
        )
    }

    /** Validate an address via the node. Returns true if valid. */
    suspend fun validateAddress(address: String): Boolean {
        return try {
            val result = call("validateaddress", address)
            val obj = result.getAsJsonObject("result") ?: return false
            obj.get("isvalid")?.asBoolean ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** Get blockchain info (stateless). */
    suspend fun getBlockchainInfo(): BlockchainInfo {
        val result = call("getblockchaininfo")
        val obj = result.getAsJsonObject("result")
            ?: throw RpcException("Invalid getblockchaininfo response")
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
     * Discover all transaction IDs for an address using the server-side scanblocks endpoint.
     * Privacy-preserving: no address is sent to the server — filtering happens locally.
     * Only scans blocks between lastScannedHeight+1 and blockHeight (incremental).
     * Returns Pair(txids, highestBlockScanned) so caller knows how far we got.
     */
    suspend fun discoverAllTxIds(
        address: String,
        blockHeight: Int = 0,
        lastScannedHeight: Int = 0,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Pair<List<String>, Int> {
        if (blockHeight <= 0) return Pair(emptyList(), lastScannedHeight)

        val startHeight = if (lastScannedHeight > 0) lastScannedHeight + 1 else 0
        if (startHeight > blockHeight) return Pair(emptyList(), lastScannedHeight)

        val found = mutableSetOf<String>()
        val batchSize = 2000
        var from = startHeight
        val totalBlocks = blockHeight - startHeight + 1
        var highestScanned = lastScannedHeight

        while (from <= blockHeight) {
            coroutineScope { ensureActive() }
            val to = minOf(from + batchSize - 1, blockHeight)
            var batch: List<String>? = null
            for (attempt in 1..3) {
                batch = scanBlocksBatch(from, to, address)
                if (batch != null) break
                if (attempt < 3) kotlinx.coroutines.delay(2000L * attempt)
            }
            if (batch == null) break
            found.addAll(batch)
            highestScanned = to
            onProgress?.invoke(to - startHeight + 1, totalBlocks)
            from = to + 1
        }

        return Pair(found.toList(), highestScanned)
    }

    /**
     * Fetch a range of blocks from the server-side scanblocks endpoint
     * and filter locally for transactions involving the given address.
     * Returns null on failure (rate limit / network error).
     */
    private suspend fun scanBlocksBatch(fromHeight: Int, toHeight: Int, address: String): List<String>? {
        return try {
            val result = call("scanblocks", fromHeight, toHeight)
            val obj = result.getAsJsonObject("result") ?: return emptyList()
            val txs = obj.getAsJsonArray("txs") ?: return emptyList()

            val matched = mutableListOf<String>()
            for (txElem in txs) {
                val tx = txElem.asJsonObject
                val addrs = tx.getAsJsonArray("a") ?: continue
                if (addrs.any { it.asString == address }) {
                    matched.add(tx.get("t").asString)
                }
            }
            matched
        } catch (_: Exception) {
            null
        }
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
