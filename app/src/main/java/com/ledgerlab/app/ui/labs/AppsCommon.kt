package com.ledgerlab.app.ui.labs

import com.ledgerlab.app.data.StoredWallet
import com.ledgerlab.xrpl.Format
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ledgerTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss", Locale.US)

/** A Ripple-epoch timestamp as local date and time. */
fun formatLedgerTime(rippleSeconds: Long): String =
    Instant.ofEpochSecond(rippleSeconds + 946_684_800L).atZone(ZoneId.systemDefault()).format(ledgerTime)

/** "4m 05s" style countdown to a Ripple-epoch time, or null once it has passed. */
fun timeLeft(rippleSeconds: Long, now: Long = rippleNow()): String? {
    val left = rippleSeconds - now
    if (left <= 0) return null
    return when {
        left >= 86_400 -> "${left / 86_400}d ${left % 86_400 / 3_600}h"
        left >= 3_600 -> "${left / 3_600}h ${left % 3_600 / 60}m"
        else -> "${left / 60}m %02ds".format(left % 60)
    }
}

/** XRP with up to six decimals, for amounts too small for [Format.xrp]. */
fun preciseXrp(drops: Long): String =
    "%.6f".format(Locale.US, drops / Format.DROPS_PER_XRP).trimEnd('0').trimEnd('.') + " XRP"

/** The saved wallet's name for an address, or the short address. */
fun nameOf(address: String?, wallets: List<StoredWallet>): String =
    address?.let { a -> wallets.firstOrNull { it.address == a }?.label ?: shortAddr(a) } ?: "unknown"

/** A field of a stored JSON record as text. */
internal fun kotlinx.serialization.json.JsonObject.text(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
