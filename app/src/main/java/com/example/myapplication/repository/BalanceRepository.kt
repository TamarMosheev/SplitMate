package com.example.myapplication.repository

import android.util.Log
import com.example.myapplication.data.api.ApiAction
import com.example.myapplication.data.api.ApiErrorMapper
import com.example.myapplication.data.api.ApiService
import com.example.myapplication.data.api.MarkPaidRequest
import com.example.myapplication.data.api.RetrofitClient
import com.example.myapplication.data.api.SettlementDto
import com.example.myapplication.data.api.SettlementResponse
import com.example.myapplication.data.model.Group
import com.example.myapplication.data.api.BreakdownResponse
import com.example.myapplication.data.api.ExpenseDto
import com.example.myapplication.ui.expense.ExpenseDetailsContent
import com.example.myapplication.ui.expense.ExpenseResultUi
import com.example.myapplication.ui.expense.ParticipantShareUi
import com.example.myapplication.ui.group.ExpenseUi
import com.example.myapplication.ui.group.canDeleteGroup
import com.example.myapplication.ui.group.logGroupDeleteDebug
import com.example.myapplication.ui.group.GroupDetailsContent
import com.example.myapplication.ui.group.MemberBalanceUi
import com.example.myapplication.utils.formatDateOnly
import com.example.myapplication.utils.parseTimestamp
import com.example.myapplication.ui.balance.Breakdown
import com.example.myapplication.ui.balance.DebtActionMode
import com.example.myapplication.ui.balance.BreakdownRowUi
import com.example.myapplication.ui.balance.formatMoney
import com.example.myapplication.ui.balance.formatSignedMoney
import com.example.myapplication.ui.balance.DebtDetailsContent
import com.example.myapplication.ui.balance.DebtUi
import com.example.myapplication.ui.balance.GroupBalanceUi
import com.example.myapplication.ui.balance.MyBalanceContent
import com.example.myapplication.utils.Resource
import com.example.myapplication.utils.formatTimestamp
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException

/** Outcome of a write action. [refresh] = the screen data was stale (404/409) and should be reloaded. */
sealed class ActionResult {
    object Success : ActionResult()
    data class Failure(val message: String, val refresh: Boolean) : ActionResult()
}

/**
 * My Balance + Debt Details. Every amount, status and id is taken from the server response as is
 * (BigDecimal, no local settlement math).
 */
class BalanceRepository(
    private val authRepository: AuthRepository = AuthRepository(),
    private val apiService: ApiService = RetrofitClient.apiService
) {

    private class GroupResult(
        val group: Group,
        val balance: BigDecimal?,
        val settlement: SettlementResponse?,
        val error: Throwable?
    )

    suspend fun loadMyBalance(): Resource<MyBalanceContent> {
        val uid = authRepository.currentUser?.uid ?: return Resource.Error("המשתמש אינו מחובר")

        val groups = try {
            apiService.getGroups().mapNotNull { it.toGroup() }.sortedByDescending { it.createdAtMillis }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /groups failed", e)
            return Resource.Error(ApiErrorMapper.message(e))
        }

        val results = coroutineScope {
            groups.map { g ->
                async {
                    val (balance, e1) = call("balances", g.id) { apiService.getGroupBalances(g.id)[uid] ?: BigDecimal.ZERO }
                    val (settlement, e2) = call("settlement", g.id) { apiService.getGroupSettlement(g.id) }
                    GroupResult(g, balance, settlement, e1 ?: e2)
                }
            }.awaitAll()
        }

        // Every group failed on every call: report the first error (401/403/network) instead of an empty screen.
        if (results.isNotEmpty() && results.all { it.balance == null && it.settlement == null }) {
            return Resource.Error(ApiErrorMapper.message(results.first().error ?: Exception()))
        }

        // Open settlements involving me, straight from `transfers`.
        val open = results.flatMap { r ->
            r.settlement?.transfers.orEmpty().mapNotNull { t ->
                val id = t.id ?: return@mapNotNull null
                val from = t.from ?: return@mapNotNull null
                val to = t.to ?: return@mapNotNull null
                val amount = t.amount ?: return@mapNotNull null
                if (!t.isOpen || amount.signum() <= 0 || (from != uid && to != uid)) null
                else OpenSettlement(r.group, id, if (to == uid) from else to, amount, owedToMe = to == uid)
            }
        }
        val names = UserNameResolver.resolve(open.map { it.otherUid }.toSet())

        val debts = open.map {
            DebtUi(
                key = "${it.group.id}:${it.settlementId}",
                groupId = it.group.id,
                settlementId = it.settlementId,
                otherUid = it.otherUid,
                otherName = names[it.otherUid],
                amount = it.amount,
                groupName = it.group.name,
                owedToMe = it.owedToMe
            )
        }.sortedByDescending { it.amount }

        val balances = results.mapNotNull { it.balance }
        return Resource.Success(
            MyBalanceContent(
                totalBalance = if (groups.isEmpty()) BigDecimal.ZERO
                else if (balances.isEmpty()) null
                else balances.fold(BigDecimal.ZERO, BigDecimal::add),
                groupCount = groups.size,
                owedToMe = debts.filter { it.owedToMe }.fold(BigDecimal.ZERO) { a, d -> a + d.amount },
                owedByMe = debts.filterNot { it.owedToMe }.fold(BigDecimal.ZERO) { a, d -> a + d.amount },
                debts = debts,
                groups = results.map {
                    GroupBalanceUi(
                        id = it.group.id,
                        name = it.group.name,
                        icon = it.group.icon.takeIf { i -> i.isNotBlank() },
                        balance = it.balance,
                        createdBy = it.group.createdBy,
                        canDelete = canDeleteGroup(it.group.createdBy, uid).also { can ->
                            logGroupDeleteDebug("MyBalance", it.group.id, it.group.name, it.group.createdBy, uid, can)
                        }
                    )
                },
                failedGroups = results.count { it.balance == null || it.settlement == null }
            )
        )
    }

    private class OpenSettlement(
        val group: Group,
        val settlementId: String,
        val otherUid: String,
        val amount: BigDecimal,
        val owedToMe: Boolean
    )

    /**
     * Group Details: GET /groups/{id} (name, icon, members), /balances (every member's balance) and
     * /expenses, the last two in parallel. Only the group request is fatal; a failing balances or expenses
     * request leaves just that section with its own message.
     */
    suspend fun loadGroupDetails(groupId: String): Resource<GroupDetailsContent> {
        val uid = authRepository.currentUser?.uid ?: return Resource.Error("המשתמש אינו מחובר")

        val group = try {
            apiService.getGroup(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /groups/$groupId failed", e)
            return Resource.Error(groupErrorMessage(e))
        }

        val (balances, expenses) = coroutineScope {
            val b = async { call("balances", groupId) { apiService.getGroupBalances(groupId) } }
            val x = async { call("expenses", groupId) { apiService.getGroupExpenses(groupId) } }
            b.await() to x.await()
        }

        val memberIds = group.memberIds.orEmpty()
        val expenseList = expenses.first.orEmpty()
        val names = UserNameResolver.resolve(memberIds.toSet() + expenseList.mapNotNull { it.paidBy })

        val members = memberIds
            .map { id -> MemberBalanceUi(id, names[id], id == uid, balances.first?.let { it[id] ?: BigDecimal.ZERO }) }
            .sortedByDescending { it.isMe } // the signed-in user first

        val rows = expenseList
            .sortedByDescending { parseTimestamp(it.createdAt)?.toInstant() }
            .mapNotNull { e ->
                val id = e.id ?: return@mapNotNull null
                val total = e.amount ?: return@mapNotNull null
                val share = myShare(e, uid)
                ExpenseUi(
                    id = id,
                    description = e.description?.takeIf { it.isNotBlank() } ?: "הוצאה",
                    amount = total,
                    payerLabel = "שולם על ידי ${e.paidBy?.let { names[it] } ?: "משתמש"}",
                    dateText = formatDateOnly(e.createdAt),
                    shareText = share?.let { "החלק שלך ${formatMoney(it)}" }
                )
            }

        return Resource.Success(
            GroupDetailsContent(
                id = groupId,
                name = group.name.orEmpty(),
                icon = group.icon?.takeIf { it.isNotBlank() },
                createdBy = group.createdBy,
                canDelete = canDeleteGroup(group.createdBy, uid).also {
                    logGroupDeleteDebug("GroupDetails", groupId, group.name.orEmpty(), group.createdBy, uid, it)
                },
                myBalance = balances.first?.let { it[uid] ?: BigDecimal.ZERO },
                members = members,
                balanceError = balances.second?.let { detailsLoadMessage(it) },
                expenses = rows,
                expensesError = expenses.second?.let { detailsLoadMessage(it) }
            )
        )
    }

    /**
     * The signed-in user's share of one expense, or null when the data does not state it exactly.
     * - exact split: their entry in `exactAmounts` (like the server, a payer also gets any difference
     *   between the amounts and the total);
     * - equal split: total / participants, only when it divides into whole cents. Otherwise the server's
     *   seeded odd-cent rule decides who pays the extra cent and the API does not return it, so no share is shown.
     */
    private fun myShare(e: ExpenseDto, uid: String): BigDecimal? =
        if (uid !in e.participantIds.orEmpty()) null else shareOf(e, uid)

    /**
     * One member's exact share of an expense, or null when the data does not state it exactly
     * (see [myShare]). A member who is not a participant (and not the payer absorbing a difference) owes 0.
     */
    private fun shareOf(e: ExpenseDto, uid: String): BigDecimal? = runCatching {
        val total = e.amount ?: return null
        val participants = e.participantIds.orEmpty().distinct()
        if (e.splitType == "exact") {
            val exact = e.exactAmounts ?: return null
            var share = exact[uid] ?: BigDecimal.ZERO
            val difference = total.subtract(exact.values.fold(BigDecimal.ZERO, BigDecimal::add))
            if (e.paidBy == uid && difference.signum() != 0) share = share.add(difference)
            share
        } else {
            if (uid !in participants) return BigDecimal.ZERO.setScale(2)
            val cents = total.movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY)
            val n = BigDecimal(participants.size)
            if (cents.remainder(n).signum() != 0) null
            else cents.divide(n).movePointLeft(2).setScale(2)
        }
    }.getOrNull()

    /**
     * Expense Details: finds the expense in GET /groups/{id}/expenses (there is no single-expense endpoint)
     * and reads the group from GET /groups/{id}. Shares are stated only when exact (see [shareOf]).
     */
    suspend fun loadExpenseDetails(groupId: String, expenseId: String): Resource<ExpenseDetailsContent> {
        val uid = authRepository.currentUser?.uid ?: return Resource.Error("המשתמש אינו מחובר")

        val group = try {
            apiService.getGroup(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /groups/$groupId failed", e)
            return Resource.Error(expenseErrorMessage(e))
        }
        val expense = try {
            apiService.getGroupExpenses(groupId).firstOrNull { it.id == expenseId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /groups/$groupId/expenses failed", e)
            return Resource.Error(expenseErrorMessage(e))
        } ?: return Resource.Error("ההוצאה לא נמצאה")

        val total = expense.amount ?: return Resource.Error("נתוני ההוצאה חסרים")
        val participants = expense.participantIds.orEmpty().distinct()
        val payerUid = expense.paidBy
        val names = UserNameResolver.resolve(participants.toSet() + setOfNotNull(payerUid))

        val rows = participants
            .map { ParticipantShareUi(it, names[it], it == uid, shareOf(expense, it)) }
            .sortedByDescending { it.isMe }
        val payerName = payerUid?.let { names[it] }

        // Your result in THIS expense (not including settlements): only when your share is known exactly.
        val mine = shareOf(expense, uid)
        val result: ExpenseResultUi? = when {
            mine == null -> null
            payerUid == uid -> total.subtract(mine).takeIf { it.signum() > 0 }?.let { ExpenseResultUi.Receive(it) }
            uid in participants && mine.signum() > 0 -> ExpenseResultUi.Owe(mine, payerName)
            else -> null
        }

        return Resource.Success(
            ExpenseDetailsContent(
                description = expense.description?.takeIf { it.isNotBlank() } ?: "הוצאה",
                amount = total,
                groupName = group.name.orEmpty(),
                groupIcon = group.icon?.takeIf { it.isNotBlank() },
                dateText = formatDateOnly(expense.createdAt),
                splitLabel = when (expense.splitType) {
                    "equal" -> "חלוקה שווה"
                    "exact" -> "סכומים מדויקים"
                    else -> null
                },
                payerName = payerName,
                payerIsMe = payerUid == uid,
                participants = rows,
                result = result,
                sharesNote = if (rows.any { it.share == null })
                    "החלוקה לא מתחלקת בדיוק לאגורות, והשרת הוא שקובע מי משלם את האגורה הנוספת. לכן הסכום לכל משתתף לא מוצג."
                else null
            )
        )
    }

    private fun expenseErrorMessage(e: Throwable): String = when {
        e is HttpException && e.code() == 401 -> ApiErrorMapper.message(e)
        e is HttpException && e.code() == 403 -> "אין לך הרשאה לצפות בהוצאה זו"
        e is HttpException && e.code() == 404 -> "ההוצאה לא נמצאה"
        else -> "לא ניתן לטעון את פרטי ההוצאה. נסי שוב."
    }

    private fun groupErrorMessage(e: Throwable): String = when {
        e is HttpException && e.code() == 401 -> ApiErrorMapper.message(e)
        e is HttpException && e.code() == 403 -> "אין לך הרשאה לצפות בקבוצה זו"
        e is HttpException && e.code() == 404 -> "הקבוצה לא נמצאה"
        else -> "לא ניתן לטעון את פרטי הקבוצה. נסי שוב."
    }

    private fun detailsLoadMessage(e: Throwable): String =
        if (e is HttpException && e.code() == 401) ApiErrorMapper.message(e)
        else "לא ניתן לטעון את פרטי הקבוצה. נסי שוב."

    /** Refetches the group's settlement and finds [settlementId] in it (open first, then paid). */
    suspend fun loadDebtDetails(
        groupId: String,
        settlementId: String,
        retryIfStale: Boolean = true
    ): Resource<DebtDetailsContent> {
        val uid = authRepository.currentUser?.uid ?: return Resource.Error("המשתמש אינו מחובר")

        val group = try {
            apiService.getGroup(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /groups/$groupId failed", e)
            return Resource.Error(ApiErrorMapper.message(e))
        }
        val settlement = try {
            apiService.getGroupSettlement(groupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /groups/$groupId/settlement failed", e)
            return Resource.Error(ApiErrorMapper.message(e))
        }

        val s: SettlementDto = settlement.transfers.orEmpty().firstOrNull { it.id == settlementId }
            ?: settlement.paid.orEmpty().firstOrNull { it.id == settlementId }
            ?: return Resource.Error("החוב הזה השתנה או כבר לא קיים. חזרו למסך הקודם כדי לרענן")

        val from = s.from
        val to = s.to
        val amount = s.amount
        if (from == null || to == null || amount == null || (from != uid && to != uid)) {
            return Resource.Error("החוב הזה לא קשור אליכם")
        }
        val owedToMe = to == uid
        val otherUid = if (owedToMe) from else to
        val isPaid = s.status == "paid"

        val breakdown = try {
            toBreakdown(apiService.getSettlementBreakdown(groupId, settlementId), owedToMe)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Log.e(TAG, "GET breakdown $groupId/$settlementId failed", e)
            if (e.code() == 404) {
                // The id went stale between the two calls: re-resolve it once from a fresh settlement,
                // otherwise report that the debt changed (never show old data).
                return if (retryIfStale) loadDebtDetails(groupId, settlementId, retryIfStale = false)
                else Resource.Error("החוב הזה השתנה או כבר לא קיים. חזרו למסך הקודם כדי לרענן")
            }
            Breakdown.Error(ApiErrorMapper.message(e))
        } catch (e: Exception) {
            Log.e(TAG, "GET breakdown $groupId/$settlementId failed", e)
            Breakdown.Error(ApiErrorMapper.message(e))
        }

        val names = UserNameResolver.resolve(setOfNotNull(otherUid, s.markedBy))
        return Resource.Success(
            DebtDetailsContent(
                groupId = groupId,
                settlementId = settlementId,
                otherName = names[otherUid],
                owedToMe = owedToMe,
                amount = amount,
                isPaid = isPaid,
                paidAtText = if (isPaid) formatTimestamp(s.paidAt) else null,
                markedByName = if (!isPaid) null else if (s.markedBy == uid) "את" else s.markedBy?.let { names[it] },
                groupName = group.name.orEmpty(),
                groupIcon = group.icon?.takeIf { it.isNotBlank() },
                mode = when {
                    isPaid -> DebtActionMode.NONE
                    owedToMe -> if (s.hasCurrentClaim) DebtActionMode.CREDITOR_CLAIM_PENDING
                    else DebtActionMode.CREDITOR_OPEN
                    else -> if (s.hasCurrentClaim) DebtActionMode.DEBTOR_CLAIM_PENDING
                    else DebtActionMode.DEBTOR_CAN_CLAIM
                },
                claimRejected = !isPaid && s.paymentClaimStatus == "rejected",
                breakdown = breakdown
            )
        )
    }

    /** Maps the server's breakdown as is: no amounts are calculated or adjusted here. */
    private fun toBreakdown(r: BreakdownResponse, owedToMe: Boolean): Breakdown {
        // Older servers send no breakdownType: fall back to breakdownAvailable.
        val type = r.breakdownType ?: if (r.breakdownAvailable == true) "exact" else "unavailable"
        if (type == "netted") return nettedBreakdown(r, owedToMe)
        if (type != "exact") return Breakdown.Unavailable(unavailableMessage(r.reason))
        val rows = r.items.orEmpty().mapIndexedNotNull { i, item ->
            val amount = item.relevantAmount ?: return@mapIndexedNotNull null
            val isPayment = item.type == "payment"
            BreakdownRowUi(
                key = item.expenseId ?: item.settlementId ?: "row$i",
                expenseId = if (isPayment) null else item.expenseId,
                title = if (isPayment) "תשלום קודם"
                else item.description?.takeIf { it.isNotBlank() } ?: "הוצאה ללא תיאור",
                subtitle = if (isPayment) null else item.expenseAmount?.let { "מתוך ${formatMoney(it)}" },
                amount = amount
            )
        }
        return Breakdown.Rows(rows)
    }

    /**
     * Netted debt: list the server's contributing expenses as context. Each row shows the whole expense
     * and, as a subtitle, the signed-in user's own effect on their balance (`toContribution` if I am the
     * creditor, else `fromContribution`). Nothing is summed or adjusted.
     */
    private fun nettedBreakdown(r: BreakdownResponse, owedToMe: Boolean): Breakdown {
        val rows = r.contributingExpenses.orEmpty().mapIndexedNotNull { i, e ->
            val total = e.expenseAmount ?: return@mapIndexedNotNull null
            val mine = if (owedToMe) e.toContribution else e.fromContribution
            BreakdownRowUi(
                key = e.expenseId ?: "row$i",
                expenseId = e.expenseId,
                title = e.description?.takeIf { it.isNotBlank() } ?: "הוצאה ללא תיאור",
                subtitle = mine?.let { "ההשפעה שלך על המאזן: ${formatSignedMoney(it)}" },
                amount = total
            )
        }
        return Breakdown.Netted(NETTED_EXPLANATION, rows)
    }

    private fun unavailableMessage(reason: String?): String = when {
        reason == null -> "לא ניתן להציג פירוט עבור חוב זה."
        "netted obligation" in reason ->
            "לא ניתן לשייך את החוב הזה באופן חד-משמעי להוצאות מסוימות, מאחר שהוא נוצר לאחר קיזוז בין מספר משתתפים."
        "No breakdown was recorded" in reason -> "לא נשמר פירוט בזמן סימון החוב כשולם."
        "inconsistent" in reason -> "הפירוט שנשמר בעת התשלום אינו עקבי ולכן לא מוצג."
        else -> "לא ניתן להציג פירוט עבור חוב זה."
    }

    /** PATCH .../paid with the amount the user saw. The UI changes only after the server confirms. */
    suspend fun markPaid(groupId: String, settlementId: String, expectedAmount: BigDecimal): ActionResult =
        action(ApiAction.MARK_PAID, "mark paid $groupId/$settlementId") {
            apiService.markSettlementPaid(groupId, settlementId, MarkPaidRequest(expectedAmount))
        }

    /** Debtor: POST .../payment-claim ("I paid"). The settlement stays open until the creditor confirms. */
    suspend fun claimPayment(groupId: String, settlementId: String, expectedAmount: BigDecimal): ActionResult =
        action(ApiAction.CLAIM_PAYMENT, "payment claim $groupId/$settlementId") {
            apiService.claimPayment(groupId, settlementId, MarkPaidRequest(expectedAmount))
        }

    /** Creditor: PATCH .../confirm-payment. Needs a pending claim; the server then marks the debt paid. */
    suspend fun confirmPayment(groupId: String, settlementId: String, expectedAmount: BigDecimal): ActionResult =
        action(ApiAction.CONFIRM_PAYMENT, "confirm payment $groupId/$settlementId") {
            apiService.confirmPayment(groupId, settlementId, MarkPaidRequest(expectedAmount))
        }

    /** POST .../reminder: no body; the server derives sender, recipient and amount. */
    suspend fun sendReminder(groupId: String, settlementId: String): ActionResult =
        action(ApiAction.REMINDER, "reminder $groupId/$settlementId") {
            apiService.sendReminder(groupId, settlementId)
        }

    private suspend fun action(kind: ApiAction, label: String, block: suspend () -> Any): ActionResult =
        try {
            block()
            ActionResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$label failed", e)
            ActionResult.Failure(ApiErrorMapper.message(e, kind), refresh = ApiErrorMapper.isStale(e))
        }

    /** Runs one backend call; a failure is logged with the group id and returned instead of thrown. */
    private suspend fun <T> call(what: String, groupId: String, block: suspend () -> T): Pair<T?, Throwable?> =
        try {
            block() to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /groups/$groupId/$what failed: ${ApiErrorMapper.message(e)}", e)
            null to e
        }

    private companion object {
        const val TAG = "BalanceRepository"
        const val NETTED_EXPLANATION =
            "החוב הסופי נוצר לאחר קיזוז בין מספר משתתפים, ולכן הסכומים הבאים מציגים הוצאות שתרמו למאזן ולא פירוק ישיר של החוב."
    }
}
