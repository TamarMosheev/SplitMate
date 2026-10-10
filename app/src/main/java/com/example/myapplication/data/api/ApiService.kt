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

    /** Registered SplitMate users whose name or email starts with [query], at least 2 characters (the caller is excluded). */
    @GET("users/search")
    suspend fun searchUsers(@Query("query") query: String): List<UserSearchDto>

    @GET("groups/{group_id}/expenses")
    suspend fun getGroupExpenses(@Path("group_id") groupId: String): List<ExpenseDto>

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
