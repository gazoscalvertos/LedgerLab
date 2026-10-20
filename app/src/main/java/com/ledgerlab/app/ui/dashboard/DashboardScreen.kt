package com.ledgerlab.app.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CandlestickChart
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerlab.app.ui.components.AccountGrowth
import com.ledgerlab.app.ui.components.AmendmentList
import com.ledgerlab.app.ui.components.BusiestAccounts
import com.ledgerlab.app.ui.components.CategoryDonut
import com.ledgerlab.app.ui.components.DexAmmPulse
import com.ledgerlab.app.ui.components.EscrowsChecks
import com.ledgerlab.app.ui.components.FeeGauge
import com.ledgerlab.app.ui.components.Heartbeat
import com.ledgerlab.app.ui.components.LabFeature
import com.ledgerlab.app.ui.components.LabsGrid
import com.ledgerlab.app.ui.components.LedgerPulse
import com.ledgerlab.app.ui.components.NetworkSwitcher
import com.ledgerlab.app.ui.components.Panel
import com.ledgerlab.app.ui.components.PeriodSwitcher
import com.ledgerlab.app.ui.components.ServerStatus
import com.ledgerlab.app.ui.components.Sparkline
import com.ledgerlab.app.ui.components.StatTile
import com.ledgerlab.app.ui.components.TagsMemos
import com.ledgerlab.app.ui.components.TokenNftActivity
import com.ledgerlab.app.ui.components.TxLane
import com.ledgerlab.app.ui.components.TypeBreakdown
import com.ledgerlab.app.ui.components.ValidatorMap
import com.ledgerlab.app.ui.components.WhaleFeed
import com.ledgerlab.app.ui.components.networkColor
import com.ledgerlab.app.ui.components.pressureColor
import com.ledgerlab.app.ui.labs.LabChip
import com.ledgerlab.app.ui.theme.LabColors
import com.ledgerlab.app.ui.theme.Mono
import com.ledgerlab.xrpl.ActivitySnapshot
import com.ledgerlab.xrpl.DashboardSnapshot
import com.ledgerlab.xrpl.Format
import com.ledgerlab.xrpl.StatsPeriod
import com.ledgerlab.xrpl.StreamTx
import com.ledgerlab.xrpl.XrplNetwork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun DashboardScreen(
    network: XrplNetwork,
    connection: ConnectionState,
    snapshot: DashboardSnapshot,
    transactions: Flow<StreamTx>,
    period: StatsPeriod,
    history: HistoryState,
    health: NetworkHealth,
    whaleThresholdXrp: Long,
    onSetWhaleThreshold: (Long) -> Unit,
    onSelectPeriod: (StatsPeriod) -> Unit,
    onRetryHistory: () -> Unit,
    onSelectNetwork: (XrplNetwork) -> Unit,
    onOpenFeature: (LabFeature) -> Unit,
) {
    val accent = networkColor(network)
    val whaleDrops = whaleThresholdXrp * 1_000_000L
    val insets = WindowInsets.systemBars.asPaddingValues()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    var statsOpen by rememberSaveable { mutableStateOf(true) }
    var basicsOpen by rememberSaveable { mutableStateOf(true) }
    var useCasesOpen by rememberSaveable { mutableStateOf(true) }
    var group by rememberSaveable { mutableStateOf(StatsGroup.OVERVIEW) }

    // The two lab sections are always the last two rows; the stats heading sits after header, pulse and lane.
    fun jump(target: MenuTarget) {
        scope.launch {
            drawer.close()
            when (target) {
                MenuTarget.TOP -> list.animateScrollToItem(0)
                MenuTarget.STATS -> {
                    statsOpen = true
                    list.animateScrollToItem(STATS_ROW)
                }
                MenuTarget.BASICS -> {
                    basicsOpen = true
                    list.animateScrollToItem((list.layoutInfo.totalItemsCount - 2).coerceAtLeast(0))
                }
                MenuTarget.USE_CASES -> {
                    useCasesOpen = true
                    list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        // Swipe-to-open would fight the sideways-scrolling chip rows, so the menu button opens it.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            DashboardMenu(
                network = network,
                accent = accent,
                onJump = { jump(it) },
                onGroup = {
                    group = it
                    jump(MenuTarget.STATS)
                },
                onOpenFeature = {
                    scope.launch { drawer.close() }
                    onOpenFeature(it)
                },
            )
        },
    ) {
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize().background(LabColors.Background),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = insets.calculateTopPadding() + 12.dp,
            bottom = insets.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Header(network, connection, onSelectNetwork, onMenu = { scope.launch { drawer.open() } }) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedgerPulse(
                    ledgerIndex = snapshot.ledgerIndex,
                    lastCloseAtMs = snapshot.lastCloseAtMs,
                    avgCloseSeconds = snapshot.avgCloseSeconds,
                    accent = accent,
                    modifier = Modifier.weight(1.1f).aspectRatio(1f),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("THROUGHPUT", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                    Text(
                        String.format(Locale.US, "%.1f", snapshot.tps),
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 44.sp,
                        color = accent,
                    )
                    Text("transactions / sec", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "peak ${String.format(Locale.US, "%.1f", snapshot.peakTps)} tx/s",
                        fontFamily = Mono,
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextSecondary,
                    )
                    Text(
                        "${Format.grouped(snapshot.txSeen)} txs watched",
                        fontFamily = Mono,
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.TextSecondary,
                    )
                }
            }
        }

        item(key = "lane-$network") {
            TxLane(transactions, whaleDrops, Modifier.fillMaxWidth().height(260.dp))
        }

        item(key = "stats") {
            Column {
                SectionHeader("STATS", "Live numbers from the ledger", accent, statsOpen) { statsOpen = !statsOpen }
                AnimatedVisibility(statsOpen) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        PeriodSwitcher(period, onSelectPeriod, accent)
                        Spacer(Modifier.height(10.dp))
                        GroupSwitcher(group, { group = it }, accent)
                    }
                }
            }
        }

        if (statsOpen) {
            if (period == StatsPeriod.LIVE) {
                when (group) {
                    StatsGroup.OVERVIEW -> {
                        item(key = "grid") { StatGrid(snapshot, accent) }
                        item(key = "heartbeat") {
                            Panel(title = "Ledger heartbeat", accent = accent, trailing = {
                                Text("last 60s", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                            }) {
                                Heartbeat(snapshot.closeIntervals, snapshot.lastCloseAtMs, snapshot.ledgerIndex, accent, Modifier.fillMaxWidth())
                            }
                        }
                        item(key = "tps") {
                            Panel(title = "Transactions per ledger", accent = accent, trailing = {
                                Text("last ${snapshot.tpsHistory.size} ledgers", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                            }) {
                                Sparkline(snapshot.tpsHistory, accent, Modifier.fillMaxWidth().height(110.dp))
                            }
                        }
                        item(key = "mix") {
                            Panel(title = "What's happening on-ledger", accent = LabColors.Cyan, trailing = {
                                Text("last 20 ledgers", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                            }) {
                                CategoryDonut(snapshot.categoryCounts, Modifier.fillMaxWidth())
                                Spacer(Modifier.height(16.dp))
                                TypeBreakdown(snapshot.typeCounts, Modifier.fillMaxWidth())
                            }
                        }
                    }
                    StatsGroup.WHALES -> item(key = "whales") {
                        Panel(title = "Whale alerts", accent = LabColors.Gold, trailing = {
                            Text("this session", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
                        }) {
                            WhaleThreshold(whaleThresholdXrp, onSetWhaleThreshold)
                            Spacer(Modifier.height(14.dp))
                            WhaleFeed(
                                snapshot.whaleAlerts,
                                whaleDrops,
                                Modifier.fillMaxWidth(),
                                ranked = false,
                                emptyText = "No payments of ${Format.grouped(whaleThresholdXrp)} XRP or more yet. They'll pop up in the lane as they land.",
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("BIGGEST XRP PAYMENTS", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
                            Spacer(Modifier.height(10.dp))
                            WhaleFeed(snapshot.whales, whaleDrops, Modifier.fillMaxWidth())
                        }
                    }
                    StatsGroup.MARKETS -> marketItems(snapshot.activity, estimated = false, window = "this session")
                    StatsGroup.ACTIVITY -> activityItems(snapshot.activity, estimated = false, window = "this session", health = health,
                        burnedDrops = health.supply?.burnedThisSessionDrops, burnedLabel = "Burned while you watched")
                    StatsGroup.NETWORK -> liveNetworkItems(health)
                }
            } else {
                periodItems(period, history, accent, group, health, whaleDrops, onRetryHistory)
            }
        }

        item(key = "basics") {
            LabSection(
                eyebrow = "THE BASICS",
                title = "Core XRPL features, hands on",
                features = LabFeature.entries.filter { !it.mockup },
                accent = accent,
                network = network,
                open = basicsOpen,
                onToggle = { basicsOpen = !basicsOpen },
                onOpenFeature = onOpenFeature,
            )
        }

        item(key = "usecases") {
            LabSection(
                eyebrow = "USE CASES",
                title = "Real-world apps built on the ledger",
                features = LabFeature.entries.filter { it.mockup },
                accent = accent,
                network = network,
                open = useCasesOpen,
                onToggle = { useCasesOpen = !useCasesOpen },
                onOpenFeature = onOpenFeature,
            )
        }
    }
    }
}

/** Index of the stats heading: after the header, pulse and lane rows. */
private const val STATS_ROW = 3

private enum class MenuTarget { TOP, STATS, BASICS, USE_CASES }

/** The stats section's tabs, so the dashboard isn't one endless scroll. */
enum class StatsGroup(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Outlined.Speed),
    WHALES("Whales", Icons.Outlined.Waves),
    MARKETS("Markets", Icons.Outlined.CandlestickChart),
    ACTIVITY("Activity", Icons.Outlined.People),
    NETWORK("Network", Icons.Outlined.Hub),
}

@Composable
private fun GroupSwitcher(selected: StatsGroup, onSelect: (StatsGroup) -> Unit, accent: Color) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsGroup.entries.forEach { LabChip(it.label, it == selected, accent) { onSelect(it) } }
    }
}

/** The side menu: jump to a part of the dashboard, or straight into any lab. */
@Composable
private fun DashboardMenu(
    network: XrplNetwork,
    accent: Color,
    onJump: (MenuTarget) -> Unit,
    onGroup: (StatsGroup) -> Unit,
    onOpenFeature: (LabFeature) -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = LabColors.Surface, drawerContentColor = LabColors.TextPrimary) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = LabColors.TextPrimary)) { append("Ledger") }
                    withStyle(SpanStyle(brush = Brush.horizontalGradient(listOf(LabColors.Matrix, LabColors.Cyan)))) { append("Lab") }
                },
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(network.label, style = MaterialTheme.typography.bodySmall, color = accent, modifier = Modifier.padding(horizontal = 24.dp))
            MenuHeading("Dashboard")
            MenuItem("Live overview", Icons.Outlined.MonitorHeart, accent) { onJump(MenuTarget.TOP) }
            StatsGroup.entries.forEach { g -> MenuItem("Stats · ${g.label}", g.icon, accent) { onGroup(g) } }
            MenuHeading("The basics")
            MenuItem("All basics", Icons.Outlined.Apps, accent) { onJump(MenuTarget.BASICS) }
            LabFeature.entries.filter { !it.mockup }.forEach { f -> MenuItem(f.title, f.icon, f.color, enabled = f.usableOn(network)) { onOpenFeature(f) } }
            MenuHeading("Use cases")
            MenuItem("All use cases", Icons.Outlined.Apps, accent) { onJump(MenuTarget.USE_CASES) }
            LabFeature.entries.filter { it.mockup }.forEach { f -> MenuItem(f.title, f.icon, f.color, enabled = f.usableOn(network)) { onOpenFeature(f) } }
        }
    }
}

@Composable
private fun MenuHeading(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = LabColors.TextSecondary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun MenuItem(label: String, icon: ImageVector, tint: Color, enabled: Boolean = true, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, color = if (enabled) LabColors.TextPrimary else LabColors.TextMuted) },
        icon = { Icon(icon, contentDescription = null, tint = if (enabled) tint else LabColors.TextMuted) },
        selected = false,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 12.dp).height(44.dp),
    )
}

/** Fee, validators, amendments and the server: current state, so the same whichever period is picked. */
internal fun LazyListScope.liveNetworkItems(health: NetworkHealth) {
    item(key = "fee") {
        Panel(title = "Fee pressure", accent = pressureColor(health.fee?.level), trailing = {
            Text("open ledger", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }) {
            FeeGauge(health.fee, health.feeHistory, Modifier.fillMaxWidth())
        }
    }
    item(key = "validators") {
        Panel(title = "Validators", accent = LabColors.Cyan, trailing = {
            Text("live", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }) {
            ValidatorMap(health.validators, Modifier.fillMaxWidth())
        }
    }
    item(key = "amendments") {
        Panel(title = "Amendments", accent = LabColors.Violet, trailing = {
            Text("live", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }) {
            AmendmentList(health.validators, Modifier.fillMaxWidth())
        }
    }
    item(key = "server") {
        Panel(title = "Server & peers", accent = LabColors.Matrix, trailing = {
            Text("live", style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }) {
            ServerStatus(health.nodes, Modifier.fillMaxWidth())
        }
    }
}

/** Threshold choices for whale alerts, in XRP. */
private val WHALE_STEPS = listOf(1_000L to "1K", 10_000L to "10K", 50_000L to "50K", 100_000L to "100K", 1_000_000L to "1M")

@Composable
private fun WhaleThreshold(selected: Long, onSelect: (Long) -> Unit) {
    Column {
        Text("ALERT ME FROM", style = MaterialTheme.typography.labelSmall, color = LabColors.TextSecondary)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WHALE_STEPS.forEach { (xrp, label) ->
                LabChip("$label XRP", xrp == selected, LabColors.Gold) { onSelect(xrp) }
            }
        }
    }
}

/** DEX and token panels, shared by the live view and past periods. */
internal fun LazyListScope.marketItems(activity: ActivitySnapshot, estimated: Boolean, window: String) {
    item(key = "dex-$estimated") {
        Panel(title = "DEX & AMM pulse", accent = LabColors.Cyan, trailing = {
            Text(window, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }) {
            DexAmmPulse(activity, estimated, Modifier.fillMaxWidth())
        }
    }
    item(key = "tokens-$estimated") {
        Panel(title = "Tokens & NFTs", accent = LabColors.Magenta, trailing = {
            Text(window, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }) {
            TokenNftActivity(activity, estimated, Modifier.fillMaxWidth())
        }
    }
}

/** Busiest accounts, escrows and checks, tags and memos, and account growth. */
internal fun LazyListScope.activityItems(
    activity: ActivitySnapshot,
    estimated: Boolean,
    window: String,
    health: NetworkHealth,
    burnedDrops: Long?,
    burnedLabel: String,
    series: List<Long> = emptyList(),
    sliceLabel: String = "",
) {
    item(key = "busiest-$estimated") {
        Panel(title = "Busiest accounts", accent = LabColors.Violet, trailing = {
            Text(window, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }) {
            BusiestAccounts(activity, estimated, Modifier.fillMaxWidth())
        }
    }
    item(key = "escrows-$estimated") {
        Panel(title = "Escrows, checks & channels", accent = LabColors.Orange, trailing = {
            Text(window, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }) {
            EscrowsChecks(activity, estimated, Modifier.fillMaxWidth())
        }
    }
    item(key = "tags-$estimated") {
        Panel(title = "Destination tags & memos", accent = LabColors.Gold, trailing = {
            Text(window, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }) {
            TagsMemos(activity, estimated, Modifier.fillMaxWidth())
        }
    }
    item(key = "accounts-$estimated") {
        Panel(title = "Account growth", accent = LabColors.Matrix, trailing = {
            Text(window, style = MaterialTheme.typography.bodySmall, color = LabColors.TextMuted)
        }) {
            AccountGrowth(activity, estimated, health.supply, burnedDrops, burnedLabel, Modifier.fillMaxWidth(), series, sliceLabel)
        }
    }
}

/** A tappable section title with a chevron, shared by the stats and the lab sections. */
@Composable
private fun SectionHeader(eyebrow: String, title: String, accent: Color, open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 0f else -90f, label = "chevron")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = accent)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = LabColors.TextPrimary)
        }
        Icon(
            Icons.Outlined.ExpandMore,
            contentDescription = if (open) "Collapse" else "Expand",
            tint = LabColors.TextSecondary,
            modifier = Modifier.rotate(turn),
        )
    }
}

/** A titled group of lab cards that folds away when its header is tapped. */
@Composable
private fun LabSection(
    eyebrow: String,
    title: String,
    features: List<LabFeature>,
    accent: Color,
    network: XrplNetwork,
    open: Boolean,
    onToggle: () -> Unit,
    onOpenFeature: (LabFeature) -> Unit,
) {
    Column {
        SectionHeader("$eyebrow · ${features.size}", title, accent, open, onToggle)
        AnimatedVisibility(open) {
            Column {
                if (network.isRealMoney && features.any { !it.usableOn(network) }) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Anything that uses a wallet is off on Mainnet, since this is a demo app. Switch to Testnet to try it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LabColors.Gold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                LabsGrid(onOpenFeature, Modifier.fillMaxWidth(), features = features, network = network)
            }
        }
    }
}

@Composable
private fun Header(network: XrplNetwork, connection: ConnectionState, onSelectNetwork: (XrplNetwork) -> Unit, onMenu: () -> Unit) {
    val accent = networkColor(network)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(Icons.Outlined.Menu, contentDescription = "Menu", tint = LabColors.TextPrimary)
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = LabColors.TextPrimary)) { append("Ledger") }
                    withStyle(SpanStyle(brush = Brush.horizontalGradient(listOf(LabColors.Matrix, LabColors.Cyan)))) { append("Lab") }
                },
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f).offset(x = (-12).dp),
            )
            ConnectionBadge(connection, accent)
        }
        Text(
            "Live from the XRP Ledger ${network.label}",
            style = MaterialTheme.typography.bodySmall,
            color = LabColors.TextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        NetworkSwitcher(network, onSelectNetwork)
    }
}

@Composable
private fun ConnectionBadge(connection: ConnectionState, accent: Color) {
    val color = if (connection == ConnectionState.LIVE) accent else LabColors.Gold
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "live-dot",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(connection.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun StatGrid(s: DashboardSnapshot, accent: Color) {
    val tiles = listOf(
        Triple("Close time", if (s.avgCloseSeconds > 0) String.format(Locale.US, "%.2fs", s.avgCloseSeconds) else "—", "avg of last 10 ledgers"),
        Triple("Last ledger", "${s.lastLedgerTxns} txs", "in the latest close"),
        Triple("Base fee", if (s.feeBaseDrops > 0) "${s.feeBaseDrops} drops" else "—", Format.xrp(s.feeBaseDrops)),
        Triple("Reserve", if (s.reserveBaseDrops > 0) Format.xrp(s.reserveBaseDrops) else "—", "+${Format.xrp(s.reserveIncDrops)} per object"),
        Triple("XRP moved", Format.xrp(s.xrpMovedDrops), "payments, last 20 ledgers"),
        Triple("Success", String.format(Locale.US, "%.1f%%", s.successRate * 100), "tesSUCCESS rate"),
    )
    val colors = listOf(accent, LabColors.Cyan, LabColors.Violet, LabColors.Orange, LabColors.Gold, LabColors.Matrix)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.zip(colors).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (tile, color) ->
                    StatTile(tile.first, tile.second, tile.third, color, Modifier.weight(1f))
                }
            }
        }
    }
}
