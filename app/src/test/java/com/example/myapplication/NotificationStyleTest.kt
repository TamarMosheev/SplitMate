package com.example.myapplication

import com.example.myapplication.ui.notifications.NotificationStyle
import com.example.myapplication.ui.notifications.NotificationUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The notification icon/colour is decided by the type alone: the same for every user, group and screen. */
class NotificationStyleTest {

    private val backendTypes = listOf(
        NotificationStyle.DEBT_REMINDER,
        NotificationStyle.PAYMENT_CLAIM,
        NotificationStyle.PAYMENT_CONFIRMED,
        NotificationStyle.PAYMENT_CLAIM_REJECTED
    )

    @Test
    fun `every type the backend sends has its own icon and none is the fallback`() {
        val icons = backendTypes.map { NotificationStyle.of(it).iconRes }
        assertEquals("each type needs a different icon", icons.size, icons.toSet().size)
        backendTypes.forEach { assertNotEquals("$it must not use the fallback", NotificationStyle.UNKNOWN, NotificationStyle.of(it)) }
    }

    @Test
    fun `the type strings are exactly the ones in the backend contract`() {
        assertEquals("debt_reminder", NotificationStyle.DEBT_REMINDER)
        assertEquals("payment_claim", NotificationStyle.PAYMENT_CLAIM)
        assertEquals("payment_confirmed", NotificationStyle.PAYMENT_CONFIRMED)
        assertEquals("payment_claim_rejected", NotificationStyle.PAYMENT_CLAIM_REJECTED)
    }

    @Test
    fun `unknown, missing or blank types get the neutral fallback`() {
        listOf(null, "", "   ", "group_added", "expense_added", "something_new").forEach {
            assertEquals("type=$it", NotificationStyle.UNKNOWN, NotificationStyle.of(it))
        }
    }

    @Test
    fun `matching ignores case and surrounding spaces`() {
        assertEquals(NotificationStyle.of("payment_claim"), NotificationStyle.of("  PAYMENT_CLAIM "))
    }

    @Test
    fun `different users and groups get exactly the same look for the same type`() {
        fun row(type: String, sender: String?, group: String) = NotificationUi(
            id = "n-$sender-$type", type = type, senderName = sender, message = "m", amount = null,
            groupId = group, groupName = group, settlementId = "s", createdAtRaw = null, createdAtText = null, isRead = false
        )
        // Different senders (including a name that is null) in different groups: only the TYPE may decide the look.
        val senders = listOf("אלעזר מושייב", "תמר", "משתמש אחר", null)
        for (type in backendTypes) {
            val styles = senders.mapIndexed { i, s -> NotificationStyle.of(row(type, s, "g$i").type) }.toSet()
            assertEquals("type $type must look the same for every user", 1, styles.size)
        }
    }

    @Test
    fun `colours carry meaning - confirmed is positive, rejected is negative`() {
        assertEquals(R.color.home_positive, NotificationStyle.of(NotificationStyle.PAYMENT_CONFIRMED).tintRes)
        assertEquals(R.color.home_negative, NotificationStyle.of(NotificationStyle.PAYMENT_CLAIM_REJECTED).tintRes)
        assertTrue(NotificationStyle.of(NotificationStyle.PAYMENT_CLAIM).tintRes != R.color.home_negative)
    }
}
