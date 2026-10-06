package com.example.myapplication.repository

import android.util.Log
import com.example.myapplication.data.api.ApiErrorMapper
import com.example.myapplication.data.api.ApiService
import com.example.myapplication.data.api.RetrofitClient
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

/** The one place that calls DELETE /groups/{id}; every screen's delete goes through here. */
class GroupDeleteRepository(
    private val apiService: ApiService = RetrofitClient.apiService
) {

    /** Outcome of the delete. [gone]: the group no longer exists (404), so lists should refresh. */
    data class Result(val success: Boolean, val message: String, val gone: Boolean = false)

    suspend fun delete(groupId: String): Result = try {
        apiService.deleteGroup(groupId)
        Result(success = true, message = DELETED)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "DELETE /groups/$groupId failed", e)
        val http = e as? HttpException
        when (http?.code()) {
            401 -> Result(false, ApiErrorMapper.message(e))
            403 -> Result(false, "אין לך הרשאה למחוק את הקבוצה")
            404 -> Result(false, "הקבוצה כבר לא קיימת", gone = true)
            else -> Result(false, "לא ניתן למחוק את הקבוצה. נסי שוב.")
        }
    }

    companion object {
        const val DELETED = "הקבוצה נמחקה בהצלחה"
        private const val TAG = "GroupDeleteRepo"
    }
}
