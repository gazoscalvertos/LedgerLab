package com.ledgerlab.app.ui.dashboard

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerlab.app.data.XrplStreamClient
import com.ledgerlab.xrpl.DashboardSnapshot
import com.ledgerlab.xrpl.DashboardStats
import com.ledgerlab.xrpl.StreamEvent
import com.ledgerlab.xrpl.StreamTx
import com.ledgerlab.xrpl.XrplNetwork
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ledgerlab", Context.MODE_PRIVATE)
    private val client = XrplStreamClient()
    private val stats = DashboardStats()
    private var streamJob: Job? = null

    private val _network = MutableStateFlow(XrplNetwork.fromName(prefs.getString(KEY_NETWORK, null)))
    val network: StateFlow<XrplNetwork> = _network.asStateFlow()

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

    init {
        connect(_network.value)
    }

    fun selectNetwork(network: XrplNetwork) {
        if (network == _network.value) return
        _network.value = network
        prefs.edit().putString(KEY_NETWORK, network.name).apply()
        connect(network)
    }

    private fun connect(network: XrplNetwork) {
        streamJob?.cancel()
        stats.reset()
        _snapshot.value = DashboardSnapshot()
        _connection.value = ConnectionState.CONNECTING
        // Everything below runs on the main thread, so the stats need no locking.
        streamJob = viewModelScope.launch {
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

    private companion object {
        const val KEY_NETWORK = "network"
        const val SNAPSHOT_THROTTLE_MS = 400L
    }
}
