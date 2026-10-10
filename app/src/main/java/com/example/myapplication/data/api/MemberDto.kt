package com.example.myapplication.data.api

/** POST /groups/{id}/members body. */
data class AddMemberRequest(val userId: String)

/** One item of GET /users/search. */
data class UserSearchDto(
    val uid: String? = null,
    val name: String? = null,
    val email: String? = null,
    val photoUrl: String? = null
)
