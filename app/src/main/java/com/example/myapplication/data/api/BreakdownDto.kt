package com.example.myapplication.data.api

import java.math.BigDecimal

/** GET /groups/{id}/settlements/{sid}/breakdown (contract §3.10). `items` is empty when not available. */
data class BreakdownResponse(
    val settlementId: String? = null,
    val groupId: String? = null,
    val from: String? = null,
    val to: String? = null,
    val amount: BigDecimal? = null,
    val status: String? = null,
    val breakdownAvailable: Boolean? = null,
    /** English informational text; the app shows its own Hebrew message. */
    val reason: String? = null,
    /** "exact" | "netted" | "unavailable" */
    val breakdownType: String? = null,
    /** Filled only for "exact"; adds up to `amount`. */
    val items: List<BreakdownItemDto>? = null,
    /** Filled only for "netted": context, NOT a decomposition of `amount`. */
    val contributingExpenses: List<ContributingExpenseDto>? = null
)

/** An expense that changed the balance of the debtor or the creditor of a netted settlement. */
data class ContributingExpenseDto(
    val expenseId: String? = null,
    val description: String? = null,
    val expenseAmount: BigDecimal? = null,
    val payerUid: String? = null,
    val participantUids: List<String>? = null,
    /** Effect on the debtor's balance (same sign as /balances). */
    val fromContribution: BigDecimal? = null,
    /** Effect on the creditor's balance. */
    val toContribution: BigDecimal? = null,
    val createdAt: String? = null
)

data class BreakdownItemDto(
    /** "expense" | "payment" */
    val type: String? = null,
    val expenseId: String? = null,
    val description: String? = null,
    val expenseAmount: BigDecimal? = null,
    val payerUid: String? = null,
    val settlementId: String? = null,
    /** This record's part of the debt, signed from the debtor's point of view (negative = offset). */
    val relevantAmount: BigDecimal? = null,
    val createdAt: String? = null
)
