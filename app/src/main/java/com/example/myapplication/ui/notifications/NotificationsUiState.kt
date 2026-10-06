package com.example.myapplication.ui.notifications

import java.math.BigDecimal

data class NotificationUi(
    val id: String,
    /** "debt_reminder" | "payment_claim" | "payment_confirmed" | "payment_claim_rejected" (others tolerated). */
    val type: String?,
    /** Real name from users/{senderUid}.name; null if unreadable (never the raw uid). */
    val senderName: String?,
    /** Display text built by the server. */
    val message: String,
    val amount: BigDecimal?,
    val groupId: String?,
    val groupName: String?,
    val settlementId: String?,
    val createdAtRaw: String?,
    val createdAtText: String?,
    val isRead: Boolean
)

sealed class NotificationsUiState {
    object Loading : NotificationsUiState()
    data class Success(val items: List<NotificationUi>) : NotificationsUiState()
    data class Error(val message: String) : NotificationsUiState()
}

/** One-off navigation request: open the debt a notification refers to. */
data class OpenDebt(val groupId: String, val settlementId: String)
