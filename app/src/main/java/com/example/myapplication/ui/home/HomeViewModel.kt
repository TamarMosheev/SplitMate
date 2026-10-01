package com.example.myapplication.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: HomeRepository = HomeRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** One-off messages (e.g. a failed save). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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
}
