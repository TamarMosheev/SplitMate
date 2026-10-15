package com.example.myapplication.data.api

import java.math.BigDecimal
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Mirrors docs/ANDROID_API_CONTRACT.md. All calls need the Firebase bearer token ([AuthInterceptor]). */
interface ApiService {

    @GET("groups")
    suspend fun getGroups(): List<GroupDto>

    @GET("groups/{group_id}")
    suspend fun getGroup(@Path("group_id") groupId: String): GroupDto

    /** Creator only: permanently deletes the group, its expenses, settlements and notifications. */
    @DELETE("groups/{group_id}")
    suspend fun deleteGroup(@Path("group_id") groupId: String): GroupDeletedResponse

    /** Any current member may add a registered user. 403 not a member, 404 no such user, 409 already a member. */
    @POST("groups/{group_id}/members")
    suspend fun addGroupMember(@Path("group_id") groupId: String, @Body body: AddMemberRequest): GroupDto

    /**
     * The creator may remove any member except themself; any other member may remove only themself.
     * 400 target is the creator, 403 not allowed, 404 no such group / not a member. Returns the updated group.
     */
    @DELETE("groups/{group_id}/members/{user_id}")
    suspend fun removeGroupMember(@Path("group_id") groupId: String, @Path("user_id") userId: String): GroupDto

    /**
     * The authenticated user leaves the group (no uid is sent: the server uses the bearer token).
     * 403 not a member, 404 no such group, 409 the caller is the creator.
     */
    @DELETE("groups/{group_id}/members/me")
    suspend fun leaveGroup(@Path("group_id") groupId: String): GroupDeletedResponse

    /** Registered SplitMate users whose name or email starts with [query], at least 2 characters (the caller is excluded). */
    @GET("users/search")
    suspend fun searchUsers(@Query("query") query: String): List<UserSearchDto>

    /** Public profile (name, avatar) of every current member, in memberIds order. 403 not a member. */
    @GET("groups/{group_id}/members")
    suspend fun getGroupMembers(@Path("group_id") groupId: String): List<GroupMemberDto>

    /** The caller's own profile, including the saved (or default) avatar. 404 if there is no profile document. */
    @GET("users/me")
    suspend fun getMe(): UserMeDto

    /** Saves the caller's avatar (any subset of fields; 422 on an invalid value). Returns the full profile. */
    @PATCH("users/me/avatar")
    suspend fun updateMyAvatar(@Body body: AvatarDto): UserMeDto

    /** Everyone who DIRECTLY owes the signed-in user (from expenses they paid). No settlementId on the rows. */
    @GET("debts/receivable")
    suspend fun getReceivableDebts(): DirectDebtsResponse

    /** Everyone the signed-in user DIRECTLY owes. */
    @GET("debts/payable")
    suspend fun getPayableDebts(): DirectDebtsResponse

    /** One row of [getReceivableDebts] opened: per-group amounts plus the real expenses/payments behind them. 404: no such debt. */
    @GET("debts/receivable/{uid}")
    suspend fun getReceivableDebtDetail(@Path("uid") uid: String): DirectDebtDetailDto

    @GET("debts/payable/{uid}")
    suspend fun getPayableDebtDetail(@Path("uid") uid: String): DirectDebtDetailDto

    /** Creditor only. Several groups: [groupId] pays one, [allGroups] = true pays all (else 400). 409: already paid / stale amount. */
    @PATCH("debts/direct/{debtor_uid}/{creditor_uid}/paid")
    suspend fun markDirectDebtPaid(
        @Path("debtor_uid") debtorUid: String,
        @Path("creditor_uid") creditorUid: String,
        @Query("groupId") groupId: String?,
        @Query("allGroups") allGroups: Boolean?,
        @Body body: MarkPaidRequest
    ): DirectDebtPaidDto

    /** Debtor only ("שילמתי"): creates a pending claim, the debt does not change. 409: a claim is already pending / stale amount. */
    @POST("debts/direct/{debtor_uid}/{creditor_uid}/payment-claim")
    suspend fun claimDirectDebtPaid(
        @Path("debtor_uid") debtorUid: String,
        @Path("creditor_uid") creditorUid: String,
        @Query("groupId") groupId: String?,
        @Query("allGroups") allGroups: Boolean?,
        @Body body: MarkPaidRequest
    ): DirectDebtClaimDto

    /** Creditor only: confirms the pending claim(s); the claimed amount becomes a real payment. 409: no pending claim. */
    @PATCH("debts/direct/{debtor_uid}/{creditor_uid}/confirm-payment")
    suspend fun confirmDirectDebtClaim(
        @Path("debtor_uid") debtorUid: String,
        @Path("creditor_uid") creditorUid: String,
        @Query("groupId") groupId: String?,
        @Query("allGroups") allGroups: Boolean?
    ): DirectDebtPaidDto

    /** Creditor only, no body. Without [groupId] one reminder covers all groups. 201 -> the notification; 409 within 24h. */
    @POST("debts/direct/{debtor_uid}/{creditor_uid}/reminder")
    suspend fun sendDirectDebtReminder(
        @Path("debtor_uid") debtorUid: String,
        @Path("creditor_uid") creditorUid: String,
        @Query("groupId") groupId: String?
    ): NotificationDto

    @GET("groups/{group_id}/expenses")
    suspend fun getGroupExpenses(@Path("group_id") groupId: String): List<ExpenseDto>

    /** Who owes the payer what for ONE expense (server cent allocation). 403 not a member, 404 no such group/expense. */
    @GET("groups/{group_id}/expenses/{expense_id}/breakdown")
    suspend fun getExpenseBreakdown(
        @Path("group_id") groupId: String,
        @Path("expense_id") expenseId: String
    ): ExpenseBreakdownDto

    /** uid -> net balance. Positive = is owed, negative = owes. */
    @GET("groups/{group_id}/balances")
    suspend fun getGroupBalances(@Path("group_id") groupId: String): Map<String, BigDecimal>

    @GET("groups/{group_id}/settlement")
    suspend fun getGroupSettlement(@Path("group_id") groupId: String): SettlementResponse

    /** The real expenses behind one settlement ("ממה זה מורכב"); breakdownAvailable may be false. */
    @GET("groups/{group_id}/settlements/{settlement_id}/breakdown")
    suspend fun getSettlementBreakdown(
        @Path("group_id") groupId: String,
        @Path("settlement_id") settlementId: String
    ): BreakdownResponse

    /** Creditor only. 200 -> the settlement, now paid. */
    @PATCH("groups/{group_id}/settlements/{settlement_id}/paid")
    suspend fun markSettlementPaid(
        @Path("group_id") groupId: String,
        @Path("settlement_id") settlementId: String,
        @Body body: MarkPaidRequest
    ): SettlementDto

    /** Debtor only: "I paid". The settlement stays open (paymentClaimStatus = pending). Body is optional. */
    @POST("groups/{group_id}/settlements/{settlement_id}/payment-claim")
    suspend fun claimPayment(
        @Path("group_id") groupId: String,
        @Path("settlement_id") settlementId: String,
        @Body body: MarkPaidRequest
    ): SettlementDto

    /** Creditor only, needs a pending claim: the settlement becomes paid. Body is optional. */
    @PATCH("groups/{group_id}/settlements/{settlement_id}/confirm-payment")
    suspend fun confirmPayment(
        @Path("group_id") groupId: String,
        @Path("settlement_id") settlementId: String,
        @Body body: MarkPaidRequest
    ): SettlementDto

    /** Creditor only, open settlements only. 201 -> the notification created for the debtor. */
    @POST("groups/{group_id}/settlements/{settlement_id}/reminder")
    suspend fun sendReminder(
        @Path("group_id") groupId: String,
        @Path("settlement_id") settlementId: String
    ): NotificationDto

    /** The caller's notifications, newest first. */
    @GET("notifications")
    suspend fun getNotifications(): List<NotificationDto>

    @GET("notifications/unread-count")
    suspend fun getUnreadCount(): UnreadCountResponse

    @PATCH("notifications/{notification_id}/read")
    suspend fun markNotificationRead(@Path("notification_id") notificationId: String): NotificationDto

    /** Permanently deletes one of the caller's own notifications. A second delete of the same id is 404. */
    @DELETE("notifications/{notification_id}")
    suspend fun deleteNotification(@Path("notification_id") notificationId: String): NotificationDeletedResponse
}
