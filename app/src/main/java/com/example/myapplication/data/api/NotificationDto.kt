package com.example.myapplication.data.api

import java.math.BigDecimal

/** A notification document (GET /notifications item, reminder response, mark-read response). */
data class NotificationDto(
    val id: String? = null,
    val type: String? = null,
    val recipientUid: String? = null,
    val senderUid: String? = null,
    val groupId: String? = null,
    val settlementId: String? = null,
    val amount: BigDecimal? = null,
    val message: String? = null,
    val isRead: Boolean? = null,
    val createdAt: String? = null,
    val readAt: String? = null
)

data class UnreadCountResponse(val count: Int? = null)

/** DELETE /notifications/{id} -> 200 {"success": true, "notificationId": "..."} */
data class NotificationDeletedResponse(
    val success: Boolean? = null,
    val notificationId: String? = null
)
