package com.example.myapplication.data.api

import java.math.BigDecimal

/**
 * GET /groups/{id}/expenses/{expenseId}/breakdown (contract §3.9a): what THIS expense alone makes each
 * participant owe the payer, using the server's own cent allocation. The payer is never an item.
 */
data class ExpenseBreakdownDto(
    val expenseId: String? = null,
    val paidBy: String? = null,
    val amount: BigDecimal? = null,
    val splitType: String? = null,
    val payerShare: BigDecimal? = null,
    val items: List<ExpenseBreakdownItemDto>? = null
)

data class ExpenseBreakdownItemDto(
    /** The person who owes (may no longer be a group member). */
    val userId: String? = null,
    val share: BigDecimal? = null,
    /** Always the payer. */
    val owesTo: String? = null,
    val amountOwed: BigDecimal? = null
)

/** One item of GET /groups/{id}/expenses (the stored expense document plus its id). */
data class ExpenseDto(
    val id: String? = null,
    val description: String? = null,
    val amount: BigDecimal? = null,
    val paidBy: String? = null,
    /** "equal" | "exact" */
    val splitType: String? = null,
    val participantIds: List<String>? = null,
    /** Only for splitType "exact": uid -> that person's share. */
    val exactAmounts: Map<String, BigDecimal>? = null,
    val createdBy: String? = null,
    val createdAt: String? = null
)
