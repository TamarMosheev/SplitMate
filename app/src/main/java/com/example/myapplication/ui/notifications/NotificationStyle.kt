package com.example.myapplication.ui.notifications

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.example.myapplication.R

/**
 * How a notification looks, decided ONLY by its `type` (as sent by the backend).
 *
 * This is the single place that maps a notification type to its icon and colour. Every notification row, for every
 * user and every group, goes through [of]; nothing here (or anywhere in the notification UI) looks at a user's name or
 * uid, a particular screen, or a particular notification.
 *
 * To support a new backend type, add ONE line to [byType].
 */
data class NotificationStyle(
    @DrawableRes val iconRes: Int,
    @ColorRes val tintRes: Int
) {
    companion object {
        const val DEBT_REMINDER = "debt_reminder"
        const val PAYMENT_CLAIM = "payment_claim"
        const val PAYMENT_CONFIRMED = "payment_confirmed"
        const val PAYMENT_CLAIM_REJECTED = "payment_claim_rejected"

        /** Shown for a type this app version does not know yet (never a wrong icon, never a crash). */
        val UNKNOWN = NotificationStyle(R.drawable.ic_notif_info, R.color.home_muted)

        private val byType: Map<String, NotificationStyle> = mapOf(
            DEBT_REMINDER to NotificationStyle(R.drawable.ic_notif_bell, R.color.home_primary),
            PAYMENT_CLAIM to NotificationStyle(R.drawable.ic_notif_payments, R.color.home_primary),
            PAYMENT_CONFIRMED to NotificationStyle(R.drawable.ic_notif_check_circle, R.color.home_positive),
            PAYMENT_CLAIM_REJECTED to NotificationStyle(R.drawable.ic_notif_cancel, R.color.home_negative)
        )

        fun of(type: String?): NotificationStyle = byType[type?.trim()?.lowercase()] ?: UNKNOWN
    }
}
