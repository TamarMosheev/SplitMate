package com.example.myapplication.data.api

import java.math.BigDecimal

/** GET /debts/receivable and GET /debts/payable (contract §3.9b). Direct debts from expenses, not settlements. */
data class DirectDebtsResponse(
    /** Sum of all `amount`s, exact cents. */
    val total: BigDecimal? = null,
    val debts: List<DirectDebtDto>? = null
)

data class DirectDebtDto(
    /** The other person: the debtor (receivable) or the person owed (payable). May no longer be a member. */
    val userId: String? = null,
    val displayName: String? = null,
    val amount: BigDecimal? = null,
    /** The same amount split by group; sums to `amount`. */
    val groups: List<DirectDebtGroupDto>? = null
)

data class DirectDebtGroupDto(
    val groupId: String? = null,
    val groupName: String? = null,
    val amount: BigDecimal? = null
)

/** GET /debts/receivable/{uid} and /debts/payable/{uid}: one row of those lists, opened (contract §3.9b). */
data class DirectDebtDetailDto(
    val userId: String? = null,
    val displayName: String? = null,
    val owedToMe: Boolean? = null,
    val amount: BigDecimal? = null,
    val groups: List<DirectDebtDetailGroupDto>? = null,
    val debtorUid: String? = null,
    val creditorUid: String? = null,
    /** True only when the signed-in user is the creditor. Drives "סמן כשולם". */
    val canMarkPaid: Boolean? = null,
    /** Creditor only, while the debtor is still in a group with this debt. Drives "שלח תזכורת". */
    val canSendReminder: Boolean? = null,
    /** Several groups: paying must send groupId or allGroups=true. */
    val requiresGroupSelection: Boolean? = null,
    /** Debtor: "שילמתי" is allowed (some group has no pending claim). */
    val canClaimPaid: Boolean? = null,
    /** Some group has a pending claim: show "ממתין לאישור". */
    val claimPending: Boolean? = null,
    /** Sum of the pending claims. */
    val claimedAmount: BigDecimal? = null,
    /** Creditor: confirm / reject the pending claim(s). */
    val canConfirmClaim: Boolean? = null,
    val canRejectClaim: Boolean? = null
)

data class DirectDebtDetailGroupDto(
    val groupId: String? = null,
    val groupName: String? = null,
    val amount: BigDecimal? = null,
    /** False when the items do not add up to `amount` (a payment was clamped). */
    val itemsExact: Boolean? = null,
    val items: List<DirectDebtItemDto>? = null,
    val canMarkPaid: Boolean? = null,
    val canSendReminder: Boolean? = null,
    val canClaimPaid: Boolean? = null,
    val claimPending: Boolean? = null,
    val canConfirmClaim: Boolean? = null
)

data class DirectDebtItemDto(
    val type: String? = null,
    val expenseId: String? = null,
    val description: String? = null,
    val expenseAmount: BigDecimal? = null,
    val settlementId: String? = null,
    /** Signed from the debtor's side; negative = a payment that reduced the debt. */
    val relevantAmount: BigDecimal? = null
)

/** PATCH /debts/direct/{debtor}/{creditor}/paid -> 200. [remainingAmount] is what the debtor still owes the creditor in all groups. */
data class DirectDebtPaidDto(
    val debtorUid: String? = null,
    val creditorUid: String? = null,
    val paidAmount: BigDecimal? = null,
    val remainingAmount: BigDecimal? = null
)

/** POST /debts/direct/{debtor}/{creditor}/payment-claim -> 201. Every claim starts as "pending". */
data class DirectDebtClaimDto(
    val debtorUid: String? = null,
    val creditorUid: String? = null,
    val claimedAmount: BigDecimal? = null,
    val claims: List<DirectDebtClaimItemDto>? = null
)

data class DirectDebtClaimItemDto(
    val claimId: String? = null,
    val groupId: String? = null,
    val groupName: String? = null,
    val amount: BigDecimal? = null,
    val status: String? = null
)
