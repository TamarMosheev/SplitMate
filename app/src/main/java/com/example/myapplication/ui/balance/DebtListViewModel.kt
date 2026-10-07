package com.example.myapplication.ui.balance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.BalanceRepository
import com.example.myapplication.utils.Resource
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DebtListContent(
    val owedToMe: Boolean,
    val total: BigDecimal,
    val debts: List<DebtUi>
)

sealed class DebtListUiState {
    object Loading : DebtListUiState()
    data class Success(val content: DebtListContent) : DebtListUiState()
    data class Error(val message: String) : DebtListUiState()
}

/**
 * "את חייבת" / "חייבים לך": the same open settlements My Balance loads (one repository call, no second
 * calculation), filtered by direction. `DebtUi.owedToMe` is already derived from settlement.to/from == uid.
 */
class DebtListViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val repository = BalanceRepository()
    val owedToMe: Boolean = savedStateHandle[EXTRA_OWED_TO_ME] ?: false

    private val _state = MutableStateFlow<DebtListUiState>(DebtListUiState.Loading)
    val state: StateFlow<DebtListUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(silent: Boolean = false) {
        if (!silent) _state.value = DebtListUiState.Loading
        viewModelScope.launch {
            when (val result = repository.loadMyBalance()) {
                is Resource.Success -> {
                    val debts = result.data.debts.filter { it.owedToMe == owedToMe }
                    _state.value = DebtListUiState.Success(
                        DebtListContent(owedToMe, debts.fold(BigDecimal.ZERO) { a, d -> a + d.amount }, debts)
                    )
                }
                is Resource.Error -> _state.value = DebtListUiState.Error(result.message)
                Resource.Loading -> Unit
            }
        }
    }

    companion object {
        const val EXTRA_OWED_TO_ME = "owedToMe"
    }
}
