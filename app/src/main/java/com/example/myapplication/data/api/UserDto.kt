package com.example.myapplication.data.api

/**
 * The backend avatar object (docs/ANDROID_API_CONTRACT.md, "Avatars"): stable string ids, never drawable ids.
 * Fields are nullable only so a malformed response cannot crash Gson; the server always sends all six.
 */
data class AvatarDto(
    val hairStyle: String? = null,
    val faceStyle: String? = null,
    val glassesEnabled: Boolean? = null,
    val skinTone: String? = null,
    val hairColor: String? = null,
    val backgroundColor: String? = null
)

/** GET /users/me and the response of PATCH /users/me/avatar. */
data class UserMeDto(
    val uid: String? = null,
    val name: String? = null,
    val email: String? = null,
    val avatar: AvatarDto? = null
)

/** One item of GET /groups/{id}/members. */
data class GroupMemberDto(
    val uid: String? = null,
    val displayName: String? = null,
    val email: String? = null,
    val photoUrl: String? = null,
    val avatar: AvatarDto? = null
)
