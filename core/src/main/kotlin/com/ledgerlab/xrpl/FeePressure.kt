package com.ledgerlab.xrpl

import com.ledgerlab.xrpl.StreamParser.long
import kotlinx.serialization.json.JsonObject

enum class PressureLevel(val label: String) {
    CALM("Calm"),
    BUSY("Busy"),
    CONGESTED("Congested"),
}

/**
 * The `fee` command's view of the open ledger. When more transactions arrive than a ledger is expected to hold,
 * rippled escalates the fee to get into the open ledger and queues the rest.
 */
data class FeePressure(
    val baseFeeDrops: Long,
    val openLedgerFeeDrops: Long,
    val medianFeeDrops: Long,
    val currentLedgerSize: Int,
    val expectedLedgerSize: Int,
    val queueSize: Int,
    val maxQueueSize: Int,
    val openLedgerLevel: Long,
    val referenceLevel: Long,
) {
    /** How many times the normal fee it costs to get into the open ledger right now. */
    val multiplier: Double get() = if (referenceLevel <= 0) 1.0 else openLedgerLevel.toDouble() / referenceLevel

    /** How full the open ledger is against what the network expects to fit; above 1 the fee escalates. */
    val fill: Double get() = if (expectedLedgerSize <= 0) 0.0 else currentLedgerSize.toDouble() / expectedLedgerSize

    val queueFill: Double get() = if (maxQueueSize <= 0) 0.0 else queueSize.toDouble() / maxQueueSize

    val level: PressureLevel
        get() = when {
            multiplier >= CONGESTED_MULTIPLIER || queueFill >= 0.5 -> PressureLevel.CONGESTED
            multiplier > 1.0 || queueSize > 0 || fill >= 1.0 -> PressureLevel.BUSY
            else -> PressureLevel.CALM
        }

    /** A 0..1 needle position: the first half tracks how full the ledger is, the second half fee escalation. */
    val gauge: Double
        get() = if (multiplier <= 1.0 && queueSize == 0) {
            (fill.coerceIn(0.0, 1.0)) * 0.5
        } else {
            val escalation = kotlin.math.ln(multiplier.coerceAtLeast(1.0)) / kotlin.math.ln(CONGESTED_MULTIPLIER * 10)
            (0.5 + 0.5 * maxOf(escalation, queueFill)).coerceIn(0.5, 1.0)
        }

    companion object {
        const val CONGESTED_MULTIPLIER = 10.0

        fun parse(result: JsonObject): FeePressure {
            val drops = result["drops"] as? JsonObject
            val levels = result["levels"] as? JsonObject
            val base = drops?.long("base_fee") ?: 10L
            return FeePressure(
                baseFeeDrops = base,
                openLedgerFeeDrops = drops?.long("open_ledger_fee") ?: base,
                medianFeeDrops = drops?.long("median_fee") ?: base,
                currentLedgerSize = result.long("current_ledger_size")?.toInt() ?: 0,
                expectedLedgerSize = result.long("expected_ledger_size")?.toInt() ?: 0,
                queueSize = result.long("current_queue_size")?.toInt() ?: 0,
                maxQueueSize = result.long("max_queue_size")?.toInt() ?: 0,
                openLedgerLevel = levels?.long("open_ledger_level") ?: 256L,
                referenceLevel = levels?.long("reference_level") ?: 256L,
            )
        }
    }
}

/** XRP destroyed by fees, read from the validated ledger's `total_coins`. */
data class SupplyState(
    val ledgerIndex: Long,
    val totalCoinsDrops: Long,
    /** `total_coins` when this session started watching, to show what burned while you watched. */
    val sessionStartDrops: Long,
) {
    val burnedSinceGenesisDrops: Long get() = (GENESIS_DROPS - totalCoinsDrops).coerceAtLeast(0)
    val burnedThisSessionDrops: Long get() = (sessionStartDrops - totalCoinsDrops).coerceAtLeast(0)

    companion object {
        /** 100 billion XRP were created in the genesis ledger and no more can ever be made. */
        const val GENESIS_DROPS = 100_000_000_000L * 1_000_000L
    }
}
