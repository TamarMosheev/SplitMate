package com.example.myapplication.ui.group

import androidx.core.view.isVisible
import com.example.myapplication.repository.GroupDeleteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * THE rule for showing a group's delete (trash) action, used by every group card and screen.
 * Contract: only the person who created the group may delete it, i.e. `group.createdBy == current uid`.
 * It depends on nothing else (not on balances, members, notifications, the screen or a user's name).
 * The server enforces the same rule (403).
 */
fun canDeleteGroup(createdBy: String?, currentUid: String?): Boolean =
    !createdBy.isNullOrBlank() && !currentUid.isNullOrBlank() && createdBy == currentUid

/**
 * TEMPORARY debug: one line per group with the real values behind the trash decision.
 * Uses Log.i on purpose: some devices drop Log.d from app tags.
 */
fun logGroupDeleteDebug(screen: String, id: String, name: String, createdBy: String?, currentUid: String?, canDelete: Boolean) {
    android.util.Log.i(
        "GroupDeleteDebug",
        "data screen=$screen groupId=$id groupName=$name createdBy=$createdBy currentUid=$currentUid canDelete=$canDelete"
    )
}

private fun visibilityName(v: Int) = when (v) {
    android.view.View.VISIBLE -> "VISIBLE"
    android.view.View.INVISIBLE -> "INVISIBLE"
    else -> "GONE"
}

/**
 * THE one place a group's trash view is shown/hidden, called from EVERY group-rendering bind
 * (Home card, My Balance card, Group Details header). It always sets the visibility explicitly from
 * `createdBy == live Firebase uid`, so neither the XML default nor a recycled RecyclerView holder can leak.
 */
fun bindGroupDeleteAction(
    screen: String,
    adapter: String,
    groupId: String,
    groupName: String,
    createdBy: String?,
    trash: android.view.View,
    deleting: Boolean = false
) {
    val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
    val canDelete = canDeleteGroup(createdBy, uid)
    val before = trash.visibility
    trash.isVisible = canDelete
    trash.isEnabled = !deleting
    android.util.Log.i(
        "GroupDeleteDebug",
        "bind screen=$screen adapter=$adapter groupId=$groupId groupName=$groupName createdBy=$createdBy " +
            "currentUid=$uid canDelete=$canDelete trashViewExists=true " +
            "trashVisibilityBefore=${visibilityName(before)} trashVisibilityAfter=${visibilityName(trash.visibility)}"
    )
}

/** What happened to a delete request. [message] is the Hebrew text to show. */
data class GroupDeleteEvent(
    val groupId: String,
    val message: String,
    val deleted: Boolean,
    /** 404: the group is gone already, so screens should refresh like after a delete. */
    val gone: Boolean
) {
    /** True when the group no longer exists and lists must reload / the open group screen must close. */
    val groupIsGone: Boolean get() = deleted || gone
}

/**
 * The shared delete behaviour used by every ViewModel that shows a group with a delete action
 * (Home, My Balance, Group Details): one in-flight guard, one backend call, one result type.
 * It changes no screen state itself: each screen reacts to [events] by reloading from the server.
 */
class GroupDeleter(
    private val scope: CoroutineScope,
    private val repository: GroupDeleteRepository = GroupDeleteRepository()
) {
    private val _deletingIds = MutableStateFlow<Set<String>>(emptySet())

    /** Groups whose deletion is running (loading state, blocks a second tap). */
    val deletingIds: StateFlow<Set<String>> = _deletingIds.asStateFlow()

    private val _events = MutableSharedFlow<GroupDeleteEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<GroupDeleteEvent> = _events.asSharedFlow()

    /** Call only after the user confirmed. Does nothing if this group is already being deleted. */
    fun delete(groupId: String) {
        if (groupId in _deletingIds.value) return
        _deletingIds.update { it + groupId }
        scope.launch {
            val r = repository.delete(groupId)
            _events.tryEmit(GroupDeleteEvent(groupId, r.message, deleted = r.success, gone = r.gone))
            _deletingIds.update { it - groupId }
        }
    }
}
