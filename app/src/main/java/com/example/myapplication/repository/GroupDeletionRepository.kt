package com.example.myapplication.repository

import android.util.Log
import com.example.myapplication.data.remote.ApiClient
import com.example.myapplication.data.remote.SplitMateApi
import kotlinx.coroutines.CancellationException

/** The outcome of asking the backend to delete a group. */
sealed class DeleteGroupResult {
    /** 200: the backend really deleted the group and its data. */
    object Deleted : DeleteGroupResult()

    /** 404: there is no such group (already deleted, or never existed). */
    object AlreadyGone : DeleteGroupResult()

    /** 403: the signed-in user did not create the group. */
    object NotAllowed : DeleteGroupResult()

    /** 401 even after the token was refreshed and the request retried once. */
    object SessionExpired : DeleteGroupResult()

    /** No connection, timeout, or a server error. */
    object Failed : DeleteGroupResult()
}

/** Deletes groups through the real backend: DELETE /groups/{group_id}. Nothing is deleted locally. */
class GroupDeletionRepository(
    private val api: SplitMateApi = ApiClient.api
) {

    suspend fun deleteGroup(groupId: String): DeleteGroupResult = try {
        val response = api.deleteGroup(groupId)
        Log.d(TAG, "DELETE groups/$groupId -> HTTP ${response.code()}")
        when {
            response.isSuccessful && response.body()?.success == true -> DeleteGroupResult.Deleted
            response.code() == 404 -> DeleteGroupResult.AlreadyGone
            response.code() == 403 -> DeleteGroupResult.NotAllowed
            response.code() == 401 -> DeleteGroupResult.SessionExpired
            else -> DeleteGroupResult.Failed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "DELETE groups/$groupId failed", e)
        DeleteGroupResult.Failed
    }

    private companion object {
        const val TAG = "GroupDeletionRepo"
    }
}
