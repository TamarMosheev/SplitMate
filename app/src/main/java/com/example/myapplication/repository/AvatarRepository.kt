package com.example.myapplication.repository

import android.util.Log
import com.example.myapplication.data.api.ApiAction
import com.example.myapplication.data.api.ApiErrorMapper
import com.example.myapplication.data.api.ApiService
import com.example.myapplication.data.api.RetrofitClient
import com.example.myapplication.data.api.UserMeDto
import com.example.myapplication.data.model.Avatar
import com.example.myapplication.data.model.toAvatar
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.CancellationException

/** The signed-in user's profile + avatar. The backend is the only source of truth; nothing is cached locally. */
class AvatarRepository(private val api: ApiService = RetrofitClient.apiService) {

    data class Profile(val name: String?, val avatar: Avatar)

    /** GET /users/me */
    suspend fun loadMe(): Resource<Profile> = call(ApiAction.LOAD_PROFILE, "GET users/me") { api.getMe() }

    /** PATCH /users/me/avatar with the exact selected ids; returns what the server saved. */
    suspend fun saveAvatar(avatar: Avatar): Resource<Profile> =
        call(ApiAction.SAVE_AVATAR, "PATCH users/me/avatar") { api.updateMyAvatar(avatar.toDto()) }

    private suspend fun call(action: ApiAction, what: String, block: suspend () -> UserMeDto): Resource<Profile> = try {
        val me = block()
        Resource.Success(Profile(me.name?.takeIf { it.isNotBlank() } ?: me.email?.takeIf { it.isNotBlank() }, me.avatar.toAvatar()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "$what failed: ${e.javaClass.simpleName} ${(e as? retrofit2.HttpException)?.code()}")
        Resource.Error(ApiErrorMapper.message(e, action))
    }

    private companion object {
        const val TAG = "AvatarRepository"
    }
}
