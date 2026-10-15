package com.example.myapplication.ui.expense

import java.math.BigDecimal

data class ParticipantShareUi(
    val uid: String,
    /** Real name from users/{uid}.name; null if unreadable (never the raw uid). */
    val name: String?,
    val isMe: Boolean,
    /** The participant's exact share; null when the data does not state it exactly. */
    val share: BigDecimal?,
    /** The participant's saved avatar from the backend; null renders the default avatar. */
    val avatar: com.example.myapplication.data.model.Avatar? = null
)

/** One real obligation of this expense: [debtorName] owes [creditorName] (the payer) [amount]. */
data class ExpenseDebtUi(
    val debtorUid: String,
    val debtorName: String?,
    val debtorIsMe: Boolean,
    val debtorAvatar: com.example.myapplication.data.model.Avatar?,
    val creditorUid: String,
    val creditorName: String?,
    val creditorIsMe: Boolean,
    val creditorAvatar: com.example.myapplication.data.model.Avatar?,
    /** The server's exact amount for this expense (never recomputed on the device). */
    val amount: BigDecimal
)

/** The signed-in user's result in this one expense. */
sealed class ExpenseResultUi {
    /** You paid: the others owe you [amount] for this expense. */
    data class Receive(val amount: BigDecimal) : ExpenseResultUi()

    /** Someone else paid: your share of this expense is [amount]. */
    data class Owe(val amount: BigDecimal, val payerName: String?) : ExpenseResultUi()
}

data class ExpenseDetailsContent(
    val description: String,
    val amount: BigDecimal,
    val groupName: String,
    val groupIcon: String?,
    val dateText: String?,
    /** "חלוקה שווה" / "סכומים מדויקים" */
    val splitLabel: String?,
    val payerName: String?,
    val payerIsMe: Boolean,
    val payerAvatar: com.example.myapplication.data.model.Avatar? = null,
    val participants: List<ParticipantShareUi>,
    val result: ExpenseResultUi?,
    /** Shown when some shares are not stated exactly by the data. */
    val sharesNote: String?,
    /** "מי חייב למי": from the breakdown endpoint; empty = no obligations (e.g. the payer paid only for themself). */
    val debts: List<ExpenseDebtUi> = emptyList(),
    /** Set when the breakdown request failed; the rest of the screen stays usable. */
    val debtsError: String? = null
)

sealed class ExpenseDetailsUiState {
    object Loading : ExpenseDetailsUiState()
    data class Success(val content: ExpenseDetailsContent) : ExpenseDetailsUiState()
    data class Error(val message: String) : ExpenseDetailsUiState()
}
