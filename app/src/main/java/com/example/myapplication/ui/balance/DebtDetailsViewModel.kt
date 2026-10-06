package com.example.myapplication.ui.balance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.repository.ActionResult
import com.example.myapplication.repository.BalanceRepository
import com.example.myapplication.utils.Resource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Reads groupId + settlementId from the launch Intent extras (via [SavedStateHandle]) and loads the debt. */
class DebtDetailsViewModel(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val repository = BalanceRepository()

    private val groupId: String? = savedStateHandle[EXTRA_GROUP_ID]
    private val settlementId: String? = savedStateHandle[EXTRA_SETTLEMENT_ID]

    private val _state = MutableStateFlow<DebtDetailsUiState>(DebtDetailsUiState.Loading)
    val state: StateFlow<DebtDetailsUiState> = _state.asStateFlow()

    /** True while mark-as-paid / reminder is running (buttons disabled). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** One-off messages (action results). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        load()
    }

    /** [silent] keeps the current content visible while refetching (after an action). */
    fun load(silent: Boolean = false) {
        val g = groupId
        val s = settlementId
        if (g.isNullOrBlank() || s.isNullOrBlank()) {
            _state.value = DebtDetailsUiState.Error("פרטי החוב חסרים")
            return
        }
        if (!silent) _state.value = DebtDetailsUiState.Loading
        viewModelScope.launch { _state.value = fetch(g, s) }
    }

    private suspend fun fetch(g: String, s: String): DebtDetailsUiState =
        when (val r = repository.loadDebtDetails(g, s)) {
            is Resource.Success -> DebtDetailsUiState.Success(r.data)
            is Resource.Error -> DebtDetailsUiState.Error(r.message)
            Resource.Loading -> DebtDetailsUiState.Loading
        }

    /** Main button; what it does depends on the real state of the debt (see [DebtActionMode]). */
    fun onPrimaryAction() {
        val mode = (_state.value as? DebtDetailsUiState.Success)?.content?.mode ?: return
        when (mode) {
            DebtActionMode.DEBTOR_CAN_CLAIM -> runAction(
                "עדכנת שהתשלום נשלח. ממתינים לאישור.", DebtActionMode.DEBTOR_CAN_CLAIM
            ) { c -> repository.claimPayment(c.groupId, c.settlementId, c.amount) }

            DebtActionMode.CREDITOR_OPEN -> runAction("סומן כשולם", DebtActionMode.CREDITOR_OPEN) { c ->
                repository.markPaid(c.groupId, c.settlementId, c.amount)
            }

            DebtActionMode.CREDITOR_CLAIM_PENDING -> runAction(
                "התשלום אושר", DebtActionMode.CREDITOR_CLAIM_PENDING
            ) { c -> repository.confirmPayment(c.groupId, c.settlementId, c.amount) }

            DebtActionMode.DEBTOR_CLAIM_PENDING, DebtActionMode.NONE -> Unit
        }
    }

    /** Reminder (creditor with no pending claim only). */
    fun sendReminder() = runAction("התזכורת נשלחה", DebtActionMode.CREDITOR_OPEN) { c ->
        repository.sendReminder(c.groupId, c.settlementId)
    }

    private fun runAction(
        successText: String,
        requiredMode: DebtActionMode,
        call: suspend (DebtDetailsContent) -> ActionResult
    ) {
        val content = (_state.value as? DebtDetailsUiState.Success)?.content ?: return
        if (content.mode != requiredMode || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            when (val result = call(content)) {
                ActionResult.Success -> {
                    _messages.tryEmit(successText)
                    _state.value = fetch(content.groupId, content.settlementId)
                }
                is ActionResult.Failure -> {
                    _messages.tryEmit(result.message)
                    if (result.refresh) _state.value = fetch(content.groupId, content.settlementId)
                }
            }
            _busy.value = false
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "debt_group_id"
        const val EXTRA_SETTLEMENT_ID = "debt_settlement_id"
    }
}
