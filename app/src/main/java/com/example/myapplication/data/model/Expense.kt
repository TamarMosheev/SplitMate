package com.example.myapplication.data.model

/** groups/{groupId}/expenses/{id}. People are referenced by Firebase UID. */
data class Expense(
    val id: String,
    val description: String,
    val amount: Double,
    val paidBy: String,
    val participantIds: List<String>,
    val splitType: String,
    val createdAtMillis: Long?
)
