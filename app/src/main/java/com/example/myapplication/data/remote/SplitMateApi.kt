package com.example.myapplication.data.remote

import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Path

/**
 * The SplitMate backend (see docs/ANDROID_API_CONTRACT.md in the backend project).
 *
 * Every call needs `Authorization: Bearer <Firebase ID token>`; [ApiClient] adds it. Responses are returned
 * as [Response] so callers can map the HTTP status code (200, 401, 403, 404, ...) themselves.
 */
interface SplitMateApi {

    /** DELETE /groups/{group_id}: permanently deletes a group and its data. Only the group's creator may. */
    @DELETE("groups/{groupId}")
    suspend fun deleteGroup(@Path("groupId") groupId: String): Response<GroupDeletedResponse>
}

/** `{"success": true, "groupId": "..."}` */
data class GroupDeletedResponse(
    val success: Boolean,
    val groupId: String
)
