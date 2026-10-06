package com.example.myapplication.ui.group

import java.math.BigDecimal

data class MemberBalanceUi(
    val uid: String,
    /** Real name from users/{uid}.name; null if unreadable (never the raw uid). */
    val name: String?,
    val isMe: Boolean,
    /** Server balance in this group; null if the balances request failed. */
    val balance: BigDecimal?
)

/** What one expense row renders. */
data class ExpenseUi(
    val id: String,
    val description: String,
    val amount: BigDecimal,
    val payerLabel: String,
    val dateText: String?,
    /** "החלק שלך ₪100.00"; null when the data does not give the user's exact share. */
    val shareText: String?
)

data class GroupDetailsContent(
    val id: String,
    val name: String,
    val icon: String?,
    /** The group's creator uid as the backend returned it (decides the trash icon at render time). */
    val createdBy: String?,
    /** Contract rule: only the group's creator may delete it (`createdBy == current uid`). */
    val canDelete: Boolean,
    /** The signed-in user's balance in this group; null if the balances request failed. */
    val myBalance: BigDecimal?,
    val members: List<MemberBalanceUi>,
    val balanceError: String?,
    val expenses: List<ExpenseUi>,
    val expensesError: String?
)

sealed class GroupDetailsUiState {
    object Loading : GroupDetailsUiState()
    data class Success(val content: GroupDetailsContent) : GroupDetailsUiState()
    data class Error(val message: String) : GroupDetailsUiState()
}
