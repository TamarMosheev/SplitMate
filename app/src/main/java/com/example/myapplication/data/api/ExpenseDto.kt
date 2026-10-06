package com.example.myapplication.data.api

import java.math.BigDecimal

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
