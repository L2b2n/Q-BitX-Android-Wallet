package org.qbitx.wallet.crypto

/**
 * Splits a payment into one or more transactions ("batches") that the Q-BitX node
 * will accept as standard.
 *
 * Since PQ witness activation (block 230000) the node weighs a transaction as
 *   16 * non-witness bytes + witness bytes
 * and rejects anything above 400,000 with "tx-size" (-26). A legacy "M..." input
 * is ~5.3 KB of non-witness data, so only 4 of them fit into one transaction; a
 * native PQ witness "dil1q..." input keeps its signature and public key in the
 * witness and is ~14x lighter. The planner therefore limits each batch by weight
 * instead of by a fixed input count, and handles mixed inputs.
 *
 * Pure Kotlin on purpose (no Android dependencies) so it can be unit-tested on a JVM.
 */
object SendPlanner {

    /** Smallest change output worth creating; below this the remainder goes to the fee. */
    const val DUST_SAT = 546L

    /** Safety margin below TransactionBuilder.MAX_STANDARD_TX_WEIGHT. */
    const val MAX_BATCH_WEIGHT = 380_000L

    /**
     * Upper bound on inputs per batch regardless of weight. A transaction with 60 native PQ
     * witness inputs (weight ~357,000) is verified against a Q-BitX node; 64 would exceed
     * MAX_BATCH_WEIGHT anyway.
     */
    const val MAX_INPUTS_PER_BATCH = 60

    /** A spendable output. [ref] is an opaque index back into the caller's UTXO list. */
    data class Coin(val amountSat: Long, val isWitness: Boolean, val ref: Int)

    data class Batch(
        val coinRefs: List<Int>,
        val sendSat: Long,
        val changeSat: Long,
        val feeSat: Long
    )

    /** Why a plan could not be built. The UI maps these to localized messages. */
    enum class Failure { INSUFFICIENT_FUNDS, UTXOS_TOO_SMALL }

    class PlanException(val failure: Failure, val missingSat: Long) :
        Exception("${failure.name} (missing $missingSat sat)")

    /**
     * Plan the batches needed to send [amountSat].
     * Nothing is signed or broadcast here: if the payment is impossible this throws
     * before any transaction exists.
     */
    fun plan(coins: List<Coin>, amountSat: Long, feeRate: Long): List<Batch> {
        require(amountSat > 0) { "amount must be positive" }
        require(feeRate > 0) { "fee rate must be positive" }

        // Largest first: fewest inputs, fewest transactions.
        val pool = coins.sortedByDescending { it.amountSat }.toMutableList()
        val batches = mutableListOf<Batch>()
        var remaining = amountSat

        while (remaining > 0) {
            if (pool.isEmpty()) {
                throw PlanException(Failure.INSUFFICIENT_FUNDS, remaining)
            }

            val batch = mutableListOf<Coin>()
            var legacy = 0
            var witness = 0
            var inSat = 0L

            while (pool.isNotEmpty() && batch.size < MAX_INPUTS_PER_BATCH) {
                val next = pool[0]
                val nextLegacy = legacy + if (next.isWitness) 0 else 1
                val nextWitness = witness + if (next.isWitness) 1 else 0
                // Always allow the first input; afterwards stop before the batch gets too heavy.
                if (batch.isNotEmpty() &&
                    TransactionBuilder.estimateTxWeight(nextLegacy, nextWitness, 2) > MAX_BATCH_WEIGHT
                ) {
                    break
                }
                pool.removeAt(0)
                batch.add(next)
                legacy = nextLegacy
                witness = nextWitness
                inSat += next.amountSat
                val feeWithChange = TransactionBuilder.estimateFee(legacy, witness, 2, feeRate)
                if (inSat >= remaining + feeWithChange) break
            }

            val fee2 = TransactionBuilder.estimateFee(legacy, witness, 2, feeRate)
            val fee1 = TransactionBuilder.estimateFee(legacy, witness, 1, feeRate)

            val planned = if (inSat >= remaining + fee2) {
                // This batch finishes the payment.
                val rawChange = inSat - remaining - fee2
                if (rawChange >= DUST_SAT) {
                    Batch(batch.map { it.ref }, remaining, rawChange, fee2)
                } else {
                    Batch(batch.map { it.ref }, remaining, 0L, inSat - remaining)
                }
            } else if (inSat >= remaining + fee1) {
                // Enough for the payment without a change output; the small surplus is the fee.
                Batch(batch.map { it.ref }, remaining, 0L, inSat - remaining)
            } else {
                // Partial batch: send everything these inputs hold, continue with the next batch.
                if (pool.isEmpty()) {
                    throw PlanException(Failure.INSUFFICIENT_FUNDS, remaining + fee1 - inSat)
                }
                if (inSat <= fee1 + DUST_SAT) {
                    throw PlanException(Failure.UTXOS_TOO_SMALL, remaining)
                }
                var send = inSat - fee1
                // Never leave a remainder the next batch could only pay as a dust output.
                val left = remaining - send
                if (left in 1 until DUST_SAT) {
                    send -= (DUST_SAT - left)
                }
                Batch(batch.map { it.ref }, send, 0L, inSat - send)
            }

            batches.add(planned)
            remaining -= planned.sendSat
        }

        return batches
    }
}
