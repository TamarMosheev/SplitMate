package com.example.myapplication.ui.balance

import java.math.BigDecimal
import java.math.RoundingMode

/** One open settlement involving the signed-in user, exactly as the server returned it. */
data class DebtUi(
    val key: String,
    /** Stable identifiers handed to the Debt Details screen (never the displayed text). */
    val groupId: String,
    val settlementId: String,
    val otherUid: String,
    /** Real display name from users/{uid}.name; null if it could not be read (never the raw uid). */
    val otherName: String?,
    val amount: BigDecimal,
    val groupName: String,
    /** True: the other person owes the signed-in user (`to == me`). False: the signed-in user owes them. */
    val owedToMe: Boolean,
    val groupIcon: String? = null,
    /** The debtor already reported payment and the creditor has not confirmed yet (server `hasCurrentClaim`). */
    val claimPending: Boolean = false
)

data class GroupBalanceUi(
    val id: String,
    val name: String,
    val icon: String?,
    /** Null when GET /groups/{id}/balances failed for this group. */
    val balance: BigDecimal?,
    /** The group's creator uid as the backend returned it (decides the trash icon at bind time). */
    val createdBy: String? = null,
    /** Contract rule: only the group's creator may delete it (`createdBy == current uid`). */
    val canDelete: Boolean = false,
    /** True while DELETE /groups/{id} is running for this group. */
    val isDeleting: Boolean = false
)

data class MyBalanceContent(
    /** Null when there are groups but every balance request failed. */
    val totalBalance: BigDecimal?,
    val groupCount: Int,
    /** Sum of open settlements where the signed-in user is the creditor ("חייבים לך"). */
    val owedToMe: BigDecimal,
    /** Sum of open settlements where the signed-in user is the debtor ("את חייבת"). */
    val owedByMe: BigDecimal,
    val debts: List<DebtUi>,
    val groups: List<GroupBalanceUi>,
    /** Number of groups whose balance or settlement request failed. */
    val failedGroups: Int
)

sealed class MyBalanceUiState {
    object Loading : MyBalanceUiState()
    data class Success(val content: MyBalanceContent) : MyBalanceUiState()
    data class Error(val message: String) : MyBalanceUiState()
}

/** "₪300.00" (absolute value) */
fun formatMoney(amount: BigDecimal): String =
    "₪" + amount.abs().setScale(2, RoundingMode.HALF_UP).toPlainString()

/** "+₪300.00" / "-₪80.00" */
fun formatSignedMoney(amount: BigDecimal): String =
    (if (amount.signum() < 0) "-" else "+") + formatMoney(amount)

fun isSettled(amount: BigDecimal) = amount.signum() == 0
