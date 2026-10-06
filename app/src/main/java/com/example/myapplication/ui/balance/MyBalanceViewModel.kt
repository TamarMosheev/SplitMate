package com.example.myapplication.ui.balance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.BalanceRepository
import com.example.myapplication.ui.group.GroupDeleter
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MyBalanceViewModel(
    private val repository: BalanceRepository = BalanceRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<MyBalanceUiState>(MyBalanceUiState.Loading)
    val state: StateFlow<MyBalanceUiState> = _state.asStateFlow()

    /** The shared group deletion (same one Home and Group Details use). */
    val deleter = GroupDeleter(viewModelScope)

    init {
        load()
        // The group is gone (deleted or already missing): reload groups, balances and debts from the server.
        viewModelScope.launch {
            deleter.events.collect { if (it.groupIsGone) load(silent = true) }
        }
    }

    /** [silent] keeps the current content on screen while refetching. */
    fun load(silent: Boolean = false) {
        if (!silent) _state.value = MyBalanceUiState.Loading
        viewModelScope.launch {
            when (val result = repository.loadMyBalance()) {
                is Resource.Success -> _state.value = MyBalanceUiState.Success(result.data)
                is Resource.Error -> _state.value = MyBalanceUiState.Error(result.message)
                Resource.Loading -> Unit
            }
        }
    }

    /** Confirmed by the user in the UI; only the creator's groups carry [GroupBalanceUi.canDelete]. */
    fun deleteGroup(group: GroupBalanceUi) {
        if (group.canDelete) deleter.delete(group.id)
    }
}
