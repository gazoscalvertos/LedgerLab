package com.ledgerlab.app.ui.wallet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ledgerlab.app.LedgerLabApp
import com.ledgerlab.app.data.StoredWallet
import com.ledgerlab.xrpl.StreamTx
import com.ledgerlab.xrpl.XrplNetwork
import com.ledgerlab.xrpl.wallet.AccountState
import com.ledgerlab.xrpl.wallet.Faucet
import com.ledgerlab.xrpl.wallet.KeyAlgorithm
import com.ledgerlab.xrpl.wallet.Reserves
import com.ledgerlab.xrpl.wallet.Transactions
import com.ledgerlab.xrpl.wallet.WalletKeys
import com.ledgerlab.xrpl.wallet.XrplRpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SendState {
    data object Idle : SendState
    data class Working(val step: String) : SendState
    data class Done(val hash: String, val result: String, val success: Boolean) : SendState
    data class Failed(val message: String) : SendState
}

data class WalletUiState(
    val network: XrplNetwork = XrplNetwork.DEFAULT,
    val wallets: List<StoredWallet> = emptyList(),
    val selected: StoredWallet? = null,
    /** Null with [loaded] true means the account isn't activated on this network yet. */
    val account: AccountState? = null,
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val reserves: Reserves? = null,
    val history: List<StreamTx> = emptyList(),
    val funding: Boolean = false,
    val send: SendState = SendState.Idle,
    val notice: String? = null,
) {
    val availableDrops: Long
        get() {
            val acct = account ?: return 0
            val r = reserves ?: return acct.balanceDrops
            return (acct.balanceDrops - r.baseDrops - r.incrementDrops * acct.ownerCount).coerceAtLeast(0)
        }
}

class WalletViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LedgerLabApp
    private val networkStore = graph.networkStore
    private val walletStore = graph.walletStore
    private val faucet = Faucet()

    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            combine(networkStore.network, walletStore.wallets, walletStore.selectedId) { network, wallets, id ->
                Triple(network, wallets, wallets.firstOrNull { it.id == id } ?: wallets.firstOrNull())
            }.collect { (network, wallets, selected) ->
                val changed = network != _state.value.network || selected?.address != _state.value.selected?.address
                _state.update {
                    if (changed) {
                        WalletUiState(network = network, wallets = wallets, selected = selected)
                    } else {
                        it.copy(wallets = wallets, selected = selected)
                    }
                }
                if (changed) refresh()
            }
        }
    }

    fun selectNetwork(network: XrplNetwork) = networkStore.select(network)

    fun selectWallet(id: String) = walletStore.select(id)

    fun create(label: String, algorithm: KeyAlgorithm) {
        val wallet = walletStore.create(label, algorithm)
        notice("Created ${wallet.label}. Its secret is encrypted on this phone.")
    }

    /** Returns an error message, or null on success. */
    fun import(label: String, secret: String): String? = try {
        val wallet = walletStore.import(label, secret)
        notice("Imported ${wallet.label}.")
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "Couldn't import that secret."
    }

    fun delete(wallet: StoredWallet) {
        walletStore.delete(wallet.id)
        notice("Removed ${wallet.label} from this phone.")
    }

    fun revealSecret(wallet: StoredWallet): String = walletStore.secretOf(wallet)

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun resetSend() = _state.update { it.copy(send = SendState.Idle) }

    fun refresh() {
        val s = _state.value
        val wallet = s.selected ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                val rpc = XrplRpc(s.network.rpcUrl)
                val (account, history, reserves) = withContext(Dispatchers.IO) {
                    val account = rpc.accountInfo(wallet.address)
                    val history = if (account != null) rpc.accountTx(wallet.address) else emptyList()
                    Triple(account, history, runCatching { rpc.reserves() }.getOrNull())
                }
                _state.update {
                    it.copy(account = account, history = history, reserves = reserves ?: it.reserves, loaded = true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                notice("Couldn't reach ${s.network.label}: ${e.message}")
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun fund() {
        val s = _state.value
        val wallet = s.selected ?: return
        viewModelScope.launch {
            _state.update { it.copy(funding = true) }
            try {
                val before = s.account?.balanceDrops ?: 0L
                val amount = withContext(Dispatchers.IO) { faucet.fund(s.network, wallet.address) }
                // The faucet returns before its payment validates; wait for the balance to move.
                val rpc = XrplRpc(s.network.rpcUrl)
                for (attempt in 0 until 12) {
                    val account = withContext(Dispatchers.IO) { runCatching { rpc.accountInfo(wallet.address) }.getOrNull() }
                    if (account != null && account.balanceDrops > before) break
                    delay(1_500)
                }
                notice(amount?.let { "The faucet sent ${it.toLong()} test XRP." } ?: "Funded from the faucet.")
                refresh()
            } catch (e: Exception) {
                notice(e.message ?: "The faucet didn't respond.")
            } finally {
                _state.update { it.copy(funding = false) }
            }
        }
    }

    /** Returns a validation error, or null if the payment was started. */
    fun send(destination: String, amount: String, tag: String): String? {
        val s = _state.value
        val wallet = s.selected ?: return "Pick a wallet first."
        val dest = destination.trim()
        if (!WalletKeys.isValidAddress(dest)) return "That isn't a valid XRPL address."
        if (dest == wallet.address) return "That's this wallet's own address."
        val drops = Transactions.parseXrp(amount) ?: return "Enter an XRP amount, up to 6 decimals."
        val destinationTag = if (tag.isBlank()) null else tag.trim().toLongOrNull()?.takeIf { it in 0..4_294_967_295L }
            ?: return "Destination tags are whole numbers up to 4294967295."
        if (s.account == null) return "This account isn't activated yet. Fund it first."
        if (drops > s.availableDrops) return "That's more than the spendable balance (the reserve stays locked)."

        viewModelScope.launch {
            try {
                val rpc = XrplRpc(s.network.rpcUrl)
                _state.update { it.copy(send = SendState.Working("Filling in sequence and fee")) }
                val fill = withContext(Dispatchers.IO) { rpc.autofill(wallet.address) }
                _state.update { it.copy(send = SendState.Working("Signing on this phone")) }
                val signed = withContext(Dispatchers.Default) {
                    WalletKeys.sign(walletStore.secretOf(wallet)) { from, key ->
                        Transactions.xrpPayment(from, key, dest, drops, destinationTag, fill)
                    }
                }
                _state.update { it.copy(send = SendState.Working("Submitting to ${s.network.label}")) }
                val submitted = withContext(Dispatchers.IO) { rpc.submit(signed.blob) }
                if (!submitted.accepted) {
                    _state.update { it.copy(send = SendState.Failed("${submitted.engineResult}: ${submitted.message}")) }
                    return@launch
                }
                _state.update { it.copy(send = SendState.Working("Waiting for validation (${submitted.engineResult})")) }
                val final = withContext(Dispatchers.IO) { rpc.waitForValidation(signed.hash, fill.lastLedgerSequence) }
                _state.update {
                    it.copy(send = SendState.Done(signed.hash, final.result, final.validated && final.result == "tesSUCCESS"))
                }
                refresh()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(send = SendState.Failed(e.message ?: "Something went wrong.")) }
            }
        }
        return null
    }

    private fun notice(message: String) = _state.update { it.copy(notice = message) }
}
