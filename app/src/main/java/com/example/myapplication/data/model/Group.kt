package com.example.myapplication.data.model

const val GROUPS_COLLECTION = "groups"

/** groups/{id}: membership is the [memberIds] array of Firebase Auth UIDs. */
data class Group(
    val id: String,
    val name: String,
    val icon: String,
    val createdBy: String,
    val memberIds: List<String>,
    val createdAtMillis: Long
)

/** A real user from users/{uid}. */
data class GroupMember(
    val uid: String,
    val name: String,
    val email: String
)
