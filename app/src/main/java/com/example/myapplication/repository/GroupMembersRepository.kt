package com.example.myapplication.repository

import com.example.myapplication.data.api.AddMemberRequest
import com.example.myapplication.data.api.ApiAction
import com.example.myapplication.data.api.ApiErrorMapper
import com.example.myapplication.data.api.RetrofitClient
import com.example.myapplication.data.model.GroupMember
import com.example.myapplication.utils.Resource
import android.util.Log
import kotlinx.coroutines.CancellationException

/** Group membership through the backend: search registered users, add one to a group, read current members. */
class GroupMembersRepository {

    private val api = RetrofitClient.apiService

    /** The group's current member uids, straight from the backend. */
    suspend fun currentMemberIds(groupId: String): Resource<Set<String>> = call(ApiAction.GENERAL) {
        api.getGroup(groupId).memberIds.orEmpty().toSet()
    }

    suspend fun search(query: String): Resource<List<GroupMember>> = call(ApiAction.GENERAL, "GET users/search") {
        Log.d(TAG, "GET users/search query='$query'")
        api.searchUsers(query).also { Log.d(TAG, "users/search -> 200, ${it.size} result(s): $it") }.mapNotNull { u ->
            val uid = u.uid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GroupMember(uid, u.name.orEmpty(), u.email.orEmpty())
        }
    }

    /** Success only when the backend accepted it; returns the group's member uids after the change. */
    suspend fun addMember(groupId: String, uid: String): Resource<Set<String>> = call(ApiAction.ADD_MEMBER, "POST groups/$groupId/members") {
        Log.d(TAG, "POST groups/$groupId/members userId=$uid")
        api.addGroupMember(groupId, AddMemberRequest(uid)).memberIds.orEmpty().toSet()
    }

    private suspend fun <T> call(action: ApiAction, what: String = "", block: suspend () -> T): Resource<T> = try {
        Resource.Success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val http = e as? retrofit2.HttpException
        Log.e(TAG, "$what failed: status=${http?.code()} body=${http?.response()?.errorBody()?.string()} error=${e.javaClass.simpleName}")
        Resource.Error(ApiErrorMapper.message(e, action))
    }

    private companion object {
        const val TAG = "GroupMembersRepo"
    }
}
