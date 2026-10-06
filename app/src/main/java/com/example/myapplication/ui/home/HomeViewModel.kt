package com.example.myapplication.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.DeleteGroupResult
import com.example.myapplication.repository.GroupDeletionRepository
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: HomeRepository = HomeRepository(),
    private val deletion: GroupDeletionRepository = GroupDeletionRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** One-off messages (e.g. a failed save). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** The group being deleted right now (null = none). Drives the row spinner and blocks double taps. */
    private val _deletingGroupId = MutableStateFlow<String?>(null)
    val deletingGroupId: StateFlow<String?> = _deletingGroupId.asStateFlow()

    private var observeJob: Job? = null

    init {
        load()
        viewModelScope.launch { repository.ensureUserProfile() }
    }

    /** (Re)starts the live observation. The listener is removed when the job/ViewModel is cancelled. */
    fun load() {
        observeJob?.cancel()
        _state.value = HomeUiState.Loading
        observeJob = viewModelScope.launch {
            repository.observeHome().collect { result ->
                _state.value = when (result) {
                    is Resource.Success -> HomeUiState.Success(result.data)
                    is Resource.Error -> HomeUiState.Error(result.message)
                    Resource.Loading -> HomeUiState.Loading
                }
            }
        }
    }

    fun updateName(name: String) {
        viewModelScope.launch {
            val result = repository.updateName(name.trim())
            if (result is Resource.Error) _messages.tryEmit(result.message)
        }
    }

    /**
     * Deletes a group through the real backend (DELETE /groups/{id}). The group leaves the list only after the
     * backend confirms the delete; on any failure it stays exactly as it was.
     */
    fun deleteGroup(groupId: String) {
        if (_deletingGroupId.value != null) return // a delete is already running: ignore double taps
        _deletingGroupId.value = groupId
        viewModelScope.launch {
            try {
                when (deletion.deleteGroup(groupId)) {
                    DeleteGroupResult.Deleted -> {
                        removeGroupFromState(groupId) // backend confirmed; the live Firestore listener confirms it too
                        _messages.tryEmit("הקבוצה נמחקה בהצלחה")
                    }
                    // The group is already gone on the server; the live group list drops it by itself.
                    DeleteGroupResult.AlreadyGone -> _messages.tryEmit("הקבוצה כבר לא קיימת")
                    DeleteGroupResult.NotAllowed -> _messages.tryEmit("אין לך הרשאה למחוק את הקבוצה")
                    DeleteGroupResult.SessionExpired -> _messages.tryEmit("פג תוקף ההתחברות. התחברו מחדש ונסו שוב")
                    DeleteGroupResult.Failed -> _messages.tryEmit("לא ניתן למחוק את הקבוצה. נסי שוב.")
                }
            } finally {
                _deletingGroupId.value = null
            }
        }
    }

    private fun removeGroupFromState(groupId: String) {
        _state.update { state ->
            if (state is HomeUiState.Success) {
                HomeUiState.Success(state.content.copy(groups = state.content.groups.filterNot { it.id == groupId }))
            } else state
        }
    }
}
