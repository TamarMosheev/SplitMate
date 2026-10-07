package com.example.myapplication.ui.balance

import android.content.Context
import android.content.Intent
import com.example.myapplication.DebtDetailsActivity
import com.example.myapplication.DebtListActivity
import com.example.myapplication.GroupDetailsActivity
import com.example.myapplication.MyBalanceActivity
import com.example.myapplication.ui.group.GroupDetailsViewModel

/**
 * The one place that builds Intents for the balance flow, so every screen (Home, Group Details,
 * My Balance, Notifications, debt lists) opens the same canonical Activities with only real ids.
 */
object BalanceNavigation {

    fun openMyBalance(context: Context) =
        context.startActivity(Intent(context, MyBalanceActivity::class.java))

    /** [owedToMe] true = "חייבים לך" (settlement.to == me); false = "את חייבת" (settlement.from == me). */
    fun openDebtList(context: Context, owedToMe: Boolean) =
        context.startActivity(
            Intent(context, DebtListActivity::class.java).putExtra(DebtListActivity.EXTRA_OWED_TO_ME, owedToMe)
        )

    /** Only ids travel; Debt Details loads the settlement fresh from the backend. */
    fun openDebtDetails(context: Context, groupId: String, settlementId: String) =
        context.startActivity(
            Intent(context, DebtDetailsActivity::class.java)
                .putExtra(DebtDetailsViewModel.EXTRA_GROUP_ID, groupId)
                .putExtra(DebtDetailsViewModel.EXTRA_SETTLEMENT_ID, settlementId)
        )

    fun openGroupDetails(context: Context, groupId: String) =
        context.startActivity(
            Intent(context, GroupDetailsActivity::class.java)
                .putExtra(GroupDetailsViewModel.EXTRA_GROUP_ID, groupId)
        )
}
