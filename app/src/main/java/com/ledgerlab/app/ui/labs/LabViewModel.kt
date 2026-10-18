package com.ledgerlab.app.ui.labs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerlab.app.LedgerLabApp
import com.ledgerlab.app.data.StoredWallet
import com.ledgerlab.xrpl.XrplNetwork
import com.ledgerlab.xrpl.labs.Asset
import com.ledgerlab.xrpl.labs.LabQueries
import com.ledgerlab.xrpl.labs.TxOutcome
import com.ledgerlab.xrpl.labs.TxPipeline
import com.ledgerlab.xrpl.wallet.Autofill
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xrpl.xrpl4j.crypto.keys.PublicKey
import org.xrpl.xrpl4j.model.transactions.Address
import org.xrpl.xrpl4j.model.transactions.Transaction

sealed interface TxStatus {
    data object Idle : TxStatus
    data class Working(val label: String, val step: String) : TxStatus
    data class Done(val label: String, val outcome: TxOutcome) : TxStatus
    data class Failed(val label: String, val message: String) : TxStatus
    /** Finished work that wasn't a transaction, such as signing an off-ledger claim. */
    data class Note(val label: String, val message: String) : TxStatus
}

/**
 * Shared plumbing for every lab: the current network and wallet, a transaction runner with
 * progress, and a reload hook that fires whenever either changes.
 */
abstract class LabViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LedgerLabApp
    protected val walletStore = graph.walletStore
    protected val settingsStore = graph.settingsStore
    private val networkStore = graph.networkStore

    val network: StateFlow<XrplNetwork> = networkStore.network
    val wallets: StateFlow<List<StoredWallet>> = walletStore.wallets
    val selected: StateFlow<StoredWallet?> = combine(walletStore.wallets, walletStore.selectedId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _tx = MutableStateFlow<TxStatus>(TxStatus.Idle)
    val tx: StateFlow<TxStatus> = _tx.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _tokens = MutableStateFlow<List<Asset.Token>>(emptyList())
    /** Tokens any saved wallet issues or holds on this network, for pickers. Filled when [usesTokens]. */
    val tokens: StateFlow<List<Asset.Token>> = _tokens.asStateFlow()

    /** Labs that offer token pickers set this so [reload] also discovers the wallets' tokens. */
    protected open val usesTokens: Boolean = false

    private var reloadJob: Job? = null

    init {
        // Dispatched (not immediate) so subclasses finish constructing before the first load().
        viewModelScope.launch(Dispatchers.Main) {
            combine(network, selected) { n, w -> n to w?.address }.distinctUntilChanged().collect {
                _tx.value = TxStatus.Idle
                reload()
            }
        }
    }

    fun selectNetwork(network: XrplNetwork) = networkStore.select(network)

    fun selectWallet(id: String) = walletStore.select(id)

    fun clearTx() {
        _tx.value = TxStatus.Idle
    }

    protected fun rpc() = XrplRpc(network.value.rpcUrl)

    protected fun queries() = LabQueries(rpc())

    /** Re-reads the lab's on-ledger state. */
    fun reload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            try {
                _error.value = null
                withContext(Dispatchers.IO) {
                    if (usesTokens) _tokens.value = discoverTokens()
                    load()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Couldn't load from ${network.value.label}: ${e.message}"
            }
        }
    }

    private fun discoverTokens(): List<Asset.Token> {
        val q = queries()
        return wallets.value.flatMap { w -> runCatching { q.trustLines(w.address) }.getOrDefault(emptyList()) }
            .map { it.token }
            .distinct()
    }

    /** Runs on a background thread. Implementations publish into their own state flows. */
    protected abstract suspend fun load()

    protected fun runTx(
        label: String,
        wallet: StoredWallet? = selected.value,
        before: (step: (String) -> Unit) -> Unit = {},
        build: (Address, PublicKey, Autofill) -> Transaction,
    ) {
        if (wallet == null) {
            _tx.value = TxStatus.Failed(label, "Create or pick a wallet first.")
            return
        }
        if (_tx.value is TxStatus.Working) return
        viewModelScope.launch {
            _tx.value = TxStatus.Working(label, "Starting")
            try {
                val outcome = withContext(Dispatchers.IO) {
                    before { step -> _tx.value = TxStatus.Working(label, step) }
                    TxPipeline.run(rpc(), walletStore.secretOf(wallet), wallet.address, onStep = { step ->
                        _tx.value = TxStatus.Working(label, step)
                    }, build = build)
                }
                _tx.value = TxStatus.Done(label, outcome)
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _tx.value = TxStatus.Failed(label, e.message ?: "Something went wrong.")
            }
        }
    }

    protected fun fail(label: String, message: String) {
        _tx.value = TxStatus.Failed(label, message)
    }

    /** Runs blocking work that isn't a transaction (an off-ledger signature, a lookup) with the same status card. */
    protected fun runWork(label: String, work: (step: (String) -> Unit) -> String) {
        if (_tx.value is TxStatus.Working) return
        viewModelScope.launch {
            _tx.value = TxStatus.Working(label, "Starting")
            try {
                val message = withContext(Dispatchers.IO) { work { step -> _tx.value = TxStatus.Working(label, step) } }
                _tx.value = TxStatus.Note(label, message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _tx.value = TxStatus.Failed(label, e.message ?: "Something went wrong.")
            }
        }
    }
}
