package com.example.myapplication.repository

import android.util.Log
import com.example.myapplication.data.api.ApiAction
import com.example.myapplication.data.api.ApiErrorMapper
import com.example.myapplication.data.api.ApiService
import com.example.myapplication.data.api.MarkPaidRequest
import com.example.myapplication.data.api.RetrofitClient
import com.example.myapplication.data.api.SettlementDto
import com.example.myapplication.data.api.SettlementResponse
import com.example.myapplication.data.model.Avatar
import com.example.myapplication.data.model.Group
import com.example.myapplication.data.model.toAvatar
import com.example.myapplication.data.api.BreakdownResponse
import com.example.myapplication.data.api.ExpenseDto
import com.example.myapplication.ui.balance.DirectActionGroup
import com.example.myapplication.ui.balance.DirectActions
import com.example.myapplication.ui.balance.DirectDebtGroupUi
import com.example.myapplication.ui.balance.DirectDebtList
import com.example.myapplication.ui.balance.DirectDebtUi
import com.example.myapplication.ui.expense.ExpenseDebtUi
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

    private class GroupResult(val group: Group, val balance: BigDecimal?, val error: Throwable?)

    /**
     * My Balance. The two summary cards, the big net number and the open-debt rows ALL come from the direct-debt
     * endpoints (GET /debts/receivable and /debts/payable): the cards show those `total`s exactly and the big
     * number is receivable.total - payable.total. Only the per-group list still uses GET /groups/{id}/balances.
     * No settlement transfers are read here. If either direct-debt request fails the whole screen reports the
     * error rather than show totals that disagree with each other.
     */
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

        val (receivable, payable, results) = coroutineScope {
            val r = async { loadDirectDebts(owedToMe = true) }
            val p = async { loadDirectDebts(owedToMe = false) }
            val g = groups.map { gr ->
                async {
                    val (balance, e) = call("balances", gr.id) { apiService.getGroupBalances(gr.id)[uid] ?: BigDecimal.ZERO }
                    GroupResult(gr, balance, e)
                }
            }
            Triple(r.await(), p.await(), g.awaitAll())
        }
        if (receivable is Resource.Error) return receivable
        if (payable is Resource.Error) return payable
        val owedToMe = (receivable as Resource.Success).data
        val owedByMe = (payable as Resource.Success).data
        Log.i("DIRECT_DEBTS", "myBalance receivable=${owedToMe.total} (${owedToMe.debts.size} rows) payable=${owedByMe.total} (${owedByMe.debts.size} rows) net=${owedToMe.total.subtract(owedByMe.total)}") // TEMP

        return Resource.Success(
            MyBalanceContent(
                totalBalance = owedToMe.total.subtract(owedByMe.total),
                groupCount = groups.size,
                owedToMe = owedToMe.total,
                owedByMe = owedByMe.total,
                debts = (owedToMe.debts + owedByMe.debts).sortedByDescending { it.amount },
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
                failedGroups = results.count { it.balance == null }
            )
        )
    }

    /**
     * "חייבים לך" ([owedToMe] = true, GET /debts/receivable) or "את חייבת" (false, GET /debts/payable).
     * Rows and amounts are the backend's, unchanged: no settlement transfers, no netting, no local math.
     */
    suspend fun loadDirectDebts(owedToMe: Boolean): Resource<DirectDebtList> {
        val response = try {
            if (owedToMe) apiService.getReceivableDebts() else apiService.getPayableDebts()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /debts/${if (owedToMe) "receivable" else "payable"} failed", e)
            return Resource.Error(ApiErrorMapper.message(e))
        }
        val items = response.debts.orEmpty().filter { !it.userId.isNullOrBlank() && it.amount != null }
        Log.i("DIRECT_DEBTS", "owedToMe=$owedToMe backendRows=${response.debts?.size} usable=${items.size} total=${response.total}") // TEMP
        val names = UserNameResolver.resolve(items.filter { it.displayName.isNullOrBlank() }.map { it.userId!! }.toSet())
        val groupIds = items.flatMap { it.groups.orEmpty().mapNotNull { g -> g.groupId } }.distinct()
        val avatarsByGroup = coroutineScope {
            groupIds.map { id -> async { id to groupAvatars(id) } }.awaitAll().toMap()
        }
        val rows = items.map { d ->
            val uid = d.userId!!
            val groups = d.groups.orEmpty().mapNotNull { g ->
                DirectDebtGroupUi(g.groupId ?: return@mapNotNull null, g.groupName.orEmpty(), g.amount ?: return@mapNotNull null)
            }
            DirectDebtUi(
                otherUid = uid,
                otherName = d.displayName?.takeIf { it.isNotBlank() } ?: names[uid],
                // Saved avatars only exist for current members of a group: someone removed shows the default.
                otherAvatar = groups.firstNotNullOfOrNull { g -> avatarsByGroup[g.groupId]?.get(uid) },
                amount = d.amount!!,
                groups = groups,
                owedToMe = owedToMe
            )
        }
        return Resource.Success(DirectDebtList(response.total ?: rows.fold(BigDecimal.ZERO) { a, r -> a + r.amount }, rows))
    }

    /**
     * The signed-in user's overall balance: the sum of their entry in GET /groups/{id}/balances over
     * [groupIds]. Shared with Home so both show the same number. A failing group is logged and skipped;
     * null when there are groups but every balance request failed.
     */
    suspend fun loadOverallBalance(groupIds: List<String>): BigDecimal? {
        val uid = authRepository.currentUser?.uid ?: return null
        if (groupIds.isEmpty()) return BigDecimal.ZERO
        val results = coroutineScope {
            groupIds.map { id ->
                async { call("balances", id) { apiService.getGroupBalances(id)[uid] ?: BigDecimal.ZERO }.first }
            }.awaitAll()
        }
        val ok = results.filterNotNull()
        if (ok.isEmpty()) return null
        return ok.fold(BigDecimal.ZERO, BigDecimal::add)
    }

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

        val (balances, expenses, avatars) = coroutineScope {
            val b = async { call("balances", groupId) { apiService.getGroupBalances(groupId) } }
            val x = async { call("expenses", groupId) { apiService.getGroupExpenses(groupId) } }
            val a = async { groupAvatars(groupId) }
            Triple(b.await(), x.await(), a.await())
        }

        val memberIds = group.memberIds.orEmpty()
        val expenseList = expenses.first.orEmpty()
        // Former members stay listed while their balance is non-zero (the server keeps counting every
        // expense they took part in); at zero they disappear from this list, but old expenses keep them.
        val formerMembersOwing = balances.first.orEmpty()
            .filter { (id, balance) -> id !in memberIds && balance.signum() != 0 }
            .keys.toList()
        // TEMP DEBUG (member removal): uids only, no tokens.
        Log.d(TAG, "MEMBERS_DEBUG group=$groupId memberIds=$memberIds balanceKeys=${balances.first?.keys} " +
            "historicalNonZeroNotMembers=$formerMembersOwing")
        val names = UserNameResolver.resolve(
            memberIds.toSet() + formerMembersOwing +
                expenseList.flatMap { listOfNotNull(it.paidBy) + it.participantIds.orEmpty() }
        )

        val members = (memberIds + formerMembersOwing)
            .map { id ->
                val isCurrent = id in memberIds
                // Same rule in every group (createdBy plays no part): a current member may remove any OTHER
                // current member. Never yourself, never a former member shown only for a historical balance.
                val removable = isCurrent && id != uid
                MemberBalanceUi(
                    id, names[id], id == uid, balances.first?.let { it[id] ?: BigDecimal.ZERO },
                    avatars[id], canRemove = removable
                )
            }
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
                canLeave = group.createdBy != uid,
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
        // The per-expense obligations come straight from the server; nothing is recomputed here.
        val (breakdown, breakdownError) = call("expense breakdown", groupId) {
            apiService.getExpenseBreakdown(groupId, expenseId)
        }
        Log.i("DEBTS_DEBUG", "repo: GET expenses/$expenseId/breakdown group=$groupId ok=${breakdown != null} err=${breakdownError?.javaClass?.simpleName} items=${breakdown?.items?.size}") // TEMP
        val debtItems = breakdown?.items.orEmpty().filter { it.userId != null && it.owesTo != null }
        val names = UserNameResolver.resolve(
            participants.toSet() + setOfNotNull(payerUid) +
                debtItems.flatMap { listOfNotNull(it.userId, it.owesTo) }
        )
        val avatars = groupAvatars(groupId)
        val debts = debtItems.mapNotNull { item ->
            val amount = item.amountOwed ?: item.share ?: return@mapNotNull null
            val debtor = item.userId!!
            val creditor = item.owesTo!!
            ExpenseDebtUi(
                debtor, names[debtor], debtor == uid, avatars[debtor],
                creditor, names[creditor], creditor == uid, avatars[creditor], amount
            )
        }

        val rows = participants
            .map { ParticipantShareUi(it, names[it], it == uid, shareOf(expense, it), avatars[it]) }
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
                payerAvatar = payerUid?.let { avatars[it] },
                participants = rows,
                result = result,
                sharesNote = if (rows.any { it.share == null })
                    "החלוקה לא מתחלקת בדיוק לאגורות, והשרת הוא שקובע מי משלם את האגורה הנוספת. לכן הסכום לכל משתתף לא מוצג."
                else null,
                debts = debts,
                debtsError = breakdownError?.let { breakdownErrorMessage(it) }
            )
        )
    }

    private fun breakdownErrorMessage(e: Throwable): String = when {
        e is HttpException && e.code() == 401 -> ApiErrorMapper.message(e)
        e is HttpException && e.code() == 403 -> "אין לך הרשאה לצפות בפירוט החובות"
        e is HttpException && e.code() == 404 -> "פירוט החובות של ההוצאה לא נמצא"
        e is java.io.IOException -> ApiErrorMapper.message(e)
        else -> "לא ניתן לטעון את פירוט החובות. נסי שוב."
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
        val avatars = groupAvatars(groupId)
        return Resource.Success(
            DebtDetailsContent(
                groupId = groupId,
                settlementId = settlementId,
                otherName = names[otherUid],
                otherAvatar = avatars[otherUid],
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

    /**
     * The existing Debt Details screen for a DIRECT debt (a row of /debts/receivable or /debts/payable), from
     * GET /debts/{receivable|payable}/{uid}. There is no settlement, so the settlement actions (mark paid,
     * reminder) are NONE: they would need a settlementId that does not exist.
     */
    suspend fun loadDirectDebtDetails(otherUid: String, owedToMe: Boolean): Resource<DebtDetailsContent> {
        val d = try {
            if (owedToMe) apiService.getReceivableDebtDetail(otherUid) else apiService.getPayableDebtDetail(otherUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Log.e(TAG, "GET /debts/${if (owedToMe) "receivable" else "payable"}/$otherUid failed", e)
            return Resource.Error(
                if (e.code() == 404) "החוב הזה השתנה או כבר לא קיים. חזרו למסך הקודם כדי לרענן" else ApiErrorMapper.message(e)
            )
        } catch (e: Exception) {
            Log.e(TAG, "GET /debts/.../$otherUid failed", e)
            return Resource.Error(ApiErrorMapper.message(e))
        }
        val amount = d.amount ?: return Resource.Error("פרטי החוב חסרים")
        val directUids = d.debtorUid?.let { db -> d.creditorUid?.let { cr -> db to cr } }
        val groups = d.groups.orEmpty().filter { !it.groupId.isNullOrBlank() }
        val severalGroups = groups.size > 1
        val rows = groups.flatMap { g ->
            g.items.orEmpty().mapIndexedNotNull { i, item ->
                val relevant = item.relevantAmount ?: return@mapIndexedNotNull null
                val isPayment = item.type == "payment"
                val whole = item.expenseAmount?.let { "מתוך ${formatMoney(it)}" }
                BreakdownRowUi(
                    key = "${g.groupId}:${item.expenseId ?: item.settlementId ?: "row$i"}",
                    groupId = g.groupId,
                    expenseId = if (isPayment) null else item.expenseId,
                    title = if (isPayment) "תשלום קודם"
                    else item.description?.takeIf { it.isNotBlank() } ?: "הוצאה ללא תיאור",
                    subtitle = listOfNotNull(whole.takeIf { !isPayment }, g.groupName.takeIf { severalGroups }).joinToString(" · ")
                        .ifEmpty { null },
                    amount = relevant
                )
            }
        }
        val breakdown: Breakdown =
            if (groups.all { it.itemsExact == true }) Breakdown.Rows(rows)
            else Breakdown.Netted("הסכום כולל תשלומים שגדולים מהחוב הישיר, ולכן הפירוט להלן אינו מסתכם בדיוק לסכום.", rows)

        val singleGroup = groups.singleOrNull()
        val icon = singleGroup?.groupId?.let {
            try { apiService.getGroup(it).icon?.takeIf { i -> i.isNotBlank() } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        }
        val avatar = groups.map { it.groupId!! }.firstNotNullOfOrNull { gid -> groupAvatars(gid)[otherUid] }
        val name = d.displayName?.takeIf { it.isNotBlank() } ?: UserNameResolver.resolve(setOf(otherUid))[otherUid]
        return Resource.Success(
            DebtDetailsContent(
                groupId = null,
                settlementId = null,
                otherName = name,
                otherAvatar = avatar,
                owedToMe = owedToMe,
                amount = amount,
                isPaid = false,
                paidAtText = null,
                markedByName = null,
                groupName = groups.joinToString(" · ") { it.groupName.orEmpty() },
                groupIcon = icon,
                mode = DebtActionMode.DIRECT,
                claimRejected = false,
                breakdown = breakdown,
                direct = directUids?.let { (debtor, creditor) ->
                    DirectActions(
                        debtorUid = debtor,
                        creditorUid = creditor,
                        canMarkPaid = d.canMarkPaid == true,
                        canSendReminder = d.canSendReminder == true,
                        requiresGroupSelection = d.requiresGroupSelection == true,
                        canClaimPaid = d.canClaimPaid == true,
                        claimPending = d.claimPending == true,
                        claimedAmount = d.claimedAmount,
                        canConfirmClaim = d.canConfirmClaim == true,
                        groups = groups.map {
                            DirectActionGroup(
                                it.groupId!!, it.groupName.orEmpty(), it.amount ?: BigDecimal.ZERO,
                                it.canMarkPaid == true, it.canClaimPaid == true, it.canConfirmClaim == true
                            )
                        }
                    )
                }
            )
        )
    }

    /** The server's answer to a direct "סמן כשולם". [remaining] is its `remainingAmount`, unchanged. */
    sealed class DirectPaidResult {
        data class Success(val paid: BigDecimal?, val remaining: BigDecimal) : DirectPaidResult()
        data class Failure(val message: String) : DirectPaidResult()
    }

    /** PATCH /debts/direct/{debtor}/{creditor}/paid with the amount the screen showed. */
    suspend fun markDirectPaid(
        actions: DirectActions, groupId: String?, allGroups: Boolean, expectedAmount: BigDecimal
    ): DirectPaidResult =
        try {
            val r = apiService.markDirectDebtPaid(
                actions.debtorUid, actions.creditorUid, groupId, if (allGroups) true else null, MarkPaidRequest(expectedAmount)
            )
            val remaining = r.remainingAmount
            if (remaining == null) DirectPaidResult.Failure("התשובה מהשרת חסרה. רעננו את המסך")
            else DirectPaidResult.Success(r.paidAmount, remaining)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "direct mark paid ${actions.debtorUid}->${actions.creditorUid} failed", e)
            DirectPaidResult.Failure(ApiErrorMapper.message(e, ApiAction.MARK_PAID))
        }

    /** Debtor "שילמתי": POST .../payment-claim. Success only on 201; the debt is NOT changed by it. */
    suspend fun claimDirectPaid(actions: DirectActions, groupId: String?, allGroups: Boolean, expectedAmount: BigDecimal): ActionResult =
        action(ApiAction.CLAIM_PAYMENT, "direct claim ${actions.debtorUid}->${actions.creditorUid}") {
            apiService.claimDirectDebtPaid(
                actions.debtorUid, actions.creditorUid, groupId, if (allGroups) true else null, MarkPaidRequest(expectedAmount)
            )
        }

    /** Creditor: PATCH .../confirm-payment. The claimed amount becomes a real payment; [DirectPaidResult.Success.remaining] is the server's. */
    suspend fun confirmDirectClaim(actions: DirectActions, groupId: String?, allGroups: Boolean): DirectPaidResult =
        try {
            val r = apiService.confirmDirectDebtClaim(actions.debtorUid, actions.creditorUid, groupId, if (allGroups) true else null)
            val remaining = r.remainingAmount
            if (remaining == null) DirectPaidResult.Failure("התשובה מהשרת חסרה. רעננו את המסך")
            else DirectPaidResult.Success(r.paidAmount, remaining)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "direct confirm ${actions.debtorUid}->${actions.creditorUid} failed", e)
            DirectPaidResult.Failure(ApiErrorMapper.message(e, ApiAction.CONFIRM_PAYMENT))
        }

    /** POST /debts/direct/{debtor}/{creditor}/reminder (one reminder for all groups). Success only on HTTP 2xx. */
    suspend fun sendDirectReminder(actions: DirectActions): ActionResult =
        action(ApiAction.REMINDER, "direct reminder ${actions.debtorUid}->${actions.creditorUid}") {
            apiService.sendDirectDebtReminder(actions.debtorUid, actions.creditorUid, null)
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

    /**
     * uid -> saved avatar for every member of [groupId] (GET /groups/{id}/members), always fresh from the
     * backend. A failure is logged and returns an empty map: those people then render the default avatar.
     */
    private suspend fun groupAvatars(groupId: String): Map<String, Avatar> =
        try {
            apiService.getGroupMembers(groupId)
                .mapNotNull { m -> m.uid?.let { it to m.avatar.toAvatar() } }
                .toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /groups/$groupId/members failed: ${ApiErrorMapper.message(e)}")
            emptyMap()
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
