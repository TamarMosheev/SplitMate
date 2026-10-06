package com.example.myapplication.ui.expense

import java.math.BigDecimal

data class ParticipantShareUi(
    val uid: String,
    /** Real name from users/{uid}.name; null if unreadable (never the raw uid). */
    val name: String?,
    val isMe: Boolean,
    /** The participant's exact share; null when the data does not state it exactly. */
    val share: BigDecimal?
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
    val participants: List<ParticipantShareUi>,
    val result: ExpenseResultUi?,
    /** Shown when some shares are not stated exactly by the data. */
    val sharesNote: String?
)

sealed class ExpenseDetailsUiState {
    object Loading : ExpenseDetailsUiState()
    data class Success(val content: ExpenseDetailsContent) : ExpenseDetailsUiState()
    data class Error(val message: String) : ExpenseDetailsUiState()
}
