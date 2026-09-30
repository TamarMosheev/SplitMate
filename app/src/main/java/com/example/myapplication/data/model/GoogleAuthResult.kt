package com.example.myapplication.data.model

/** Result of authenticating with Google: the Firebase user plus whether a users/{uid} profile exists. */
data class GoogleAuthResult(
    val user: User,
    val photoUrl: String?,
    val hasProfile: Boolean
)
