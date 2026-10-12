package com.example.myapplication.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.BalanceRepository
import com.example.myapplication.repository.GroupMembersRepository
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Loads one group's details from the backend using only the groupId it was opened with. */
class GroupDetailsViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val repository = BalanceRepository()
    private val groupId: String = savedStateHandle.get<String>(EXTRA_GROUP_ID).orEmpty()

    private val _state = MutableStateFlow<GroupDetailsUiState>(GroupDetailsUiState.Loading)
    val state: StateFlow<GroupDetailsUiState> = _state.asStateFlow()

    /** The shared group deletion (same one Home and My Balance use). */
    val deleter = GroupDeleter(viewModelScope)

    private val membersRepository = GroupMembersRepository()

    /** uid being removed right now (disables its button; blocks double taps). */
    private val _removingUid = MutableStateFlow<String?>(null)
    val removingUid: StateFlow<String?> = _removingUid.asStateFlow()

    /** One-shot messages (errors / success) for the Activity to toast. */
    private val _messages = Channel<RemoveResult>(Channel.BUFFERED)
    val removeResults = _messages.receiveAsFlow()

    /** [leftGroup]: the signed-in user left, so Group Details must close. */
    data class RemoveResult(val message: String?, val leftGroup: Boolean = false)

    /** The signed-in user leaves. Nothing changes locally until the backend confirms; then the screen closes. */
    fun leaveGroup() {
        val canLeave = (_state.value as? GroupDetailsUiState.Success)?.content?.canLeave == true
        if (_removingUid.value != null || groupId.isEmpty() || !canLeave) return
        _removingUid.value = LEAVING
        viewModelScope.launch {
            try {
                when (val r = membersRepository.leaveGroup(groupId)) {
                    is Resource.Success -> _messages.send(RemoveResult("עזבת את הקבוצה", leftGroup = true))
                    is Resource.Error -> {
                        _messages.send(RemoveResult(r.message))
                        load(silent = true)
                    }
                    Resource.Loading -> Unit
                }
            } finally {
                _removingUid.value = null
            }
        }
    }

    init {
        load()
    }

    /**
     * Calls the real DELETE endpoint. Nothing changes locally first: on success the details and balances are
     * refetched from the backend; on a stale 404/409 they are refetched too.
     */
    fun removeMember(member: MemberBalanceUi) {
        if (_removingUid.value != null || groupId.isEmpty() || !member.canRemove) return
        _removingUid.value = member.uid
        viewModelScope.launch {
            try {
                when (val r = membersRepository.removeMember(groupId, member.uid)) {
                    is Resource.Success -> {
                        _messages.send(RemoveResult("${member.name ?: "החבר"} הוסר מהקבוצה"))
                        load(silent = true) // details + balances from the backend; the row goes only now
                    }
                    is Resource.Error -> {
                        _messages.send(RemoveResult(r.message))
                        load(silent = true) // permissions / membership may have changed meanwhile
                    }
                    Resource.Loading -> Unit
                }
            } finally {
                _removingUid.value = null
            }
        }
    }

    /** Confirmed by the user in the UI. The Activity closes this screen once the group is gone. */
    fun deleteGroup() {
        val canDelete = (_state.value as? GroupDetailsUiState.Success)?.content?.canDelete == true
        if (canDelete && groupId.isNotEmpty()) deleter.delete(groupId)
    }

    /** [silent] keeps the current content on screen while refetching (e.g. on return from another screen). */
    fun load(silent: Boolean = false) {
        if (groupId.isEmpty()) {
            _state.value = GroupDetailsUiState.Error("לא נבחרה קבוצה")
            return
        }
        if (!silent) _state.value = GroupDetailsUiState.Loading
        viewModelScope.launch {
            when (val r = repository.loadGroupDetails(groupId)) {
                is Resource.Success -> _state.value = GroupDetailsUiState.Success(r.data)
                is Resource.Error ->
                    // A failed silent refresh keeps what is already shown.
                    if (!silent || _state.value !is GroupDetailsUiState.Success) {
                        _state.value = GroupDetailsUiState.Error(r.message)
                    }
                Resource.Loading -> Unit
            }
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
        private const val LEAVING = "__leaving__"
    }
}
