package com.example.myapplication.repository

import android.util.Log
import com.example.myapplication.data.api.ApiErrorMapper
import com.example.myapplication.data.api.ApiService
import com.example.myapplication.data.api.NotificationDto
import com.example.myapplication.data.api.RetrofitClient
import com.example.myapplication.ui.notifications.NotificationUi
import com.example.myapplication.utils.Resource
import com.example.myapplication.utils.formatTimestamp
import com.example.myapplication.utils.parseTimestamp
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** Notifications and the unread count, all from the backend. Sender names come from users/{uid}.name. */
class NotificationRepository(
    private val apiService: ApiService = RetrofitClient.apiService
) {

    suspend fun unreadCount(): Resource<Int> = try {
        Resource.Success(apiService.getUnreadCount().count ?: 0)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "GET /notifications/unread-count failed", e)
        Resource.Error(ApiErrorMapper.message(e))
    }

    /** Newest first. */
    suspend fun load(): Resource<List<NotificationUi>> {
        val dtos = try {
            apiService.getNotifications()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "GET /notifications failed", e)
            return Resource.Error(ApiErrorMapper.message(e))
        }

        val names = UserNameResolver.resolve(dtos.mapNotNull { it.senderUid }.toSet())
        // Group names are best effort: a failure only leaves the group line out.
        val groupNames = try {
            apiService.getGroups().mapNotNull { g -> g.id?.let { it to g.name.orEmpty() } }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "GET /groups for notification group names failed", e)
            emptyMap()
        }

        return Resource.Success(
            dtos.mapNotNull { it.toUi(names, groupNames) }
                .sortedByDescending { parseTimestamp(it.createdAtRaw)?.toInstant() }
        )
    }

    /** Outcome of a write on one notification. [Gone]: the server says it no longer exists (404). */
    sealed class OpResult {
        object Success : OpResult()
        object Gone : OpResult()
        data class Failure(val message: String) : OpResult()
    }

    /** PATCH /notifications/{id}/read. Idempotent. */
    suspend fun markRead(id: String): OpResult = op("PATCH /notifications/$id/read", READ_FAILED) {
        apiService.markNotificationRead(id)
    }

    /** DELETE /notifications/{id}. Permanent; a repeat is 404 ([OpResult.Gone]). */
    suspend fun delete(id: String): OpResult = op("DELETE /notifications/$id", DELETE_FAILED) {
        apiService.deleteNotification(id)
    }

    private suspend fun op(label: String, genericFailure: String, block: suspend () -> Any): OpResult = try {
        block()
        OpResult.Success
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "$label failed" + ((e as? HttpException)?.let { " http=${it.code()}" } ?: ""), e)
        val authProblem = e is IOException && e.message in setOf("NOT_SIGNED_IN", "NO_TOKEN", "TOKEN_FAILED")
        when {
            e is HttpException && e.code() == 404 -> OpResult.Gone
            e is HttpException && e.code() == 401 || authProblem -> OpResult.Failure(ApiErrorMapper.message(e))
            e is IOException || (e is HttpException && e.code() in 500..599) ->
                OpResult.Failure("לא ניתן להתחבר לשרת. נסי שוב.")
            else -> OpResult.Failure(genericFailure)
        }
    }

    private fun NotificationDto.toUi(names: Map<String, String>, groupNames: Map<String, String>): NotificationUi? {
        val nid = id ?: return null
        val sender = senderUid?.let { names[it] }
        return NotificationUi(
            id = nid,
            type = type,
            senderName = sender,
            // Payment notifications get a short Hebrew line from the real type and sender name;
            // reminders and any future type keep the server's own message.
            message = when (type) {
                "payment_claim" -> "${sender ?: "משתמש"} עדכן/ה שהתשלום נשלח"
                "payment_confirmed" -> "התשלום שלך אושר"
                "payment_claim_rejected" -> "התשלום שלך לא אושר"
                else -> message.orEmpty()
            },
            amount = amount,
            groupId = groupId,
            groupName = groupId?.let { groupNames[it] },
            settlementId = settlementId,
            createdAtRaw = createdAt,
            createdAtText = formatTimestamp(createdAt),
            isRead = isRead == true
        )
    }

    private companion object {
        const val TAG = "NotificationRepo"
        const val READ_FAILED = "לא ניתן לעדכן את ההתראה. נסי שוב."
        const val DELETE_FAILED = "לא ניתן למחוק את ההתראה. נסי שוב."
    }
}
