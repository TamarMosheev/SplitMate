package com.example.myapplication.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.model.GroupMember
import com.example.myapplication.repository.GroupRepository
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CreateGroupUiState(
    val name: String = "",
    val icon: String = GROUP_ICONS.first(),
    val me: GroupMember? = null,
    val members: List<GroupMember> = emptyList(),
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<GroupMember> = emptyList(),
    val searchMessage: String? = null,
    val nameError: String? = null,
    val saving: Boolean = false,
    val error: String? = null
)

@OptIn(FlowPreview::class)
class CreateGroupViewModel(
    private val repository: GroupRepository = GroupRepository()
) : ViewModel() {

    private val groupId = repository.newGroupId()

    private val _state = MutableStateFlow(CreateGroupUiState())
    val state: StateFlow<CreateGroupUiState> = _state.asStateFlow()

    private val searchQueries = MutableStateFlow("")

    /** Emitted once, only after Firestore confirmed the write. The screen navigates on this and nothing else. */
    private val _groupCreated = Channel<String>(Channel.BUFFERED)
    val groupCreated: Flow<String> = _groupCreated.receiveAsFlow()

    init {
        viewModelScope.launch {
            searchQueries.debounce(SEARCH_DEBOUNCE_MS).distinctUntilChanged().collectLatest(::runSearch)
        }
        viewModelScope.launch {
            when (val result = repository.loadCurrentUser()) {
                is Resource.Success -> _state.update { it.copy(me = result.data) }
                is Resource.Error -> _state.update { it.copy(error = result.message) }
                Resource.Loading -> Unit
            }
        }
    }

    fun onNameChanged(name: String) = _state.update { it.copy(name = name, nameError = null) }

    fun onIconSelected(icon: String) = _state.update { it.copy(icon = icon) }

    /** Called on every keystroke; the actual Firestore query is debounced. */
    fun onSearchQueryChanged(text: String) {
        val q = text.trim()
        _state.update {
            it.copy(
                searchQuery = q,
                searchMessage = null,
                searching = q.isNotEmpty(),
                searchResults = if (q.isEmpty()) emptyList() else it.searchResults
            )
        }
        searchQueries.value = q
    }

    fun addMember(member: GroupMember) = _state.update { s ->
        if (s.members.any { it.uid == member.uid } || member.uid == s.me?.uid) s
        else s.copy(
            members = s.members + member,
            searchResults = s.searchResults.filterNot { it.uid == member.uid }
        )
    }

    fun removeMember(uid: String) = _state.update { s ->
        s.copy(members = s.members.filterNot { it.uid == uid })
    }

    fun create() {
        val s = _state.value
        if (s.saving) return
        val name = s.name.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(nameError = "יש להזין שם קבוצה") }
            return
        }
        val me = s.me ?: return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = repository.createGroup(groupId, name, s.icon, listOf(me.uid) + s.members.map { it.uid })
            when (result) {
                is Resource.Success -> {
                    _state.update { it.copy(saving = false) }
                    _groupCreated.send(groupId)
                }
                is Resource.Error -> _state.update { it.copy(saving = false, error = result.message) }
                Resource.Loading -> Unit
            }
        }
    }

    private suspend fun runSearch(query: String) {
        if (query.isEmpty()) {
            _state.update { it.copy(searching = false, searchResults = emptyList(), searchMessage = null) }
            return
        }
        _state.update { it.copy(searching = true) }
        val result = repository.searchUsersByName(query)
        _state.update { s ->
            if (s.searchQuery != query) return@update s // superseded by newer typing
            when (result) {
                is Resource.Error -> s.copy(searching = false, searchResults = emptyList(), searchMessage = result.message)
                is Resource.Success -> {
                    val visible = result.data.filter { u -> u.uid != s.me?.uid && s.members.none { it.uid == u.uid } }
                    s.copy(
                        searching = false,
                        searchResults = visible,
                        searchMessage = if (visible.isEmpty()) "לא נמצאו משתמשים" else null
                    )
                }
                Resource.Loading -> s
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
