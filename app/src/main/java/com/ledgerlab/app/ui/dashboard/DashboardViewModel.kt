package com.ledgerlab.app.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerlab.app.LedgerLabApp
import com.ledgerlab.app.data.NetworkHealthSource
import com.ledgerlab.app.data.XrplStreamClient
import com.ledgerlab.xrpl.DashboardSnapshot
import com.ledgerlab.xrpl.DashboardStats
import com.ledgerlab.xrpl.LedgerHistory
import com.ledgerlab.xrpl.PeriodSnapshot
import com.ledgerlab.xrpl.StatsPeriod
import com.ledgerlab.xrpl.StreamEvent
import com.ledgerlab.xrpl.StreamTx
import com.ledgerlab.xrpl.SupplyState
import com.ledgerlab.xrpl.ValidatorTracker
import com.ledgerlab.xrpl.XrplNetwork
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val networkStore = (app as LedgerLabApp).networkStore
    private val settings = (app as LedgerLabApp).settingsStore
    private val client = XrplStreamClient()
    private var streamJob: Job? = null
    private var stats: DashboardStats? = null

    val network: StateFlow<XrplNetwork> = networkStore.network

    private val _connection = MutableStateFlow(ConnectionState.CONNECTING)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _snapshot = MutableStateFlow(DashboardSnapshot())
    val snapshot: StateFlow<DashboardSnapshot> = _snapshot.asStateFlow()

    /** Every validated transaction, for the lane animation. Old ones are dropped if the UI falls behind. */
    private val _transactions = MutableSharedFlow<StreamTx>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val transactions: SharedFlow<StreamTx> = _transactions.asSharedFlow()

    private val _health = MutableStateFlow(NetworkHealth())
    val health: StateFlow<NetworkHealth> = _health.asStateFlow()

    val whaleThresholdXrp: StateFlow<Long> = settings.whaleThresholdXrp

    private val _period = MutableStateFlow(StatsPeriod.LIVE)
    val period: StateFlow<StatsPeriod> = _period.asStateFlow()

    private val _history = MutableStateFlow<HistoryState>(HistoryState.Idle)
    val history: StateFlow<HistoryState> = _history.asStateFlow()

    private var historyJob: Job? = null

    /** Past periods are slow to read, so each one is kept for a few minutes per network. */
    private val historyCache = mutableMapOf<Pair<XrplNetwork, StatsPeriod>, PeriodSnapshot>()
    private val cachedAt = mutableMapOf<Pair<XrplNetwork, StatsPeriod>, Long>()

    init {
        viewModelScope.launch {
            networkStore.network.collect {
                connect(it)
                loadHistory(it, _period.value)
            }
        }
        viewModelScope.launch {
            settings.whaleThresholdXrp.collect { xrp -> stats?.whaleThresholdDrops = xrp * DROPS_PER_XRP }
        }
    }

    fun setWhaleThreshold(xrp: Long) = settings.setWhaleThresholdXrp(xrp)

    fun selectNetwork(network: XrplNetwork) = networkStore.select(network)

    fun selectPeriod(period: StatsPeriod) {
        _period.value = period
        loadHistory(network.value, period)
    }

    fun retryHistory() {
        historyCache.remove(network.value to _period.value)
        loadHistory(network.value, _period.value)
    }

    private fun loadHistory(network: XrplNetwork, period: StatsPeriod) {
        historyJob?.cancel()
        if (period == StatsPeriod.LIVE) {
            _history.value = HistoryState.Idle
            return
        }
        val cached = historyCache[network to period]
        if (cached != null && System.currentTimeMillis() - cachedAt.getOrDefault(network to period, 0L) < CACHE_MS) {
            _history.value = HistoryState.Ready(cached)
            return
        }
        _history.value = HistoryState.Loading(0, 0)
        historyJob = viewModelScope.launch {
            _history.value = try {
                val stats = withContext(Dispatchers.IO) {
                    val context = coroutineContext
                    LedgerHistory(XrplRpc(network.historyRpcUrl)::call).load(period) { done, total ->
                        if (context.isActive) _history.value = HistoryState.Loading(done, total)
                    }
                }
                historyCache[network to period] = stats
                cachedAt[network to period] = System.currentTimeMillis()
                HistoryState.Ready(stats)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                HistoryState.Failed(e.message ?: "Couldn't read the ledger history")
            }
        }
    }

    private fun connect(network: XrplNetwork) {
        streamJob?.cancel()
        // Each network gets brand-new stats, so nothing from the previous ledger carries over.
        val stats = DashboardStats().apply { whaleThresholdDrops = settings.whaleThresholdXrp.value * DROPS_PER_XRP }
        this.stats = stats
        val validators = ValidatorTracker()
        _snapshot.value = DashboardSnapshot()
        _health.value = NetworkHealth()
        _connection.value = ConnectionState.CONNECTING
        // Everything below runs on the main thread, so the stats need no locking.
        streamJob = viewModelScope.launch {
            pollHealth(NetworkHealthSource(network), validators)
            var dirty = false
            launch {
                while (isActive) {
                    delay(SNAPSHOT_THROTTLE_MS)
                    if (dirty) {
                        dirty = false
                        _snapshot.value = stats.snapshot()
                    }
                }
            }
            client.events(network).collect { event ->
                when (event) {
                    StreamEvent.Connected -> _connection.value = ConnectionState.LIVE
                    is StreamEvent.Disconnected -> _connection.value = ConnectionState.RECONNECTING
                    is StreamEvent.Transaction -> _transactions.tryEmit(event.tx)
                    is StreamEvent.ValidationReceived -> {
                        validators.onValidation(event.validation)
                        return@collect
                    }
                    is StreamEvent.LedgerClosed -> validators.onValidatedLedger(event.ledger.index, event.ledger.hash)
                    else -> Unit
                }
                stats.onEvent(event)
                if (event is StreamEvent.LedgerClosed || event is StreamEvent.Subscribed) {
                    _snapshot.value = stats.snapshot()
                } else {
                    dirty = true
                }
            }
        }
    }

    /** Polls what the stream doesn't push. Runs inside the stream's job, so a network switch stops it. */
    private fun CoroutineScope.pollHealth(source: NetworkHealthSource, validators: ValidatorTracker) {
        launch {
            val history = ArrayDeque<Double>()
            while (isActive) {
                background { source.fee() }?.let { fee ->
                    history.addLast(fee.gauge)
                    while (history.size > FEE_HISTORY) history.removeFirst()
                    _health.update { it.copy(fee = fee, feeHistory = history.toList()) }
                }
                delay(FEE_POLL_MS)
            }
        }
        launch {
            var start: Long? = null
            while (isActive) {
                background { source.supply() }?.let { header ->
                    val first = start ?: header.totalCoinsDrops.also { start = it }
                    _health.update { it.copy(supply = SupplyState(header.index, header.totalCoinsDrops, first)) }
                }
                delay(SUPPLY_POLL_MS)
            }
        }
        launch {
            background { source.validatorList() }?.let(validators::setUnl)
            val looked = mutableSetOf<String>()
            while (isActive) {
                background { source.amendments() }?.let(validators::setAmendments)
                val pending = (validators.knownKeys() - looked).take(MANIFESTS_PER_ROUND)
                looked += pending
                background { pending.mapNotNull { key -> runCatching { source.manifest(key) }.getOrNull() } }
                    ?.forEach { (master, signing, domain) -> validators.setManifest(master, signing, domain) }
                delay(VALIDATOR_POLL_MS)
            }
        }
        launch {
            while (isActive) {
                background { source.nodes() }?.let { nodes -> _health.update { it.copy(nodes = nodes) } }
                delay(NODES_POLL_MS)
            }
        }
        launch {
            while (isActive) {
                _health.update { it.copy(validators = validators.snapshot()) }
                delay(VALIDATOR_SNAPSHOT_MS)
            }
        }
    }

    /** Runs a blocking call on the IO pool; null when it fails, so one bad poll just waits for the next. */
    private suspend fun <T> background(block: () -> T): T? = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SNAPSHOT_THROTTLE_MS = 400L
        const val CACHE_MS = 5 * 60_000L
        const val DROPS_PER_XRP = 1_000_000L
        const val FEE_POLL_MS = 4_000L
        const val FEE_HISTORY = 30
        const val SUPPLY_POLL_MS = 20_000L
        const val VALIDATOR_POLL_MS = 60_000L
        const val VALIDATOR_SNAPSHOT_MS = 2_000L
        const val MANIFESTS_PER_ROUND = 60
        const val NODES_POLL_MS = 30_000L
    }
}
