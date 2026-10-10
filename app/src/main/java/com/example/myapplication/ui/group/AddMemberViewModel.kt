package com.example.myapplication.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.model.GroupMember
import com.example.myapplication.repository.GroupMembersRepository
import com.example.myapplication.utils.Resource
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddMemberUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<GroupMember> = emptyList(),
    /** Real current members of the group (from the backend); results in this set are not addable. */
    val memberIds: Set<String> = emptySet(),
    /** The uid being added right now (blocks duplicate taps). */
    val addingUid: String? = null,
    val error: String? = null,
    val searched: Boolean = false
) {
    val busy: Boolean get() = addingUid != null
}

class AddMemberViewModel(savedStateHandle: SavedStateHandle) : ViewModel() {

    private val repository = GroupMembersRepository()
    private val groupId: String = savedStateHandle.get<String>(EXTRA_GROUP_ID).orEmpty()
    private val myUid: String? = FirebaseAuth.getInstance().currentUser?.uid

    private val _state = MutableStateFlow(AddMemberUiState())
    val state: StateFlow<AddMemberUiState> = _state.asStateFlow()

    /** One-shot messages, e.g. "המשתמש נוסף לקבוצה". */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        refreshMembers()
    }

    private fun refreshMembers() {
        if (groupId.isEmpty()) return
        viewModelScope.launch {
            when (val r = repository.currentMemberIds(groupId)) {
                is Resource.Success -> _state.update { it.copy(memberIds = r.data) }
                is Resource.Error -> _state.update { it.copy(error = r.message) }
                Resource.Loading -> Unit
            }
        }
    }

    fun onQueryChanged(text: String) {
        val q = text.trim()
        _state.update { it.copy(query = text, error = null) }
        searchJob?.cancel()
        if (q.length < MIN_QUERY_LENGTH) {
            _state.update { it.copy(results = emptyList(), searching = false, searched = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _state.update { it.copy(searching = true) }
            when (val r = repository.search(q)) {
                is Resource.Success -> _state.update {
                    it.copy(results = r.data.filter { m -> m.uid != myUid }, searching = false, searched = true)
                }
                is Resource.Error -> _state.update {
                    it.copy(results = emptyList(), searching = false, searched = true, error = r.message)
                }
                Resource.Loading -> Unit
            }
        }
    }

    fun add(member: GroupMember) {
        val s = _state.value
        if (s.busy || member.uid in s.memberIds || groupId.isEmpty()) return
        _state.update { it.copy(addingUid = member.uid, error = null) }
        viewModelScope.launch {
            when (val r = repository.addMember(groupId, member.uid)) {
                is Resource.Success -> {
                    // The backend confirmed: take the member list it returned.
                    _state.update { it.copy(addingUid = null, memberIds = r.data) }
                    _messages.send("המשתמש נוסף לקבוצה")
                }
                is Resource.Error -> {
                    _state.update { it.copy(addingUid = null, error = r.message) }
                    // A 409 means the list was stale: reload the real members.
                    refreshMembers()
                }
                Resource.Loading -> Unit
            }
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
        private const val DEBOUNCE_MS = 300L
        private const val MIN_QUERY_LENGTH = 2
    }
}
