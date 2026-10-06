package com.example.myapplication.ui.notifications

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.NotificationRepository
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NotificationsViewModel(
    private val repository: NotificationRepository = NotificationRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<NotificationsUiState>(NotificationsUiState.Loading)
    val state: StateFlow<NotificationsUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _openDebt = MutableSharedFlow<OpenDebt>(extraBufferCapacity = 1)
    val openDebt: SharedFlow<OpenDebt> = _openDebt.asSharedFlow()

    init {
        load()
    }

    /** [silent] keeps the list on screen while refetching (a failed silent refetch keeps the old list). */
    fun load(silent: Boolean = false) {
        if (!silent) _state.value = NotificationsUiState.Loading
        viewModelScope.launch {
            when (val r = repository.load()) {
                is Resource.Success -> _state.value = NotificationsUiState.Success(r.data)
                is Resource.Error -> if (!silent) _state.value = NotificationsUiState.Error(r.message)
                Resource.Loading -> Unit
            }
        }
    }

    private val markingRead = mutableSetOf<String>()

    /**
     * Checkmark: PATCH /notifications/{id}/read. The row turns read only after the server confirms, and the
     * list is then refetched so it matches the server. A read row sends nothing.
     */
    fun markRead(item: NotificationUi) {
        if (item.isRead) {
            Log.d(TAG, "markRead ignored (already read) id=${item.id}")
            return
        }
        if (!markingRead.add(item.id)) {
            Log.d(TAG, "markRead ignored (request in progress) id=${item.id}")
            return
        }
        Log.d(TAG, "markRead start id=${item.id}")
        viewModelScope.launch {
            when (val r = repository.markRead(item.id)) {
                NotificationRepository.OpResult.Success -> {
                    Log.d(TAG, "markRead success id=${item.id}")
                    updateItem(item.id) { it.copy(isRead = true) }
                    load(silent = true)
                }
                NotificationRepository.OpResult.Gone -> {
                    Log.w(TAG, "markRead failed id=${item.id}: not found (404)")
                    _messages.tryEmit(GONE_MESSAGE)
                    load(silent = true)
                }
                is NotificationRepository.OpResult.Failure -> {
                    Log.w(TAG, "markRead failed id=${item.id}: ${r.message}")
                    _messages.tryEmit(r.message)
                }
            }
            markingRead.remove(item.id)
        }
    }

    /** Ids whose row was swiped away and must be put back (the delete failed). */
    private val _restoreRow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val restoreRow: SharedFlow<String> = _restoreRow.asSharedFlow()

    private val deleting = mutableSetOf<String>()

    /**
     * Swipe: DELETE /notifications/{id}. The row leaves the list only after the server confirms (200), or
     * says it is already gone (404). On any other failure the row is restored and an error is shown.
     * Unread count: the server computes it from the stored notifications; Home refetches it on resume.
     */
    fun delete(item: NotificationUi) {
        if (!deleting.add(item.id)) return
        viewModelScope.launch {
            when (val r = repository.delete(item.id)) {
                NotificationRepository.OpResult.Success -> {
                    removeItem(item.id)
                    load(silent = true)
                }
                NotificationRepository.OpResult.Gone -> {
                    _messages.tryEmit(GONE_MESSAGE)
                    removeItem(item.id)
                    load(silent = true)
                }
                is NotificationRepository.OpResult.Failure -> {
                    _messages.tryEmit(r.message)
                    _restoreRow.tryEmit(item.id)
                }
            }
            deleting.remove(item.id)
        }
    }

    /** Marks the notification read on the server (row updates only after success), then opens its debt. */
    fun onNotificationClick(item: NotificationUi) {
        viewModelScope.launch {
            if (!item.isRead) {
                when (val r = repository.markRead(item.id)) {
                    NotificationRepository.OpResult.Success -> updateItem(item.id) { it.copy(isRead = true) }
                    NotificationRepository.OpResult.Gone -> {
                        // The notification no longer exists on the server: show that and refresh, don't navigate.
                        _messages.tryEmit(GONE_MESSAGE)
                        load(silent = true)
                        return@launch
                    }
                    is NotificationRepository.OpResult.Failure -> _messages.tryEmit(r.message)
                }
            }
            val groupId = item.groupId
            val settlementId = item.settlementId
            if (!groupId.isNullOrBlank() && !settlementId.isNullOrBlank()) {
                _openDebt.tryEmit(OpenDebt(groupId, settlementId))
            }
        }
    }

    private fun updateItem(id: String, change: (NotificationUi) -> NotificationUi) {
        val current = _state.value as? NotificationsUiState.Success ?: return
        _state.value = NotificationsUiState.Success(current.items.map { if (it.id == id) change(it) else it })
    }

    private fun removeItem(id: String) {
        val current = _state.value as? NotificationsUiState.Success ?: return
        _state.value = NotificationsUiState.Success(current.items.filterNot { it.id == id })
    }

    private companion object {
        const val TAG = "NotificationsVM"
        const val GONE_MESSAGE = "ההתראה כבר לא קיימת"
    }
}
