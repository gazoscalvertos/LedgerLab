package com.ledgerlab.xrpl

import java.util.Locale
import kotlin.math.abs

object Format {
    const val DROPS_PER_XRP = 1_000_000.0

    fun xrp(drops: Long): String = compact(drops / DROPS_PER_XRP) + " XRP"

    fun amount(amount: Amount?): String = when (amount) {
        is Amount.Xrp -> xrp(amount.drops)
        is Amount.Issued -> (amount.value.toDoubleOrNull()?.let(::compact) ?: amount.value) + " " + amount.currency
        null -> ""
    }

    /** 1234 -> "1.23K", 12_345_678 -> "12.3M". Small numbers keep a few decimals. */
    fun compact(value: Double): String {
        val a = abs(value)
        return when {
            a >= 1e9 -> String.format(Locale.US, "%.2fB", value / 1e9)
            a >= 1e6 -> String.format(Locale.US, "%.2fM", value / 1e6)
            a >= 1e4 -> String.format(Locale.US, "%.1fK", value / 1e3)
            a >= 100 -> String.format(Locale.US, "%.0f", value)
            a >= 1 -> String.format(Locale.US, "%.2f", value)
            a == 0.0 -> "0"
            else -> String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
        }
    }

    fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)

    fun shortAddress(address: String): String =
        if (address.length > 12) address.take(6) + "…" + address.takeLast(4) else address
}
