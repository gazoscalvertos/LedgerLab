package com.ledgerlab.app.ui.dashboard

import com.ledgerlab.xrpl.FeePressure
import com.ledgerlab.xrpl.SupplyState
import com.ledgerlab.xrpl.ValidatorSnapshot

/** The polled side of the dashboard: fee escalation, XRP supply and validators. */
data class NetworkHealth(
    val fee: FeePressure? = null,
    /** Recent gauge readings, oldest first. */
    val feeHistory: List<Double> = emptyList(),
    val supply: SupplyState? = null,
    val validators: ValidatorSnapshot = ValidatorSnapshot(),
)
