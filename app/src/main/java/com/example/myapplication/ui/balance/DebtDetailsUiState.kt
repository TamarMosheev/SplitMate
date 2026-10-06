package com.example.myapplication.ui.balance

import java.math.BigDecimal

/** One row of "ממה זה מורכב": exactly what the server returned for this debt. */
data class BreakdownRowUi(
    val key: String,
    /** Real expense id for expense rows (opens Expense Details); null for payment rows. */
    val expenseId: String? = null,
    val title: String,
    /** "מתוך ₪350.00" (whole expense) for expense rows; null for payments. */
    val subtitle: String?,
    /** Server's relevantAmount, signed from the debtor's side (negative = offsets the debt). */
    val amount: BigDecimal
)

sealed class Breakdown {
    /** breakdownType "exact": the rows add up to the debt. */
    data class Rows(val rows: List<BreakdownRowUi>) : Breakdown()

    /**
     * breakdownType "netted": [rows] are expenses that affected the balances of the two people.
     * They are context only and deliberately not forced to add up to the debt.
     */
    data class Netted(val explanation: String, val rows: List<BreakdownRowUi>) : Breakdown()

    /** breakdownType "unavailable": nothing was recorded for this debt. */
    data class Unavailable(val message: String) : Breakdown()

    /** The breakdown request itself failed (network, 401/403...). */
    data class Error(val message: String) : Breakdown()
}

/**
 * Which actions the signed-in user has on this debt, derived from the real settlement
 * (from = debtor, to = creditor, status, paymentClaimStatus). The Activity only renders it.
 */
enum class DebtActionMode {
    /** Paid, or the user is not a party: no actions. */
    NONE,

    /** Debtor, no current claim: "שלחתי תשלום". */
    DEBTOR_CAN_CLAIM,

    /** Debtor, claim already sent: waiting for the creditor. */
    DEBTOR_CLAIM_PENDING,

    /** Creditor, no current claim: "סמן כשולם" + reminder. */
    CREDITOR_OPEN,

    /** Creditor, debtor reported paying: "אשר קבלת תשלום". */
    CREDITOR_CLAIM_PENDING
}

data class DebtDetailsContent(
    val groupId: String,
    val settlementId: String,
    val otherName: String?,
    /** True: the other person owes the signed-in user. */
    val owedToMe: Boolean,
    val amount: BigDecimal,
    val isPaid: Boolean,
    /** Formatted in the device zone; null while open or if the server sent none. */
    val paidAtText: String?,
    /** "את" if the signed-in user marked it, otherwise the real name if it could be read. */
    val markedByName: String?,
    val groupName: String,
    val groupIcon: String?,
    val mode: DebtActionMode,
    /** The creditor rejected the debtor's last claim (debtor may report again). */
    val claimRejected: Boolean,
    val breakdown: Breakdown
)

sealed class DebtDetailsUiState {
    object Loading : DebtDetailsUiState()
    data class Success(val content: DebtDetailsContent) : DebtDetailsUiState()
    data class Error(val message: String) : DebtDetailsUiState()
}
