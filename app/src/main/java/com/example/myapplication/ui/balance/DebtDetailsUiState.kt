package com.example.myapplication.ui.balance

import java.math.BigDecimal

/** One row of "ממה זה מורכב": exactly what the server returned for this debt. */
data class BreakdownRowUi(
    val key: String,
    /** The group this expense belongs to when the debt spans several (direct debts); null = the debt's group. */
    val groupId: String? = null,
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
    CREDITOR_CLAIM_PENDING,

    /** A direct debt (no settlement): the buttons follow [DebtDetailsContent.direct], i.e. the server's flags. */
    DIRECT
}

/** The server's action flags for a direct debt (GET /debts/{receivable|payable}/{uid}); nothing is guessed locally. */
data class DirectActions(
    val debtorUid: String,
    val creditorUid: String,
    val canMarkPaid: Boolean,
    val canSendReminder: Boolean,
    val requiresGroupSelection: Boolean,
    /** Debtor: "שילמתי" allowed. */
    val canClaimPaid: Boolean,
    /** A claim is waiting for the creditor. */
    val claimPending: Boolean,
    val claimedAmount: BigDecimal?,
    /** Creditor: a claim is waiting for them. */
    val canConfirmClaim: Boolean,
    val groups: List<DirectActionGroup>
) {
    /** What the one main button does. The server's flags decide; a pending claim is confirmed rather than re-paid. */
    val primary: DirectPrimary?
        get() = when {
            canClaimPaid -> DirectPrimary.CLAIM
            canConfirmClaim -> DirectPrimary.CONFIRM
            canMarkPaid -> DirectPrimary.MARK_PAID
            else -> null
        }
}

enum class DirectPrimary { CLAIM, CONFIRM, MARK_PAID }

data class DirectActionGroup(
    val groupId: String,
    val name: String,
    val amount: BigDecimal,
    val canMarkPaid: Boolean,
    val canClaimPaid: Boolean,
    val canConfirmClaim: Boolean
)

data class DebtDetailsContent(
    /** Null for a direct debt (no single group, no settlement): the settlement actions are not available. */
    val groupId: String?,
    val settlementId: String?,
    val otherName: String?,
    /** The other person's saved avatar from the backend; null renders the default avatar. */
    val otherAvatar: com.example.myapplication.data.model.Avatar? = null,
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
    val breakdown: Breakdown,
    /** Set only for a direct debt. */
    val direct: DirectActions? = null
)

sealed class DebtDetailsUiState {
    object Loading : DebtDetailsUiState()
    data class Success(val content: DebtDetailsContent) : DebtDetailsUiState()
    data class Error(val message: String) : DebtDetailsUiState()
}
