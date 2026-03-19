package org.qbitx.wallet.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Bitcoin-compatible transaction builder for Q-BitX PQ (Dilithium) transactions.
 *
 * Handles parsing raw tx hex, computing legacy sighash (SigVersion::BASE),
 * and constructing Dilithium-signed scriptSigs — all locally on the device.
 * No private key material ever leaves the phone.
 */
object TransactionBuilder {

    // Dilithium3 (ML-DSA-65) sizes
    const val DILITHIUM_SIG_BYTES = 3309
    const val DILITHIUM_PK_BYTES = 1952

    data class TxInput(
        val txid: ByteArray,        // 32 bytes, internal byte order
        val vout: Long,
        var scriptSig: ByteArray = ByteArray(0),
        val sequence: Long = 0xFFFFFFFFL
    )

    data class TxOutput(
        val amount: Long,           // satoshis
        val scriptPubKey: ByteArray
    )

    data class RawTransaction(
        val version: Int,
        val inputs: MutableList<TxInput>,
        val outputs: MutableList<TxOutput>,
        val locktime: Long
    )

    /**
     * Estimate the fee for a PQ transaction in satoshis.
     *
     * PQ scriptSig per input:
     *   OP_PUSHDATA2(3) + sig(3309) + hashtype(1) + OP_PUSHDATA2(3) + pubkey(1952) = 5268 bytes
     * Plus prevout(36) + sequence(4) + scriptSig length varint(3) = 5311 bytes/input
     *
     * @param feeRate sat/vB (1=low, 5=normal, 15=high)
     */
    fun estimateFee(numInputs: Int, numOutputs: Int, feeRate: Long = 5L): Long {
        val perInput = 32L + 4 + 3 + 3 + DILITHIUM_SIG_BYTES + 1 + 3 + DILITHIUM_PK_BYTES + 4
        val perOutput = 8L + 1 + 25   // amount + varint + typical PQ P2PKH scriptPubKey
        val base = 4L + 1 + 1 + 4     // version + vin_count + vout_count + locktime
        val txSize = base + perInput * numInputs + perOutput * numOutputs
        return txSize * feeRate
    }

    /** Map fee policy string to sat/vB rate. */
    fun feeRateForPolicy(policy: String): Long = when (policy) {
        "low" -> 1L
        "high" -> 15L
        else -> 5L  // normal
    }

    // ============ PARSE / SERIALIZE ============

    /** Parse a raw transaction hex string into a RawTransaction struct. */
    fun parseRawTx(hex: String): RawTransaction {
        val bytes = hexToBytes(hex)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val version = buf.int

        val numInputs = readVarInt(buf)
        val inputs = mutableListOf<TxInput>()
        for (i in 0 until numInputs.toInt()) {
            val txid = ByteArray(32)
            buf.get(txid)
            val vout = buf.int.toLong() and 0xFFFFFFFFL
            val scriptLen = readVarInt(buf)
            val scriptSig = ByteArray(scriptLen.toInt())
            buf.get(scriptSig)
            val sequence = buf.int.toLong() and 0xFFFFFFFFL
            inputs.add(TxInput(txid, vout, scriptSig, sequence))
        }

        val numOutputs = readVarInt(buf)
        val outputs = mutableListOf<TxOutput>()
        for (i in 0 until numOutputs.toInt()) {
            val amount = buf.long
            val scriptLen = readVarInt(buf)
            val scriptPubKey = ByteArray(scriptLen.toInt())
            buf.get(scriptPubKey)
            outputs.add(TxOutput(amount, scriptPubKey))
        }

        val locktime = buf.int.toLong() and 0xFFFFFFFFL
        return RawTransaction(version, inputs, outputs, locktime)
    }

    /** Serialize a RawTransaction back to bytes. */
    fun serializeTx(tx: RawTransaction): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(intToLe(tx.version))
        out.write(encodeVarInt(tx.inputs.size.toLong()))
        for (input in tx.inputs) {
            out.write(input.txid)
            out.write(intToLe(input.vout.toInt()))
            out.write(encodeVarInt(input.scriptSig.size.toLong()))
            out.write(input.scriptSig)
            out.write(intToLe(input.sequence.toInt()))
        }
        out.write(encodeVarInt(tx.outputs.size.toLong()))
        for (output in tx.outputs) {
            out.write(longToLe(output.amount))
            out.write(encodeVarInt(output.scriptPubKey.size.toLong()))
            out.write(output.scriptPubKey)
        }
        out.write(intToLe(tx.locktime.toInt()))
        return out.toByteArray()
    }

    // ============ SIGHASH ============

    /**
     * Compute the legacy sighash (SigVersion::BASE) for one input.
     *
     * Exactly matches Bitcoin Core's SignatureHash() with SIGHASH_ALL:
     *  1. Replace all inputs' scriptSig with empty
     *  2. Set the current input's scriptSig to the UTXO's scriptPubKey
     *  3. Serialize the modified tx + 4-byte LE hashtype
     *  4. Double SHA-256
     */
    fun computeSighash(
        tx: RawTransaction,
        inputIndex: Int,
        scriptPubKey: ByteArray,
        hashType: Int = 0x01 // SIGHASH_ALL
    ): ByteArray {
        val modInputs = tx.inputs.mapIndexed { i, inp ->
            val script = if (i == inputIndex) scriptPubKey.copyOf() else ByteArray(0)
            TxInput(inp.txid.copyOf(), inp.vout, script, inp.sequence)
        }.toMutableList()

        val modTx = RawTransaction(tx.version, modInputs, tx.outputs.toMutableList(), tx.locktime)

        val serialized = serializeTx(modTx)
        val out = ByteArrayOutputStream()
        out.write(serialized)
        out.write(intToLe(hashType))

        return doubleSha256(out.toByteArray())
    }

    // ============ SCRIPTSIG ============

    /**
     * Build PQ scriptSig: `<sig+hashtype> <pubkey>`
     * Uses OP_PUSHDATA2 for data > 255 bytes (Dilithium sig ~3310, pubkey 1952).
     */
    fun buildPqScriptSig(signature: ByteArray, publicKey: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val sigWithHashtype = signature + byteArrayOf(0x01) // SIGHASH_ALL
        pushData(out, sigWithHashtype)
        pushData(out, publicKey)
        return out.toByteArray()
    }

    // ============ SIGN TRANSACTION ============

    /**
     * Sign all inputs of an unsigned transaction with Dilithium.
     *
     * @param unsignedTxHex  Hex from createrawtransaction (all scriptSigs empty)
     * @param utxoScriptPubKeys  Map of inputIndex → scriptPubKey hex of the UTXO being spent
     * @param signFn  Called with a 32-byte sighash, returns the Dilithium signature
     * @param publicKey  The Dilithium public key bytes (1952 bytes)
     * @return Signed transaction hex ready for sendrawtransaction
     */
    fun signTransaction(
        unsignedTxHex: String,
        utxoScriptPubKeys: Map<Int, String>,
        signFn: (ByteArray) -> ByteArray?,
        publicKey: ByteArray
    ): String {
        val tx = parseRawTx(unsignedTxHex)

        for ((inputIndex, scriptPubKeyHex) in utxoScriptPubKeys) {
            val scriptPubKey = hexToBytes(scriptPubKeyHex)
            val sighash = computeSighash(tx, inputIndex, scriptPubKey)
            val signature = signFn(sighash)
                ?: throw IllegalStateException("Dilithium signing failed for input $inputIndex")
            tx.inputs[inputIndex].scriptSig = buildPqScriptSig(signature, publicKey)
        }

        return bytesToHex(serializeTx(tx))
    }

    // ============ HELPERS ============

    private fun pushData(out: ByteArrayOutputStream, data: ByteArray) {
        val len = data.size
        when {
            len < 76 -> out.write(len)
            len < 256 -> {
                out.write(0x4C) // OP_PUSHDATA1
                out.write(len)
            }
            len < 65536 -> {
                out.write(0x4D) // OP_PUSHDATA2
                out.write(len and 0xFF)
                out.write((len shr 8) and 0xFF)
            }
            else -> {
                out.write(0x4E) // OP_PUSHDATA4
                out.write(len and 0xFF)
                out.write((len shr 8) and 0xFF)
                out.write((len shr 16) and 0xFF)
                out.write((len shr 24) and 0xFF)
            }
        }
        out.write(data)
    }

    private fun readVarInt(buf: ByteBuffer): Long {
        val first = buf.get().toLong() and 0xFF
        return when {
            first < 0xFD -> first
            first == 0xFDL -> buf.short.toLong() and 0xFFFF
            first == 0xFEL -> buf.int.toLong() and 0xFFFFFFFFL
            else -> buf.long
        }
    }

    private fun encodeVarInt(value: Long): ByteArray = when {
        value < 0xFD -> byteArrayOf(value.toByte())
        value <= 0xFFFF -> byteArrayOf(
            0xFD.toByte(),
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
        value <= 0xFFFFFFFFL -> byteArrayOf(
            0xFE.toByte(),
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
        else -> {
            val b = ByteArray(9)
            b[0] = 0xFF.toByte()
            ByteBuffer.wrap(b, 1, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
            b
        }
    }

    private fun intToLe(v: Int): ByteArray {
        val b = ByteArray(4)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(v)
        return b
    }

    private fun longToLe(v: Long): ByteArray {
        val b = ByteArray(8)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(v)
        return b
    }

    private fun doubleSha256(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(md.digest(data))
    }

    private fun hexToBytes(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
