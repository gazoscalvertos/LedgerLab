package com.ledgerlab.app.ui.dashboard

import com.ledgerlab.xrpl.PeriodSnapshot

/** What the dashboard knows about a past [StatsPeriod]. */
sealed interface HistoryState {
    data object Idle : HistoryState
    data class Loading(val done: Int, val total: Int) : HistoryState
    data class Ready(val stats: PeriodSnapshot) : HistoryState
    data class Failed(val message: String) : HistoryState
}
