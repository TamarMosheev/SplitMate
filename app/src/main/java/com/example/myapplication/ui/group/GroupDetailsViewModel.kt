package com.example.myapplication.ui.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.model.Expense
import com.example.myapplication.repository.ExpenseRepository
import com.example.myapplication.repository.GroupDetails
import com.example.myapplication.utils.Resource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val expensesError: String? = null
)

class GroupDetailsViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val repository = ExpenseRepository()
    private val groupId: String = savedStateHandle.get<String>(EXTRA_GROUP_ID).orEmpty()

    private val _state = MutableStateFlow(GroupDetailsUiState())
    val state: StateFlow<GroupDetailsUiState> = _state.asStateFlow()

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
                            _state.update { it.copy(loading = false, group = result.data, groupError = null) }
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
