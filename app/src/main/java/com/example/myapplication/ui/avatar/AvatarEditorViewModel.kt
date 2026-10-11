package com.example.myapplication.ui.avatar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.model.Avatar
import com.example.myapplication.repository.AvatarRepository
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AvatarEditorState(
    val loading: Boolean = true,
    /** Set when GET /users/me failed: the editor is hidden and a retry is offered. */
    val loadError: String? = null,
    val name: String? = null,
    /** The avatar being edited (drives the live preview). Only saved when the user taps save. */
    val avatar: Avatar = Avatar.DEFAULT,
    val saving: Boolean = false
) {
    val ready: Boolean get() = !loading && loadError == null
}

class AvatarEditorViewModel(
    private val repository: AvatarRepository = AvatarRepository()
) : ViewModel() {

    private val _state = MutableStateFlow(AvatarEditorState())
    val state: StateFlow<AvatarEditorState> = _state.asStateFlow()

    /** One-shot toasts ("האווטאר נשמר" / the Hebrew error). */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            when (val r = repository.loadMe()) {
                is Resource.Success -> _state.update {
                    it.copy(loading = false, name = r.data.name, avatar = r.data.avatar)
                }
                is Resource.Error -> _state.update { it.copy(loading = false, loadError = r.message) }
                Resource.Loading -> Unit
            }
        }
    }

    fun update(change: (Avatar) -> Avatar) {
        if (_state.value.saving) return
        _state.update { it.copy(avatar = change(it.avatar)) }
    }

    /** The only place the avatar is written to the backend. */
    fun save() {
        val s = _state.value
        if (!s.ready || s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            when (val r = repository.saveAvatar(s.avatar)) {
                is Resource.Success -> {
                    // Show what the server actually saved.
                    _state.update { it.copy(saving = false, avatar = r.data.avatar, name = r.data.name ?: it.name) }
                    _messages.send("האווטאר נשמר")
                }
                is Resource.Error -> {
                    _state.update { it.copy(saving = false) }
                    _messages.send(r.message)
                }
                Resource.Loading -> Unit
            }
        }
    }
}
