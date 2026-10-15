package com.example.myapplication.ui.expense

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.BalanceRepository
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The single Expense Details screen: loads one expense fresh from the backend by groupId + expenseId. */
class ExpenseDetailsViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val repository = BalanceRepository()
    private val groupId: String = savedStateHandle.get<String>(EXTRA_GROUP_ID).orEmpty()
    private val expenseId: String = savedStateHandle.get<String>(EXTRA_EXPENSE_ID).orEmpty()

    private val _state = MutableStateFlow<ExpenseDetailsUiState>(ExpenseDetailsUiState.Loading)
    val state: StateFlow<ExpenseDetailsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(silent: Boolean = false) {
        if (groupId.isEmpty() || expenseId.isEmpty()) {
            _state.value = ExpenseDetailsUiState.Error("פרטי ההוצאה חסרים")
            return
        }
        if (!silent) _state.value = ExpenseDetailsUiState.Loading
        viewModelScope.launch {
            val r = repository.loadExpenseDetails(groupId, expenseId)
            android.util.Log.i("DEBTS_DEBUG", "vm: received ${(r as? com.example.myapplication.utils.Resource.Success)?.data?.debts?.size} debts, error=${(r as? com.example.myapplication.utils.Resource.Success)?.data?.debtsError}") // TEMP
            when (r) {
                is Resource.Success -> _state.value = ExpenseDetailsUiState.Success(r.data)
                is Resource.Error -> _state.value = ExpenseDetailsUiState.Error(r.message)
                Resource.Loading -> Unit
            }
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "expense_group_id"
        const val EXTRA_EXPENSE_ID = "expense_id"
    }
}
