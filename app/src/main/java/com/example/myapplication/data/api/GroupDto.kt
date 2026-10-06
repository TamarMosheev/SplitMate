package com.example.myapplication.data.api

import com.example.myapplication.data.model.Group
import com.example.myapplication.utils.parseTimestamp

/** DELETE /groups/{id} -> 200 {"success": true, "groupId": "..."} */
data class GroupDeletedResponse(
    val success: Boolean? = null,
    val groupId: String? = null
)

/** One item of GET /groups: the Firestore document id plus the stored group fields. */
data class GroupDto(
    val id: String? = null,
    val name: String? = null,
    val icon: String? = null,
    val createdBy: String? = null,
    val memberIds: List<String>? = null,
    /** Serialized by FastAPI; usually an ISO-8601 string. Parsed leniently. */
    val createdAt: String? = null
) {
    /** Null when the item has no id (cannot be opened later). */
    fun toGroup(): Group? {
        val groupId = id?.takeIf { it.isNotBlank() } ?: return null
        return Group(
            id = groupId,
            name = name.orEmpty(),
            icon = icon.orEmpty(),
            createdBy = createdBy.orEmpty(),
            memberIds = memberIds.orEmpty(),
            createdAtMillis = parseMillis(createdAt)
        )
    }

    private fun parseMillis(value: String?): Long =
        parseTimestamp(value)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
}
