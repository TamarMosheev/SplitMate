package com.example.myapplication.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.NotificationRepository
import com.example.myapplication.ui.group.GroupDeleter
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
    private val notificationRepository: NotificationRepository = NotificationRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** One-off messages (e.g. a failed save). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Unread notifications for the bell badge; 0 hides it. Polled (no push yet). */
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private var observeJob: Job? = null

    /** The shared group deletion (same one My Balance and Group Details use). */
    val deleter = GroupDeleter(viewModelScope)

    /**
     * Confirmed by the user in the UI. The group leaves the list only when the refetched GET /groups no
     * longer returns it; the unread count is refreshed because the server also deletes its notifications.
     */
    fun deleteGroup(group: GroupItemUi) {
        if (group.canDelete) deleter.delete(group.id)
    }

    /** GET /notifications/unread-count. A failure keeps the previous badge value. */
    fun refreshUnreadCount() {
        viewModelScope.launch {
            val r = notificationRepository.unreadCount()
            if (r is Resource.Success) _unreadCount.value = r.data
        }
    }

    init {
        load()
        viewModelScope.launch { repository.ensureUserProfile() }
        viewModelScope.launch {
            deleter.events.collect { e ->
                if (e.groupIsGone) {
                    repository.refreshGroups()
                    refreshUnreadCount()
                }
            }
        }
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

    /** Re-fetches GET /groups (e.g. after returning from creating a group). */
    fun refreshGroups() = repository.refreshGroups()

    fun updateName(name: String) {
        viewModelScope.launch {
            val result = repository.updateName(name.trim())
            if (result is Resource.Error) _messages.tryEmit(result.message)
        }
    }
}
