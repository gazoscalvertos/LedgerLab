package com.ledgerlab.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Loyalty
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Token
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ledgerlab.app.ui.theme.LabColors

/** LedgerLab's screens. [mockup] ones are use cases: small apps built from several features, shown in their own section. */
enum class LabFeature(
    val title: String,
    val blurb: String,
    val icon: ImageVector,
    val color: Color,
    val available: Boolean = true,
    val mockup: Boolean = false,
) {
    WALLET("Wallet", "Create or import a wallet and fund it from the faucet", Icons.Outlined.AccountBalanceWallet, LabColors.Matrix),
    TOKENS("Tokens", "Issue, hold and send your own tokens", Icons.Outlined.Token, LabColors.Gold),
    DEX("DEX", "Place offers on the built-in order book", Icons.Outlined.CandlestickChart, LabColors.Cyan),
    NFTS("NFTs", "Mint to IPFS, then list and trade", Icons.Outlined.Image, LabColors.Magenta),
    ESCROW("Escrow", "Lock XRP or tokens until a time", Icons.Outlined.Lock, LabColors.Orange),
    CHANNELS("Channels", "Stream XRP micropayments off ledger", Icons.Outlined.Bolt, LabColors.Matrix),
    AMM("AMM", "Provide liquidity to automated market makers", Icons.Outlined.WaterDrop, LabColors.Violet),
    EXPLORER("Explorer", "Look up any account or transaction", Icons.Outlined.Search, LabColors.TextPrimary),
    NAMES(".xrp names", "Register names, sell subdomains, pay by name", Icons.Outlined.Language, LabColors.Cyan, mockup = true),
    NOTARY("Notary", "Timestamp a file's fingerprint, verify it later", Icons.Outlined.Verified, LabColors.Gold, mockup = true),
    TICKETS("Event tickets", "NFT tickets with royalties and door check-in", Icons.Outlined.ConfirmationNumber, LabColors.Magenta, mockup = true),
    METER("Pay-per-minute", "Stream XRP by the second, settle once", Icons.Outlined.Timer, LabColors.Cyan, mockup = true),
    JOBS("Freelance jobs", "Escrowed pay, released when work is accepted", Icons.Outlined.Work, LabColors.Orange, mockup = true),
    CROWDFUND("Crowdfunding", "All-or-nothing pledges held in escrow", Icons.Outlined.VolunteerActivism, LabColors.Matrix, mockup = true),
    LOYALTY("Loyalty points", "A shop token with approval and clawback", Icons.Outlined.Loyalty, LabColors.Violet, mockup = true),
    SUBSCRIPTIONS("Subscriptions", "Monthly Checks the merchant cashes", Icons.Outlined.Autorenew, LabColors.Cyan, mockup = true),
    TREASURY("Shared treasury", "A club wallet that needs 2 of 3 to spend", Icons.Outlined.AccountBalance, LabColors.Gold, mockup = true),
    BADGES("Verified badges", "On-ledger credentials anyone can check", Icons.Outlined.WorkspacePremium, LabColors.Magenta, mockup = true),
}

@Composable
fun LabsGrid(
    onOpen: (LabFeature) -> Unit,
    modifier: Modifier = Modifier,
    features: List<LabFeature> = LabFeature.entries.filter { !it.mockup },
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        features.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { LabCard(it, onOpen, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LabCard(feature: LabFeature, onOpen: (LabFeature) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(LabColors.Surface)
            .border(1.dp, if (feature.available) feature.color.copy(alpha = 0.5f) else LabColors.Outline, shape)
            .clickable(enabled = feature.available) { onOpen(feature) }
            .padding(14.dp),
    ) {
        Icon(feature.icon, contentDescription = null, tint = feature.color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(10.dp))
        Text(feature.title, style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
        Spacer(Modifier.height(2.dp))
        Text(feature.blurb, style = MaterialTheme.typography.bodySmall, color = LabColors.TextSecondary, minLines = 2, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Text(
            if (feature.available) "OPEN" else "SOON",
            style = MaterialTheme.typography.labelSmall,
            color = feature.color,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(feature.color.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
