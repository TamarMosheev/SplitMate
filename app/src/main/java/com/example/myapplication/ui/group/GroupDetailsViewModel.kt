package com.example.myapplication.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.model.Expense
import com.example.myapplication.repository.DeleteGroupResult
import com.example.myapplication.repository.ExpenseRepository
import com.example.myapplication.repository.GroupDeletionRepository
import com.example.myapplication.repository.GroupDetails
import com.example.myapplication.utils.Resource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What one expense row renders. */
data class ExpenseUi(
    val id: String,
    val description: String,
    val amount: Double,
    val payerLabel: String,
    val dateText: String?,
    val splitLabel: String?
)

data class GroupDetailsUiState(
    val loading: Boolean = true,
    val group: GroupDetails? = null,
    val groupError: String? = null,
    val expenses: List<ExpenseUi> = emptyList(),
    val expensesLoaded: Boolean = false,
    val expensesError: String? = null,
    /** True only when the signed-in user created this group (the backend enforces the same rule). */
    val canDelete: Boolean = false,
    val deleting: Boolean = false
)

/** One-off results of deleting the group from this screen. */
sealed class GroupDetailsEvent {
    data class Message(val text: String) : GroupDetailsEvent()

    /** The backend deleted the group: close this screen. */
    data class Deleted(val text: String) : GroupDetailsEvent()
}

class GroupDetailsViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val repository = ExpenseRepository()
    private val deletion = GroupDeletionRepository()
    private val groupId: String = savedStateHandle.get<String>(EXTRA_GROUP_ID).orEmpty()

    private val _state = MutableStateFlow(GroupDetailsUiState())
    val state: StateFlow<GroupDetailsUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<GroupDetailsEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<GroupDetailsEvent> = _events.asSharedFlow()

    private var rawExpenses: List<Expense> = emptyList()

    init {
        if (groupId.isEmpty()) {
            _state.update { it.copy(loading = false, groupError = "לא נבחרה קבוצה") }
        } else {
            // Both listeners live exactly as long as this ViewModel's scope and are removed with it.
            viewModelScope.launch {
                repository.observeGroup(groupId).collect { result ->
                    when (result) {
                        is Resource.Success -> {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    group = result.data,
                                    groupError = null,
                                    // Same rule as the backend: only the creator may delete the group.
                                    canDelete = result.data.createdBy.isNotBlank() && result.data.createdBy == repository.currentUid
                                )
                            }
                            publishExpenses() // payer names depend on the member list
                        }
                        is Resource.Error -> _state.update { it.copy(loading = false, groupError = result.message) }
                        Resource.Loading -> Unit
                    }
                }
            }
            viewModelScope.launch {
                repository.observeExpenses(groupId).collect { result ->
                    when (result) {
                        is Resource.Success -> {
                            rawExpenses = result.data
                            _state.update { it.copy(expensesLoaded = true, expensesError = null) }
                            publishExpenses()
                        }
                        is Resource.Error -> _state.update { it.copy(expensesLoaded = true, expensesError = result.message) }
                        Resource.Loading -> Unit
                    }
                }
            }
        }
    }

    /**
     * Deletes this group through the real backend (DELETE /groups/{id}). On success the screen is closed; on any
     * failure the group stays and a message is shown. Double taps are ignored while a delete is running.
     */
    fun deleteGroup() {
        if (groupId.isEmpty() || _state.value.deleting) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val event = when (deletion.deleteGroup(groupId)) {
                DeleteGroupResult.Deleted -> GroupDetailsEvent.Deleted("הקבוצה נמחקה בהצלחה")
                // The group no longer exists, so there is nothing left to show on this screen either.
                DeleteGroupResult.AlreadyGone -> GroupDetailsEvent.Deleted("הקבוצה כבר לא קיימת")
                DeleteGroupResult.NotAllowed -> GroupDetailsEvent.Message("אין לך הרשאה למחוק את הקבוצה")
                DeleteGroupResult.SessionExpired -> GroupDetailsEvent.Message("פג תוקף ההתחברות. התחברו מחדש ונסו שוב")
                DeleteGroupResult.Failed -> GroupDetailsEvent.Message("לא ניתן למחוק את הקבוצה. נסי שוב.")
            }
            _state.update { it.copy(deleting = false) }
            _events.tryEmit(event)
        }
    }

    private fun publishExpenses() {
        _state.update { s ->
            val names = s.group?.members.orEmpty().associate { it.uid to it.name }
            val format = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            s.copy(
                expenses = rawExpenses.map { e ->
                    val payer = names[e.paidBy]?.takeIf { it.isNotBlank() } ?: "משתמש"
                    ExpenseUi(
                        id = e.id,
                        description = e.description.ifBlank { "הוצאה" },
                        amount = e.amount,
                        payerLabel = "שולם על ידי $payer",
                        dateText = e.createdAtMillis?.let { format.format(Date(it)) },
                        splitLabel = when (e.splitType) {
                            "equal" -> "חלוקה שווה"
                            "exact" -> "סכומים מדויקים"
                            else -> null
                        }
                    )
                }
            )
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
    }
}
