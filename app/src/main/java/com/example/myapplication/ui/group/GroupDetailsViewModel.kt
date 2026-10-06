package com.example.myapplication.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.BalanceRepository
import com.example.myapplication.utils.Resource
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

    init {
        load()
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
    }
}
